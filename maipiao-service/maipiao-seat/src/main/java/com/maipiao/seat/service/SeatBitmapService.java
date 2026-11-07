package com.maipiao.seat.service;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.redis.script.LuaScriptLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/** 座位 bitmap 的唯一写入口；变更必须通过 Lua 保证检查与写入原子化。 */
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
    private static final RedisScript<List> SWEEP_SCRIPT =
            LuaScriptLoader.ofList("lua/seat_sweep.lua");

    private final StringRedisTemplate redis;

    /** 一次加锁尝试的结果。 */
    public record LockResult(boolean success, int lockedCount, int conflictSeatIndex) {

        public static LockResult ok(int count) {
            return new LockResult(true, count, -1);
        }

        public static LockResult conflict(int seatIndex) {
            return new LockResult(false, 0, seatIndex);
        }
    }

    // ------------------------------------------------------------
    // 加锁
    // ------------------------------------------------------------

    /**
     * 请求的座位要么全拿到，要么一个都不拿。
     *
     * @return 成功时带上拿到的数量；冲突时给出第一个已被占用的座位索引
     */
    public LockResult lock(
            Long sessionId,
            String orderNo,
            int totalSeat,
            List<Integer> seatIndexes,
            Duration ttl) {
        return lock(
                sessionId,
                orderNo,
                totalSeat,
                seatIndexes,
                System.currentTimeMillis() + ttl.toMillis());
    }

    public LockResult lock(
            Long sessionId,
            String orderNo,
            int totalSeat,
            List<Integer> seatIndexes,
            long expiresAt) {

        if (seatIndexes == null || seatIndexes.isEmpty()) {
            throw new BizException(ErrorCode.SEAT_INDEX_INVALID, "未选择座位");
        }

        List<String> keys =
                List.of(
                        CommonConstants.SEAT_MAP_KEY + sessionId,
                        CommonConstants.SEAT_OWNER_KEY + sessionId,
                        CommonConstants.SEAT_DELAY_KEY + sessionId,
                        CommonConstants.SEAT_ORDER_KEY + orderNo,
                        CommonConstants.SOLD_OUT_KEY + sessionId,
                        CommonConstants.SEAT_ACTIVE_KEY);

        List<String> args = new ArrayList<>(seatIndexes.size() + 4);
        args.add(orderNo);
        args.add(String.valueOf(expiresAt));
        args.add(String.valueOf(totalSeat));
        args.add(String.valueOf(sessionId));
        for (Integer index : seatIndexes) {
            args.add(String.valueOf(index));
        }

        List<?> result = execute(LOCK_SCRIPT, keys, args);
        if (result == null || result.size() < 2) {
            // 返回结构异常时拒绝继续，避免误判为加锁成功。
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
    // 分配
    // ------------------------------------------------------------

    /** 一次分配尝试的结果。 */
    public record AllocateResult(boolean success, List<Integer> seatIndexes, int longestFreeRun) {

        public static AllocateResult ok(List<Integer> seatIndexes) {
            return new AllocateResult(true, seatIndexes, seatIndexes.size());
        }

        public static AllocateResult noRun(int longestFreeRun) {
            return new AllocateResult(false, List.of(), longestFreeRun);
        }
    }

    /** 从候选连座段分配座位；脚本会再次校验 bitmap，防止快照过期导致超卖。 */
    public AllocateResult allocate(
            Long sessionId,
            String orderNo,
            int count,
            int totalSeat,
            List<SeatRuns.Segment> runs,
            boolean adjacent,
            Duration ttl) {
        return allocate(
                sessionId,
                orderNo,
                count,
                totalSeat,
                runs,
                adjacent,
                System.currentTimeMillis() + ttl.toMillis());
    }

    public AllocateResult allocate(
            Long sessionId,
            String orderNo,
            int count,
            int totalSeat,
            List<SeatRuns.Segment> runs,
            boolean adjacent,
            long expiresAt) {

        if (count < 1 || runs == null || runs.isEmpty()) {
            return AllocateResult.noRun(0);
        }

        List<String> keys =
                List.of(
                        CommonConstants.SEAT_MAP_KEY + sessionId,
                        CommonConstants.SEAT_OWNER_KEY + sessionId,
                        CommonConstants.SEAT_DELAY_KEY + sessionId,
                        CommonConstants.SEAT_ORDER_KEY + orderNo,
                        CommonConstants.SOLD_OUT_KEY + sessionId,
                        CommonConstants.SEAT_ACTIVE_KEY);

        List<String> args = new ArrayList<>(6 + runs.size() * 2);
        args.add(orderNo);
        args.add(String.valueOf(expiresAt));
        args.add(String.valueOf(count));
        args.add(String.valueOf(totalSeat));
        args.add(adjacent ? "1" : "0");
        args.add(String.valueOf(sessionId));
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
            log.info(
                    "could not assign {} seats (adjacent={}): schedule={}, longest run={}",
                    count,
                    adjacent,
                    sessionId,
                    longest);
            return AllocateResult.noRun(longest);
        }

        // 返回实际座位索引，拆分模式不能只返回起点。
        List<Integer> seatIndexes = new ArrayList<>(result.size() - 1);
        for (int i = 1; i < result.size(); i++) {
            seatIndexes.add((int) toLong(result.get(i)));
        }

        if (seatIndexes.size() != count) {
            log.error(
                    "allocate returned {} seats for a request of {}: {}",
                    seatIndexes.size(),
                    count,
                    seatIndexes);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分配结果与请求数量不一致");
        }

        log.debug(
                "seats assigned: schedule={}, order={}, count={}, seats={}",
                sessionId,
                orderNo,
                count,
                seatIndexes);
        return AllocateResult.ok(seatIndexes);
    }

    // ------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------

    /** 校验订单仍持有全部指定座位；最终防超卖依赖账本的状态 CAS。 */
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
    // 释放 / 确认
    // ------------------------------------------------------------

    /** 释放订单座位；脚本按 owner 校验，支持重试幂等。{@code includeSold} 仅退款使用。 */
    public int release(Long sessionId, String orderNo, boolean force, boolean includeSold) {
        List<String> keys =
                List.of(
                        CommonConstants.SEAT_MAP_KEY + sessionId,
                        CommonConstants.SEAT_OWNER_KEY + sessionId,
                        CommonConstants.SEAT_DELAY_KEY + sessionId,
                        CommonConstants.SEAT_ORDER_KEY + orderNo,
                        CommonConstants.SOLD_OUT_KEY + sessionId);

        Long released =
                execute(
                        RELEASE_SCRIPT,
                        keys,
                        List.of(orderNo, force ? "1" : "0", includeSold ? "1" : "0"));
        int count = released == null ? 0 : released.intValue();

        if (count > 0) {
            log.debug("seats released: schedule={}, order={}, count={}", sessionId, orderNo, count);
        }
        return count;
    }

    /** 将 owner 标记改为已售；bitmap 保持置位，退款时据此区分锁定与售出。 */
    public int confirm(Long sessionId, String orderNo) {
        List<String> keys =
                List.of(
                        CommonConstants.SEAT_OWNER_KEY + sessionId,
                        CommonConstants.SEAT_DELAY_KEY + sessionId,
                        CommonConstants.SEAT_ORDER_KEY + orderNo);

        Long confirmed = execute(CONFIRM_SCRIPT, keys, List.of(orderNo));
        return confirmed == null ? 0 : confirmed.intValue();
    }

    // ------------------------------------------------------------
    // 超时回收
    // ------------------------------------------------------------

    /** 还有未释放占用的场次。 */
    public Set<String> activeSessions() {
        Set<String> members = redis.opsForSet().members(CommonConstants.SEAT_ACTIVE_KEY);
        return members == null ? Set.of() : members;
    }

    /** 从活跃场次集合移除无效成员。 */
    public void forgetActiveSession(String sessionId) {
        redis.opsForSet().remove(CommonConstants.SEAT_ACTIVE_KEY, sessionId);
    }

    /** 查询已到期的占用，不直接释放；释放统一走 seat_release.lua。 */
    @SuppressWarnings("unchecked")
    public List<String> findExpiredHolds(Long sessionId, int limit) {
        List<String> keys =
                List.of(
                        CommonConstants.SEAT_DELAY_KEY + sessionId,
                        CommonConstants.SEAT_ACTIVE_KEY);

        List<?> raw =
                execute(
                        SWEEP_SCRIPT,
                        keys,
                        List.of(
                                String.valueOf(sessionId),
                                String.valueOf(System.currentTimeMillis()),
                                String.valueOf(limit)));

        List<String> expired = new ArrayList<>();
        if (raw != null) {
            for (Object item : raw) {
                expired.add(String.valueOf(item));
            }
        }
        return expired;
    }

    // ------------------------------------------------------------
    // 读取 / 重建
    // ------------------------------------------------------------

    /**
     * @return 已占用的座位索引，升序
     */
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

    /** 该场次的 bitmap 是否已经建好。 */
    public boolean isInitialised(Long sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(CommonConstants.SEAT_MAP_KEY + sessionId));
    }

    /** 冷启动时只置位，不清除并发写入；脏 bit 由 {@link #rebuild} 修复。 */
    public void seed(Long sessionId, List<Integer> occupiedIndexes) {
        if (occupiedIndexes == null || occupiedIndexes.isEmpty()) {
            return;
        }
        String mapKey = CommonConstants.SEAT_MAP_KEY + sessionId;
        for (Integer index : occupiedIndexes) {
            redis.opsForValue().setBit(mapKey, index, true);
        }
        log.info(
                "seat bitmap seeded from ledger: schedule={}, occupied={}",
                sessionId,
                occupiedIndexes.size());
    }

    /** 用账本重建 bitmap；写入临时 key 后原子替换，仅用于停售或修复窗口。 */
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

        // RENAME 原子替换，读者不会看到半成品。
        redis.rename(tempKey, finalKey);

        int count = occupiedIndexes == null ? 0 : occupiedIndexes.size();
        log.info("seat bitmap rebuilt: schedule={}, occupied={}", sessionId, count);
    }

    /** 重建后补齐 owner 标记；已售座位使用 {@code SOLD:} 前缀。 */
    public void markOwners(Long sessionId, Map<String, String> owners) {
        if (owners.isEmpty()) {
            return;
        }
        String ownerKey = CommonConstants.SEAT_OWNER_KEY + sessionId;
        redis.opsForHash().putAll(ownerKey, owners);
    }

    // ------------------------------------------------------------

    /** 执行脚本并将 Redis 故障转为业务错误；不可确认时 fail-closed。 */
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
