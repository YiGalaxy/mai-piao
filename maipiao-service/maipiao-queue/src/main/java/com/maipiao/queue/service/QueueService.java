package com.maipiao.queue.service;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.redis.script.LuaScriptLoader;
import com.maipiao.queue.config.QueueProperties;
import com.maipiao.queue.dto.QueueDtos;
import com.maipiao.queue.feign.MovieClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The waiting line.
 *
 * <p>Everything here is Redis and nothing here is durable, on purpose. A queue
 * fills up in the seconds after tickets open and is empty minutes later;
 * writing it to a database would mean a table that is almost always empty and
 * a write on the hottest path in the system. If Redis is gone the sale cannot
 * proceed anyway, so there is nothing to be durable for.
 *
 * <p>Four keys per screening:
 *
 * <ul>
 *   <li>{@code queue:wait:{id}} - sorted set, member is the user id and score
 *       is when they arrived. A sorted set rather than a list because joining
 *       twice must not put you in line twice, and a set member is unique by
 *       construction - the second {@code ZADD} just moves a score it should
 *       not move, so it is written to preserve the original arrival time.</li>
 *   <li>{@code queue:inflight:{id}} - who has been admitted and has not acted
 *       yet. Admission is sized against this as well as against stock, or a
 *       dispatcher waking twice would admit twice.</li>
 *   <li>{@code queue:token:{id}:{uid}} - the admission token itself.</li>
 *   <li>{@code rush:schedules} - which screenings are running a line, so the
 *       dispatcher has something to walk.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    /**
     * Admission is a script, not {@code ZPOPMIN}.
     *
     * <p>Redisson cannot decode the reply to {@code ZPOPMIN}: the pop lands on
     * the server and the client never learns what it took, so the places
     * disappear without anybody being admitted. It presents as an empty queue,
     * which is what it looks like when everything is working.
     */
    private static final RedisScript<List> ADMIT_SCRIPT =
            LuaScriptLoader.ofList("lua/queue_admit.lua");

    private final StringRedisTemplate redis;
    private final MovieClient movieClient;
    private final QueueProperties properties;

    /**
     * Guards the session-metadata fetch against a stampede.
     *
     * <p>The moment a rush opens, tens of thousands of joins arrive at once and
     * every one of them needs the same metadata. Without this each would be a
     * cache miss and each miss would be a call to movie-service, turning the
     * busiest second of the sale into a fan-out onto a service that is not
     * built for it.
     *
     * <p>Process-local and coarse: one thread fetches while the rest wait for
     * it. That is the right trade for a value that is identical for every
     * caller and about to be cached anyway.
     */
    private final AtomicLong sessionFetchGate = new AtomicLong();

    private static final long GATE_HOLD_MS = 2000;

    // ------------------------------------------------------------
    // joining
    // ------------------------------------------------------------

    /**
     * Puts the user in line, or leaves them where they are.
     *
     * <p>Idempotent by construction: {@code ZADD NX} adds a member only when it
     * is absent, so a user who reloads the page a hundred times keeps their
     * original place instead of losing it, and a user who tries to join twice
     * to get a better position simply does not move.
     */
    public QueueDtos.PositionVO join(Long scheduleId, Long userId) {
        Map<String, Object> session = sessionOf(scheduleId);

        if (isSoldOut(scheduleId)) {
            return QueueDtos.PositionVO.of(QueueDtos.Status.SOLD_OUT, 0, 0, 0, null);
        }
        if (isPaused(scheduleId)) {
            return QueueDtos.PositionVO.of(QueueDtos.Status.PAUSED, 0, 0, 0,
                    rushStartOf(session));
        }

        LocalDateTime rushStart = rushStartOf(session);
        if (rushStart != null && LocalDateTime.now().isBefore(rushStart)) {
            // Not open. Joining early would be meaningless - the line is
            // ordered by arrival and there is nothing to arrive for yet - so
            // the client is told to come back rather than given a place it
            // would then have to defend.
            return QueueDtos.PositionVO.of(QueueDtos.Status.NOT_STARTED, 0, 0, 0, rushStart);
        }

        String waitKey = CommonConstants.QUEUE_WAIT_KEY + scheduleId;
        redis.opsForZSet().addIfAbsent(waitKey, String.valueOf(userId),
                System.currentTimeMillis());
        redis.expire(waitKey, Duration.ofSeconds(properties.getScheduleTtlSeconds()));

        // Register the screening so the dispatcher finds it. Done here rather
        // than by movie-service so the two services never have to agree on a
        // key space, and swept by the reaper when the line drains.
        redis.opsForSet().add(CommonConstants.RUSH_SCHEDULES_KEY, String.valueOf(scheduleId));

        return position(scheduleId, userId);
    }

    /** Where the user stands right now. */
    public QueueDtos.PositionVO position(Long scheduleId, Long userId) {
        String uid = String.valueOf(userId);

        // Order matters. A user who was admitted and then sold out is still
        // admitted: the token is in their hand and the seat is not yet taken
        // from them. Reporting SOLD_OUT first would turn a successful
        // admission into a dead end.
        String status = statusOf(scheduleId, uid);
        if (QueueDtos.Status.PASSED.equals(status)) {
            Long ttl = redis.getExpire(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + uid);
            return new QueueDtos.PositionVO(QueueDtos.Status.PASSED, 0, 0, 0,
                    redis.opsForValue().get(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + uid),
                    ttl == null || ttl < 0 ? properties.getTokenSeconds() : ttl,
                    null);
        }

        if (isSoldOut(scheduleId)) {
            return QueueDtos.PositionVO.of(QueueDtos.Status.SOLD_OUT, 0, 0, 0, null);
        }
        if (isPaused(scheduleId)) {
            return QueueDtos.PositionVO.of(QueueDtos.Status.PAUSED, 0, 0, 0, null);
        }

        String waitKey = CommonConstants.QUEUE_WAIT_KEY + scheduleId;
        Long rank = redis.opsForZSet().rank(waitKey, uid);
        Long total = redis.opsForZSet().zCard(waitKey);

        if (rank == null) {
            // Never joined, or already admitted and the token has since
            // expired. Either way there is no place to report; sending them
            // back through join is what the client does with this.
            Map<String, Object> session = sessionOf(scheduleId);
            return QueueDtos.PositionVO.of(QueueDtos.Status.WAITING, 0, 0,
                    total == null ? 0 : total.intValue(), rushStartOf(session));
        }

        return QueueDtos.PositionVO.of(QueueDtos.Status.WAITING,
                rank.intValue() + 1, rank.intValue(),
                total == null ? 0 : total.intValue(), null);
    }

    /**
     * Leaves the line.
     *
     * <p>Only removes a place that has not been admitted. Someone who has a
     * token and closes the tab has given up their place in the line already;
     * what they are holding is not a place but a claim on a seat, and that
     * expires on its own. Releasing it here would let a user free a seat they
     * are still deciding about.
     */
    public void leave(Long scheduleId, Long userId) {
        redis.opsForZSet().remove(CommonConstants.QUEUE_WAIT_KEY + scheduleId,
                String.valueOf(userId));
    }

    // ------------------------------------------------------------
    // admission - called by the dispatcher
    // ------------------------------------------------------------

    /** Screenings currently running a line. */
    public Set<String> rushSchedules() {
        Set<String> ids = redis.opsForSet().members(CommonConstants.RUSH_SCHEDULES_KEY);
        return ids == null ? Set.of() : ids;
    }

    /** Drops a screening from the registry once nothing is left in it. */
    public void deregister(Long scheduleId) {
        redis.opsForSet().remove(CommonConstants.RUSH_SCHEDULES_KEY, String.valueOf(scheduleId));
    }

    /**
     * Admits a batch and hands each one a token.
     *
     * <p>{@code ZPOPMIN} rather than a range read followed by a delete: it is
     * atomic, so two instances waking at the same moment cannot both take the
     * same member and admit the same person twice. That single property is why
     * this service needs no leader election.
     *
     * <p>Tokens are minted here rather than accepted from the client, and the
     * seat service will later check the token against the same key. A client
     * cannot invent one.
     */
    public int admit(Long scheduleId, int quota) {
        List<String> keys = List.of(
                CommonConstants.QUEUE_WAIT_KEY + scheduleId,
                CommonConstants.QUEUE_INFLIGHT_KEY + scheduleId,
                CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":");

        List<String> args = List.of(
                String.valueOf(quota),
                String.valueOf(System.currentTimeMillis() + properties.getTokenSeconds() * 1000L),
                java.util.UUID.randomUUID().toString(),
                String.valueOf(properties.getTokenSeconds()),
                String.valueOf(properties.getScheduleTtlSeconds()));

        // toArray, not the list: RedisTemplate's only script signature takes
        // varargs, so passing a List hands it a single argument of type List
        // and the serializer fails trying to cast it to String.
        List<?> admitted = redis.execute(ADMIT_SCRIPT, keys, args.toArray());
        if (admitted == null || admitted.isEmpty()) {
            return 0;
        }

        int count = admitted.size() / 2;
        log.info("admitted {} of {} waiting: schedule={}",
                count, count + waiting(scheduleId), scheduleId);
        return count;
    }

    /** How many are waiting, admitted, or in flight. */
    public int size(String key) {
        Long n = redis.opsForZSet().zCard(key);
        return n == null ? 0 : n.intValue();
    }

    public int waiting(Long scheduleId) {
        return size(CommonConstants.QUEUE_WAIT_KEY + scheduleId);
    }

    public int inflight(Long scheduleId) {
        return size(CommonConstants.QUEUE_INFLIGHT_KEY + scheduleId);
    }

    /** Removes in-flight entries whose token has lapsed. */
    public int reapExpired(Long scheduleId) {
        String inflightKey = CommonConstants.QUEUE_INFLIGHT_KEY + scheduleId;
        Long removed = redis.opsForZSet()
                .removeRangeByScore(inflightKey, 0, System.currentTimeMillis());
        return removed == null ? 0 : removed.intValue();
    }

    /**
     * Verifies an admission token.
     *
     * <p>Called by the seat service through the gateway's filter, and again by
     * the seat service itself before it hands out a seat. The second check is
     * the one that matters: the gateway reads the schedule id from the query
     * string, which a client controls, so the gateway can only ever be a
     * pressure valve. The seat service reads it from the token's own key,
     * which it cannot.
     */
    public boolean verifyToken(Long scheduleId, Long userId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String stored = redis.opsForValue()
                .get(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + userId);
        return token.equals(stored);
    }

    /**
     * Consumes a token, once.
     *
     * <p>Called when the seat is actually taken. A token that stayed valid
     * after use would let one admission buy repeatedly, which is the whole
     * thing the line exists to prevent.
     */
    public boolean consumeToken(Long scheduleId, Long userId, String token) {
        if (!verifyToken(scheduleId, userId, token)) {
            return false;
        }
        redis.delete(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + userId);
        redis.opsForZSet().remove(CommonConstants.QUEUE_INFLIGHT_KEY + scheduleId,
                String.valueOf(userId));
        return true;
    }

    // ------------------------------------------------------------
    // pause switch
    // ------------------------------------------------------------

    public void pause(Long scheduleId) {
        redis.opsForValue().set(CommonConstants.RUSH_PAUSED_KEY + scheduleId, "1");
        log.warn("rush sale paused: schedule={}", scheduleId);
    }

    public void resume(Long scheduleId) {
        redis.delete(CommonConstants.RUSH_PAUSED_KEY + scheduleId);
        log.warn("rush sale resumed: schedule={}", scheduleId);
    }

    // ------------------------------------------------------------
    // state
    // ------------------------------------------------------------

    public boolean isPaused(Long scheduleId) {
        return Boolean.TRUE.equals(redis.hasKey(CommonConstants.RUSH_PAUSED_KEY + scheduleId));
    }

    public boolean isSoldOut(Long scheduleId) {
        return Boolean.TRUE.equals(redis.hasKey(CommonConstants.SOLD_OUT_KEY + scheduleId));
    }

    public Long totalSeats(Long scheduleId) {
        Object value = sessionOf(scheduleId).get("totalSeat");
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * How many seats are still sellable.
     *
     * <p>Read from the bitmap rather than from the session counters, because it
     * is the bitmap the seat service is about to act on. Counters would be a
     * second source of truth for the one number the sale turns on, and the two
     * would eventually disagree - at which point the dispatcher would be
     * admitting against a number nobody else believes.
     */
    public int remaining(Long scheduleId) {
        Long total = totalSeats(scheduleId);
        if (total == null) {
            return 0;
        }
        Long taken = redis.execute(
                (org.springframework.data.redis.core.RedisCallback<Long>) connection ->
                        connection.stringCommands()
                                .bitCount((CommonConstants.SEAT_MAP_KEY + scheduleId).getBytes()),
                true);
        long occupied = taken == null ? 0 : taken;
        return (int) Math.max(0, total - occupied);
    }

    // ------------------------------------------------------------

    private String statusOf(Long scheduleId, String userId) {
        return redis.opsForValue()
                .get(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + userId) != null
                ? QueueDtos.Status.PASSED : QueueDtos.Status.WAITING;
    }

    private LocalDateTime rushStartOf(Map<String, Object> session) {
        Object value = session.get("rushStartTime");
        if (value instanceof LocalDateTime time) {
            return time;
        }
        return null;
    }

    /**
     * Session metadata, cached.
     *
     * <p>Every field this service reads is cached, not just the ones the first
     * caller needed. An earlier version stored two of them and left the rest
     * to be fetched on a miss, so the second caller onwards read a cache hit
     * that was missing its own field - {@code rushMode} came back absent and a
     * rush sale announced itself as an ordinary screening. A cache that
     * answers a different question than the one asked is worse than no cache.
     *
     * <p>A hash rather than a serialised blob, so the values keep their types
     * and there is no date format to agree on across a version change.
     *
     * <p>See {@link #sessionFetchGate} for why the fetch is serialised. A
     * failure falls back to an empty map rather than throwing: the caller can
     * still put the user in line, and the next round re-reads this anyway, so
     * a blip costs a second of accuracy rather than the sale.
     */
    private Map<String, Object> sessionOf(Long scheduleId) {
        String key = CommonConstants.QUEUE_SESSION_KEY + scheduleId;

        Map<Object, Object> cached = redis.opsForHash().entries(key);
        if (!cached.isEmpty()) {
            return typed(cached);
        }

        long now = System.currentTimeMillis();
        long gate = sessionFetchGate.get();
        if (now - gate < GATE_HOLD_MS && !sessionFetchGate.compareAndSet(gate, now)) {
            return Map.of();
        }
        sessionFetchGate.set(now);

        try {
            var response = movieClient.snapshot(scheduleId);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return Map.of();
            }
            Map<String, Object> session = response.getData();

            Map<String, String> store = new java.util.HashMap<>();
            for (String field : CACHED_FIELDS) {
                Object value = session.get(field);
                if (value != null) {
                    store.put(field, String.valueOf(value));
                }
            }
            if (!store.isEmpty()) {
                redis.opsForHash().putAll(key, store);
                redis.expire(key, Duration.ofSeconds(properties.getSessionCacheSeconds()));
            }
            return session;
        } catch (Exception e) {
            log.warn("could not read session metadata: schedule={}", scheduleId, e);
            return Map.of();
        }
    }

    /** What this service reads off a session. Everything it reads, and nothing else. */
    private static final List<String> CACHED_FIELDS =
            List.of("totalSeat", "rushMode", "rushStartTime", "saleStartTime", "status");

    /** Puts the string values from Redis back into the types callers expect. */
    private Map<String, Object> typed(Map<Object, Object> cached) {
        Map<String, Object> session = new java.util.HashMap<>();
        for (Map.Entry<Object, Object> entry : cached.entrySet()) {
            String field = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            switch (field) {
                case "totalSeat", "rushMode", "status" -> {
                    try {
                        session.put(field, Long.valueOf(value));
                    } catch (NumberFormatException ignored) {
                        // Left out rather than defaulted: a missing field is
                        // visible as absent, a zero is indistinguishable from
                        // a real one.
                    }
                }
                case "rushStartTime", "saleStartTime" -> {
                    try {
                        session.put(field, LocalDateTime.parse(value));
                    } catch (Exception ignored) {
                        // Same reasoning.
                    }
                }
                default -> session.put(field, value);
            }
        }
        return session;
    }

    /** Refuses a rush-sale action on a screening that is not one. */
    public void requireRushSale(Long scheduleId) {
        Object rushMode = sessionOf(scheduleId).get("rushMode");
        if (!(rushMode instanceof Number number) || number.intValue() != 1) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE, "该场次不是抢购场次");
        }
    }

    /** All screenings with a line, as ids. */
    public List<Long> rushScheduleIds() {
        return rushSchedules().stream().map(Long::valueOf).toList();
    }
}
