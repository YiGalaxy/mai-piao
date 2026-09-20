package com.maipiao.seat.service;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.redis.script.LuaScriptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The seat bitmap, and the only place that writes to it.
 *
 * <p>Every mutation goes through a Lua script. That is not a stylistic
 * choice: it is the entire concurrency story. Redis executes a script as a
 * single unit, so the check-then-write inside {@code seat_lock.lua} cannot be
 * interleaved with another request. Splitting it into GETBIT and SETBIT calls
 * would leave a window in which two users both read "free" and both proceed,
 * and the result would be a seat sold twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatBitmapService {

    private static final RedisScript<List> LOCK_SCRIPT =
            LuaScriptLoader.ofList("lua/seat_lock.lua");
    private static final RedisScript<Long> RELEASE_SCRIPT =
            LuaScriptLoader.ofLong("lua/seat_release.lua");
    private static final RedisScript<Long> CONFIRM_SCRIPT =
            LuaScriptLoader.ofLong("lua/seat_confirm.lua");
    private static final RedisScript<List> MAP_QUERY_SCRIPT =
            LuaScriptLoader.ofList("lua/seat_map_query.lua");
    private static final RedisScript<Long> VERIFY_SCRIPT =
            LuaScriptLoader.ofLong("lua/seat_verify.lua");
    private static final RedisScript<List> ALLOCATE_SCRIPT =
            LuaScriptLoader.ofList("lua/seat_allocate.lua");

    private final StringRedisTemplate redis;

    /** Outcome of a lock attempt. */
    public record LockResult(boolean success, int lockedCount, int conflictSeatIndex) {

        public static LockResult ok(int count) {
            return new LockResult(true, count, -1);
        }

        public static LockResult conflict(int seatIndex) {
            return new LockResult(false, 0, seatIndex);
        }
    }

    // ------------------------------------------------------------
    // lock
    // ------------------------------------------------------------

    /**
     * Claims every requested seat, or none of them.
     *
     * @return success with the number claimed, or a conflict naming the first
     *         seat that was already taken
     */
    public LockResult lock(Long sessionId, String orderNo, int totalSeat,
                           List<Integer> seatIndexes, Duration ttl) {

        if (seatIndexes == null || seatIndexes.isEmpty()) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "未选择座位");
        }

        List<String> keys = List.of(
                CommonConstants.SEAT_MAP_KEY + sessionId,
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo,
                CommonConstants.SOLD_OUT_KEY + sessionId);

        List<String> args = new ArrayList<>(seatIndexes.size() + 3);
        args.add(orderNo);
        args.add(String.valueOf(System.currentTimeMillis() + ttl.toMillis()));
        args.add(String.valueOf(totalSeat));
        for (Integer index : seatIndexes) {
            args.add(String.valueOf(index));
        }

        List<?> result = execute(LOCK_SCRIPT, keys, args);
        if (result == null || result.size() < 2) {
            // The script always returns a two-element table; anything else
            // means Redis or the script is not what we think it is.
            log.error("unexpected lock script result: {}", result);
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE);
        }

        long flag = toLong(result.get(0));
        long value = toLong(result.get(1));

        if (flag == 1L) {
            log.debug("seats locked: schedule={}, order={}, count={}", sessionId, orderNo, value);
            return LockResult.ok((int) value);
        }

        log.debug("seat conflict: schedule={}, order={}, seatIndex={}", sessionId, orderNo, value);
        return LockResult.conflict((int) value);
    }

    // ------------------------------------------------------------
    // allocate
    // ------------------------------------------------------------

    /** Outcome of an allocation attempt. */
    public record AllocateResult(boolean success, List<Integer> seatIndexes, int longestFreeRun) {

        public static AllocateResult ok(List<Integer> seatIndexes) {
            return new AllocateResult(true, seatIndexes, seatIndexes.size());
        }

        public static AllocateResult noRun(int longestFreeRun) {
            return new AllocateResult(false, List.of(), longestFreeRun);
        }
    }

    /**
     * Takes seats from a ranked list of candidate runs.
     *
     * <p>The candidates come from {@link SeatRuns} and are a snapshot: between
     * computing them and running this, somebody else may have taken one of the
     * seats. The script re-checks every bit itself, so a stale candidate can
     * only ever cost a retry, never a double sale. That is the whole reason the
     * geometry is allowed to live outside the atomic section.
     *
     * @param runs     candidate runs, best first; each must be adjacent in both
     *                 index and geometry, which is what {@link SeatRuns}
     *                 produces
     * @param adjacent whether the seats must come from within one run, or may
     *                 be picked from anywhere in the candidate order
     */
    public AllocateResult allocate(Long sessionId, String orderNo, int count, int totalSeat,
                                   List<SeatRuns.Segment> runs, boolean adjacent, Duration ttl) {

        if (count < 1 || runs == null || runs.isEmpty()) {
            return AllocateResult.noRun(0);
        }

        List<String> keys = List.of(
                CommonConstants.SEAT_MAP_KEY + sessionId,
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo,
                CommonConstants.SOLD_OUT_KEY + sessionId);

        List<String> args = new ArrayList<>(5 + runs.size() * 2);
        args.add(orderNo);
        args.add(String.valueOf(System.currentTimeMillis() + ttl.toMillis()));
        args.add(String.valueOf(count));
        args.add(String.valueOf(totalSeat));
        args.add(adjacent ? "1" : "0");
        for (SeatRuns.Segment run : runs) {
            args.add(String.valueOf(run.startIndex()));
            args.add(String.valueOf(run.length()));
        }

        List<?> result = execute(ALLOCATE_SCRIPT, keys, args);
        if (result == null || result.isEmpty()) {
            log.error("unexpected allocate script result: {}", result);
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE);
        }

        if (toLong(result.get(0)) != 1L) {
            int longest = result.size() < 2 ? 0 : (int) toLong(result.get(1));
            log.info("could not assign {} seats (adjacent={}): schedule={}, longest run={}",
                    count, adjacent, sessionId, longest);
            return AllocateResult.noRun(longest);
        }

        // The script reports the seats it took rather than a starting point,
        // precisely because in split mode there is no starting point that
        // implies the rest.
        List<Integer> seatIndexes = new ArrayList<>(result.size() - 1);
        for (int i = 1; i < result.size(); i++) {
            seatIndexes.add((int) toLong(result.get(i)));
        }

        if (seatIndexes.size() != count) {
            log.error("allocate returned {} seats for a request of {}: {}",
                    seatIndexes.size(), count, seatIndexes);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分配结果与请求数量不一致");
        }

        log.debug("seats assigned: schedule={}, order={}, count={}, seats={}",
                sessionId, orderNo, count, seatIndexes);
        return AllocateResult.ok(seatIndexes);
    }

    // ------------------------------------------------------------
    // verify
    // ------------------------------------------------------------

    /**
     * True when the order still holds every seat it names.
     *
     * <p>This is the check that makes the lock token mean something at order
     * time. The token is issued when the seats are locked and carries no
     * expiry of its own, so on its own it stays "valid" long after the hold it
     * refers to has lapsed and the seats have been taken by somebody else.
     *
     * <p>It narrows a race, it does not close one: a release can still land
     * between this call and the transaction that follows. What stops that from
     * overselling is the ledger's own {@code status = 0} compare-and-set. This
     * call exists so the realistic case - a page left open past the hold, then
     * submitted - is refused outright instead of costing the current holder
     * their seat.
     */
    public boolean verifyOwnership(Long sessionId, String orderNo, List<Integer> seatIndexes) {
        if (seatIndexes == null || seatIndexes.isEmpty()) {
            return false;
        }

        List<String> keys = List.of(CommonConstants.SEAT_OWNER_KEY + sessionId);
        List<String> args = new ArrayList<>(seatIndexes.size() + 1);
        args.add(orderNo);
        for (Integer index : seatIndexes) {
            args.add(String.valueOf(index));
        }

        Long held = execute(VERIFY_SCRIPT, keys, args);
        boolean ok = held != null && held == 1L;
        if (!ok) {
            log.debug("hold no longer owned by order: schedule={}, order={}", sessionId, orderNo);
        }
        return ok;
    }

    // ------------------------------------------------------------
    // release / confirm
    // ------------------------------------------------------------

    /**
     * Frees the seats held by an order.
     *
     * <p>Idempotent, and safe to run after the seats have moved on: the script
     * only clears a seat whose owner marker still points at this order.
     *
     * @param force release seats even when the owner marker is gone; for the
     *              reconciliation job only, never a normal flow
     * @return how many seats were actually released
     */
    public int release(Long sessionId, String orderNo, boolean force) {
        List<String> keys = List.of(
                CommonConstants.SEAT_MAP_KEY + sessionId,
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo,
                CommonConstants.SOLD_OUT_KEY + sessionId);

        Long released = execute(RELEASE_SCRIPT, keys, List.of(orderNo, force ? "1" : "0"));
        int count = released == null ? 0 : released.intValue();

        if (count > 0) {
            log.debug("seats released: schedule={}, order={}, count={}", sessionId, orderNo, count);
        }
        return count;
    }

    /**
     * Marks the seats as sold rather than merely locked.
     *
     * <p>The bitmap bit stays set - a sold seat is no more available than a
     * locked one. What changes is the owner marker, so a later release can
     * tell a paid seat from a held one.
     */
    public int confirm(Long sessionId, String orderNo) {
        List<String> keys = List.of(
                CommonConstants.SEAT_OWNER_KEY + sessionId,
                CommonConstants.SEAT_DELAY_KEY + sessionId,
                CommonConstants.SEAT_ORDER_KEY + orderNo);

        Long confirmed = execute(CONFIRM_SCRIPT, keys, List.of(orderNo));
        return confirmed == null ? 0 : confirmed.intValue();
    }

    // ------------------------------------------------------------
    // read / rebuild
    // ------------------------------------------------------------

    /** @return the occupied seat indexes, ascending */
    @SuppressWarnings("unchecked")
    public List<Integer> findOccupiedIndexes(Long sessionId, int totalSeats) {
        List<String> keys = List.of(CommonConstants.SEAT_MAP_KEY + sessionId);
        List<?> raw = execute(MAP_QUERY_SCRIPT, keys, List.of(String.valueOf(totalSeats)));

        List<Integer> occupied = new ArrayList<>();
        if (raw != null) {
            for (Object item : raw) {
                occupied.add((int) toLong(item));
            }
        }
        return occupied;
    }

    /** True when the bitmap for this screening has been built. */
    public boolean isInitialised(Long sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(CommonConstants.SEAT_MAP_KEY + sessionId));
    }

    /**
     * Seeds a cold bitmap from the ledger, without ever clearing a bit.
     *
     * <p>This is the lazy path, taken whenever a screening's bitmap is missing
     * - a fresh deploy, a flushed Redis, a screening nobody has opened yet.
     * Several requests hit a cold bitmap at the same moment by definition,
     * since they are what made it cold, so this has to be safe to run
     * concurrently with itself and with allocations.
     *
     * <p>Which is why it only sets bits. The earlier version built a temporary
     * key and renamed it over the live one, which is atomic against a reader
     * but not against a writer: every concurrent arrival rebuilt from a ledger
     * that did not yet know about the seats the others had just handed out,
     * and the rename discarded them. Measured under a rush sale - 300 buyers,
     * 100 concurrent - it sold 271 seats into 240, with 21 seats handed to two
     * people. With a warm bitmap the same test sells 160 into 160 and doubles
     * nothing.
     *
     * <p>The cost of merging is that a stale bit cannot be cleared this way.
     * That is the right way round: the ledger is authoritative for what is
     * sold, and a seat that stays occupied is a seat nobody can be sold twice.
     * Clearing a bit is a repair, and repair is {@link #rebuild}.
     */
    public void seed(Long sessionId, List<Integer> occupiedIndexes) {
        if (occupiedIndexes == null || occupiedIndexes.isEmpty()) {
            return;
        }
        String mapKey = CommonConstants.SEAT_MAP_KEY + sessionId;
        for (Integer index : occupiedIndexes) {
            redis.opsForValue().setBit(mapKey, index, true);
        }
        log.info("seat bitmap seeded from ledger: schedule={}, occupied={}",
                sessionId, occupiedIndexes.size());
    }

    /**
     * Rebuilds the bitmap from the ledger, discarding whatever was there.
     *
     * <p>For repair, not for cold starts. Written to a temporary key and then
     * renamed, so a reader sees either the old bitmap or the new one and never
     * a partial one - but a writer that runs concurrently loses its bits, so
     * this must not run while a screening is selling. {@link #seed} is the
     * path that is safe to reach for lazily.
     *
     * <p>The empty case is handled explicitly. A screening where nothing has
     * been sold yet produces no SETBIT calls at all, so the temporary key is
     * never created and RENAME fails with "no such key" - on the most common
     * screening there is. Writing a single zero bit creates the key while
     * leaving every seat available.
     */
    public void rebuild(Long sessionId, List<Integer> occupiedIndexes) {
        String tempKey = CommonConstants.SEAT_MAP_KEY + sessionId + ":rebuild";
        String finalKey = CommonConstants.SEAT_MAP_KEY + sessionId;

        redis.delete(tempKey);

        if (occupiedIndexes == null || occupiedIndexes.isEmpty()) {
            redis.opsForValue().setBit(tempKey, 0, false);
        } else {
            for (Integer index : occupiedIndexes) {
                redis.opsForValue().setBit(tempKey, index, true);
            }
        }

        // RENAME is atomic; there is no instant at which the key is absent.
        redis.rename(tempKey, finalKey);

        int count = occupiedIndexes == null ? 0 : occupiedIndexes.size();
        log.info("seat bitmap rebuilt: schedule={}, occupied={}", sessionId, count);
    }

    /**
     * Restores owner markers after a rebuild.
     *
     * <p>Every occupied seat needs one, taken or held alike. The owner marker
     * is what the release script compares against, so a rebuilt seat without
     * one can never be freed: the release sees no owner, decides the seat is
     * not this order's, and leaves the bit set. A held seat rebuilt this way
     * is dead from the moment it is cancelled until the next rebuild.
     *
     * <p>Sold seats get the {@code SOLD:} prefix so the release path can tell
     * them from held ones and refuse to put a paid-for seat back on sale.
     *
     * @param owners seat index to owner marker
     */
    public void markOwners(Long sessionId, Map<String, String> owners) {
        if (owners.isEmpty()) {
            return;
        }
        String ownerKey = CommonConstants.SEAT_OWNER_KEY + sessionId;
        redis.opsForHash().putAll(ownerKey, owners);
    }

    // ------------------------------------------------------------

    /**
     * Runs a script, translating a Redis outage into a business error.
     *
     * <p>Deliberately fail-closed. If Redis is unreachable the lock cannot be
     * taken safely, and the alternative - falling back to database row locks -
     * would put the entire seat-selection load onto MySQL and take the
     * database down with it. Refusing the request is the better failure.
     */
    private <T> T execute(RedisScript<T> script, List<String> keys, List<String> args) {
        try {
            return redis.execute(script, keys, args.toArray());
        } catch (DataAccessException e) {
            log.error("redis unavailable while executing {}", script.getSha1(), e);
            throw new BizException(ErrorCode.SEAT_MAP_UNAVAILABLE);
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
