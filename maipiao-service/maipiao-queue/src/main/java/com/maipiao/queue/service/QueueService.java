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

/** 基于 Redis 的场次等待队列。队列 key 使用 ZSet、inflight 集合和短期令牌。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    /** 原子弹出候选用户并生成准入令牌。 */
    private static final RedisScript<List> ADMIT_SCRIPT =
            LuaScriptLoader.ofList("lua/queue_admit.lua");

    private final StringRedisTemplate redis;
    private final MovieClient movieClient;
    private final QueueProperties properties;

    /** 进程内闸门，合并并发的场次元数据查询。 */
    private final AtomicLong sessionFetchGate = new AtomicLong();

    private static final long GATE_HOLD_MS = 2000;

    // ------------------------------------------------------------
    // 加入队列
    // ------------------------------------------------------------

    /** 将用户加入队列；重复加入保留首次排位。 */
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
            // 还没开始。提早加入没有意义 —— 队列按到达顺序排，而现在根本还没有可到达
            // 的东西 —— 所以告诉客户端待会儿再来，而不是给它一个之后还得自己去守住的
            // 位置。
            return QueueDtos.PositionVO.of(QueueDtos.Status.NOT_STARTED, 0, 0, 0, rushStart);
        }

        String waitKey = CommonConstants.QUEUE_WAIT_KEY + scheduleId;
        redis.opsForZSet().addIfAbsent(waitKey, String.valueOf(userId),
                System.currentTimeMillis());
        redis.expire(waitKey, Duration.ofSeconds(properties.getScheduleTtlSeconds()));

        // 把场次登记上，好让调度器找得到它。放在这里做而不是由 movie-service 做，
        // 是为了两个服务永远不必就 key 的命名空间达成一致；队列排空后由回收逻辑扫掉。
        redis.opsForSet().add(CommonConstants.RUSH_SCHEDULES_KEY, String.valueOf(scheduleId));

        return position(scheduleId, userId);
    }

    /** 用户此刻排在哪里。 */
    public QueueDtos.PositionVO position(Long scheduleId, Long userId) {
        String uid = String.valueOf(userId);

        // 顺序很重要。一个已经被放进来、之后才售罄的用户，仍然是"已被放进来"的：令牌
        // 在他手上，座位也还没从他手里被拿走。先报 SOLD_OUT 会把一次成功的准入变成一个
        // 死胡同。
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
            // 从没加入过，或者曾经被放进来但令牌已经过期了。两种情况下都没有位置可报；
            // 客户端拿到这个结果后要做的就是让他重新走一遍 join。
            Map<String, Object> session = sessionOf(scheduleId);
            return QueueDtos.PositionVO.of(QueueDtos.Status.WAITING, 0, 0,
                    total == null ? 0 : total.intValue(), rushStartOf(session));
        }

        return QueueDtos.PositionVO.of(QueueDtos.Status.WAITING,
                rank.intValue() + 1, rank.intValue(),
                total == null ? 0 : total.intValue(), null);
    }

    /** 移除尚未放行的排队位置。已发放的令牌由 TTL 回收。 */
    public void leave(Long scheduleId, Long userId) {
        redis.opsForZSet().remove(CommonConstants.QUEUE_WAIT_KEY + scheduleId,
                String.valueOf(userId));
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

    /**
     * 放一批人进来，并给每人发一个令牌。
     *
     * <p>用 Lua 原子弹出而不是"先范围读再删除"：两个同时醒来的实例
     * 不可能取到同一个 member、把同一个人放进来两次。正是这一条性质，让本服务不需要
     * 选主。
     *
     * <p>令牌在这里铸造，而不是从客户端收下的，座位服务之后会拿它去和同一个 key 比对。
     * 客户端造不出一个有效令牌。
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

        // 传 toArray，不能传 list：RedisTemplate 唯一的脚本方法签名是可变参数，所以
        // 传一个 List 进去，它就收到一个类型为 List 的单个参数，序列化器在试图把它强转
        // 成 String 时就炸了。
        List<?> admitted = redis.execute(ADMIT_SCRIPT, keys, args.toArray());
        if (admitted == null || admitted.isEmpty()) {
            return 0;
        }

        int count = admitted.size() / 2;
        log.info("admitted {} of {} waiting: schedule={}",
                count, count + waiting(scheduleId), scheduleId);
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
        Long removed = redis.opsForZSet()
                .removeRangeByScore(inflightKey, 0, System.currentTimeMillis());
        return removed == null ? 0 : removed.intValue();
    }

    /**
     * 校验一个准入令牌。
     *
     * <p>座位服务会经由网关的过滤器调用一次，然后在真正发出座位之前自己再调一次。真正
     * 起作用的是第二次：网关是从 query string 读场次 id 的，而那是客户端能控制的，所以
     * 网关无论怎样都只能算个泄压阀。座位服务则是从令牌自己的 key 里读出来的，那个它控制
     * 不了。
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
     * 把令牌消费掉，且只能一次。
     *
     * <p>在座位真正被拿走的时刻调用。用过之后还能继续生效的令牌，会让一次准入反复购买，
     * 而这就是排队存在要防的全部内容。
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

    /**
     * 还有多少个座位可卖。
     *
     * <p>从 bitmap 读，而不是从场次的计数列读，因为座位服务接下来动手改的就是这张
     * bitmap。用计数列等于给整个销售赖以为生的那一个数字又造一个真相来源，而两者迟早
     * 会对不上 —— 到那时，调度器就是在拿一个别人都不认的数字来放人。
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
     * 场次元数据，带缓存。
     *
     * <p>本服务会读的每一个字段都被缓存，而不只是第一个调用方需要的那几个。早先的版本
     * 只存了其中两个，剩下的留给未命中时去取，于是从第二个调用方开始，读到的是一个
     * 缺了自己那个字段的缓存命中 —— {@code rushMode} 取回来是空的，一场抢购把自己
     * 说成了一场普通放映。一个答非所问的缓存，比没有缓存更糟。
     *
     * <p>用 hash 而不是序列化后的 blob，这样值能保住自己的类型，版本变更时也不必就日期
     * 格式达成一致。
     *
     * <p>拉取为什么要串行化，见 {@link #sessionFetchGate}。失败时退回一个空 map 而不是
     * 抛异常：调用方仍然可以把用户排进队列，而且下一轮本来就会重新读一遍，所以一次抖动
     * 的代价是一秒钟的准确度，而不是整场销售。
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
                        // 宁可不要这个字段，也不给它一个默认值：字段缺失是看得出来的，
                        // 而一个 0 和一个真实的 0 完全分不出来。
                    }
                }
                case "rushStartTime", "saleStartTime" -> {
                    try {
                        session.put(field, LocalDateTime.parse(value));
                    } catch (Exception ignored) {
                        // 同样的道理。
                    }
                }
                default -> session.put(field, value);
            }
        }
        return session;
    }

    /** 对一个并不是抢购的场次，拒绝执行抢购相关的操作。 */
    public void requireRushSale(Long scheduleId) {
        Object rushMode = sessionOf(scheduleId).get("rushMode");
        if (!(rushMode instanceof Number number) || number.intValue() != 1) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_ON_SALE, "该场次不是抢购场次");
        }
    }

    /** 所有有排队的场次，以 id 形式给出。 */
    public List<Long> rushScheduleIds() {
        return rushSchedules().stream().map(Long::valueOf).toList();
    }
}
