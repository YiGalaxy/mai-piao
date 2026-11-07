package com.maipiao.queue.service;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.redis.script.LuaScriptLoader;
import com.maipiao.queue.config.QueueProperties;
import com.maipiao.queue.dto.QueueDtos;
import com.maipiao.queue.feign.MovieClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/** Redis 等待队列；队列丢失可重新入队，交易座位仍以持久化账本为准。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    /** 准入使用 Lua 原子完成额度核算、出队和发令牌。 */
    private static final RedisScript<List> ADMIT_SCRIPT =
            LuaScriptLoader.ofList("lua/queue_admit.lua");

    private static final RedisScript<Long> CONSUME_SCRIPT =
            LuaScriptLoader.ofLong("lua/queue_consume.lua");

    private final StringRedisTemplate redis;
    private final MovieClient movieClient;
    private final QueueProperties properties;

    private final java.util.concurrent.ConcurrentHashMap<
                    Long, java.util.concurrent.CompletableFuture<Map<String, Object>>>
            sessionLoads = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> failedLoads =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ------------------------------------------------------------
    // 加入队列
    // ------------------------------------------------------------

    /** 加入等待队列；按用户维度幂等，重复请求不改变原排位。 */
    public QueueDtos.PositionVO join(Long scheduleId, Long userId) {
        Map<String, Object> session = sessionOf(scheduleId);
        if (!(session.get("status") instanceof Number status)
                || status.intValue() != 1
                || !(session.get("rushMode") instanceof Number rush)
                || rush.intValue() != 1) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE);
        }

        if (isSoldOut(scheduleId)) {
            return QueueDtos.PositionVO.of(QueueDtos.Status.SOLD_OUT, 0, 0, 0, null);
        }
        if (isPaused(scheduleId)) {
            return QueueDtos.PositionVO.of(QueueDtos.Status.PAUSED, 0, 0, 0, rushStartOf(session));
        }

        LocalDateTime rushStart = rushStartOf(session);
        if (rushStart != null && LocalDateTime.now().isBefore(rushStart)) {
            // 抢购未开始，不提前占用排位。
            return QueueDtos.PositionVO.of(QueueDtos.Status.NOT_STARTED, 0, 0, 0, rushStart);
        }

        String waitKey = CommonConstants.QUEUE_WAIT_KEY + scheduleId;
        redis.opsForZSet().addIfAbsent(waitKey, String.valueOf(userId), System.currentTimeMillis());
        redis.expire(waitKey, Duration.ofSeconds(properties.getScheduleTtlSeconds()));

        // 注册场次，供调度器遍历；队列排空后再移除。
        redis.opsForSet().add(CommonConstants.RUSH_SCHEDULES_KEY, String.valueOf(scheduleId));

        return position(scheduleId, userId);
    }

    /** 用户此刻排在哪里。 */
    public QueueDtos.PositionVO position(Long scheduleId, Long userId) {
        String uid = String.valueOf(userId);

        // 先判断已放行令牌，避免售罄覆盖已成功准入的状态。
        String status = statusOf(scheduleId, uid);
        if (QueueDtos.Status.PASSED.equals(status)) {
            Long ttl = redis.getExpire(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + uid);
            return new QueueDtos.PositionVO(
                    QueueDtos.Status.PASSED,
                    0,
                    0,
                    0,
                    redis.opsForValue()
                            .get(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + uid),
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
            // 未入队或令牌已过期，返回空排位，客户端需重新入队。
            Map<String, Object> session = sessionOf(scheduleId);
            return QueueDtos.PositionVO.of(
                    QueueDtos.Status.WAITING,
                    0,
                    0,
                    total == null ? 0 : total.intValue(),
                    rushStartOf(session));
        }

        return QueueDtos.PositionVO.of(
                QueueDtos.Status.WAITING,
                rank.intValue() + 1,
                rank.intValue(),
                total == null ? 0 : total.intValue(),
                null);
    }

    /** 仅移除尚未放行的排队记录；已发放的令牌按 TTL 过期。 */
    public void leave(Long scheduleId, Long userId) {
        redis.opsForZSet()
                .remove(CommonConstants.QUEUE_WAIT_KEY + scheduleId, String.valueOf(userId));
    }

    // ------------------------------------------------------------
    // 准入 —— 由调度器调用
    // ------------------------------------------------------------

    /** 当前正在排队的场次。 */
    public Set<String> rushSchedules() {
        Set<String> ids = redis.opsForSet().members(CommonConstants.RUSH_SCHEDULES_KEY);
        return ids == null ? Set.of() : ids;
    }

    /** 场次里已经什么都不剩时，把它从注册表里摘掉。 */
    public void deregister(Long scheduleId) {
        redis.opsForSet().remove(CommonConstants.RUSH_SCHEDULES_KEY, String.valueOf(scheduleId));
    }

    /** 原子核算额度、出队并签发令牌；多实例共享 inflight 上限。 */
    public int admit(Long scheduleId, int quota, int target) {
        List<String> keys =
                List.of(
                        CommonConstants.QUEUE_WAIT_KEY + scheduleId,
                        CommonConstants.QUEUE_INFLIGHT_KEY + scheduleId,
                        CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":");

        List<String> args =
                List.of(
                        String.valueOf(quota),
                        String.valueOf(
                                System.currentTimeMillis() + properties.getTokenSeconds() * 1000L),
                        java.util.UUID.randomUUID().toString(),
                        String.valueOf(properties.getTokenSeconds()),
                        String.valueOf(properties.getScheduleTtlSeconds()),
                        String.valueOf(target));

        // RedisTemplate 脚本参数必须展开为可变参数。
        List<?> admitted = redis.execute(ADMIT_SCRIPT, keys, args.toArray());
        if (admitted == null || admitted.isEmpty()) {
            return 0;
        }

        int count = admitted.size() / 2;
        log.info(
                "admitted {} of {} waiting: schedule={}",
                count,
                count + waiting(scheduleId),
                scheduleId);
        return count;
    }

    /** 有多少人在等待、已放行、或正在途中。 */
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

    /** 清掉令牌已经过期的 inflight 条目。 */
    public int reapExpired(Long scheduleId) {
        String inflightKey = CommonConstants.QUEUE_INFLIGHT_KEY + scheduleId;
        Long removed =
                redis.opsForZSet().removeRangeByScore(inflightKey, 0, System.currentTimeMillis());
        return removed == null ? 0 : removed.intValue();
    }

    /** 在实际锁座时一次性消费令牌，防止重复使用。 */
    public boolean consumeToken(Long scheduleId, Long userId, String token) {
        if (token == null || token.isBlank()) return false;
        return Long.valueOf(1)
                .equals(
                        redis.execute(
                                CONSUME_SCRIPT,
                                List.of(
                                        CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + userId,
                                        CommonConstants.QUEUE_INFLIGHT_KEY + scheduleId),
                                token,
                                String.valueOf(userId)));
    }

    // ------------------------------------------------------------
    // 暂停开关
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
    // 状态
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

    /** 从座位 bitmap 统计余票，避免把场次计数列作为第二真相源。 */
    public int remaining(Long scheduleId) {
        Long total = totalSeats(scheduleId);
        if (total == null) {
            return 0;
        }
        Long taken =
                redis.execute(
                        (org.springframework.data.redis.core.RedisCallback<Long>)
                                connection ->
                                        connection
                                                .stringCommands()
                                                .bitCount(
                                                        (CommonConstants.SEAT_MAP_KEY + scheduleId)
                                                                .getBytes()),
                        true);
        long occupied = taken == null ? 0 : taken;
        return (int) Math.max(0, total - occupied);
    }

    // ------------------------------------------------------------

    private String statusOf(Long scheduleId, String userId) {
        return redis.opsForValue().get(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + userId)
                        != null
                ? QueueDtos.Status.PASSED
                : QueueDtos.Status.WAITING;
    }

    private LocalDateTime rushStartOf(Map<String, Object> session) {
        Object value = session.get("rushStartTime");
        if (value instanceof LocalDateTime time) {
            return time;
        }
        return null;
    }

    /** 按场次合并并发回源；读取失败明确拒绝，短暂退避。 */
    private Map<String, Object> sessionOf(Long scheduleId) {
        String key = CommonConstants.QUEUE_SESSION_KEY + scheduleId;

        Map<Object, Object> cached = redis.opsForHash().entries(key);
        if (!cached.isEmpty()) {
            return typed(cached);
        }

        long now = System.currentTimeMillis();
        if (failedLoads.getOrDefault(scheduleId, 0L) > now) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "场次信息暂不可用");
        }
        var loading = new java.util.concurrent.CompletableFuture<Map<String, Object>>();
        var existing = sessionLoads.putIfAbsent(scheduleId, loading);
        if (existing != null) {
            try {
                return existing.join();
            } catch (java.util.concurrent.CompletionException e) {
                throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "场次信息暂不可用");
            }
        }

        try {
            // 再查一次，关闭缓存读取与抢占加载权之间的窗口。
            cached = redis.opsForHash().entries(key);
            if (!cached.isEmpty()) {
                var session = typed(cached);
                loading.complete(session);
                return session;
            }
            var response = movieClient.snapshot(scheduleId);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "场次信息暂不可用");
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
            Map<Object, Object> normalized = new java.util.HashMap<>();
            normalized.putAll(store);
            session = typed(normalized);
            failedLoads.remove(scheduleId);
            loading.complete(session);
            return session;
        } catch (Exception e) {
            log.warn("could not read session metadata: schedule={}", scheduleId, e);
            if (failedLoads.size() >= 2048)
                failedLoads.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (failedLoads.size() < 2048)
                failedLoads.put(scheduleId, System.currentTimeMillis() + 2000);
            loading.completeExceptionally(e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "场次信息暂不可用");
        } finally {
            sessionLoads.remove(scheduleId, loading);
        }
    }

    /** 本服务会从场次上读的所有字段。全都在这，一个不多。 */
    private static final List<String> CACHED_FIELDS =
            List.of("totalSeat", "rushMode", "rushStartTime", "saleStartTime", "status");

    /** 把 Redis 里的字符串值还原成调用方期望的类型。 */
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
                        // 解析失败时保留缺失状态，不填默认值。
                    }
                }
                case "rushStartTime", "saleStartTime" -> {
                    try {
                        session.put(field, LocalDateTime.parse(value));
                    } catch (Exception ignored) {
                        // 时间格式错误时保留缺失状态。
                    }
                }
                default -> session.put(field, value);
            }
        }
        return session;
    }

    /** 所有有排队的场次，以 id 形式给出。 */
    public List<Long> rushScheduleIds() {
        return rushSchedules().stream().map(Long::valueOf).toList();
    }
}
