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
 * 等待队列。
 *
 * <p>这里所有东西都在 Redis 上，没有任何东西是持久的，这是刻意的。队列在开票之后的
 * 几秒内被灌满，几分钟后就空了；把它写进数据库，意味着多一张几乎永远是空的表，以及
 * 在系统最热的那条路径上多一次写入。Redis 要是没了，售票本来也进行不下去，所以没有
 * 什么东西值得为持久化去换。
 *
 * <p>每个场次四个 key：
 *
 * <ul>
 *   <li>{@code queue:wait:{id}} —— ZSet，member 是用户 id，score 是他们到达的时间。
 *       用 ZSet 而不是 list，因为重复加入不能让人排两次队，而 set 的 member 按构造
 *       就是唯一的 —— 第二次 {@code ZADD} 只会去改一个本来不该被改的 score，所以这里
 *       写成保留最初到达时间的方式。</li>
 *   <li>{@code queue:inflight:{id}} —— 谁已经被放进来但还没动作。准入名额既要按库存算，
 *       也要按它来算，否则一个调度器醒两次就会放两次人。</li>
 *   <li>{@code queue:token:{id}:{uid}} —— 准入令牌本身。</li>
 *   <li>{@code rush:schedules} —— 哪些场次正在排队，好让调度器有东西可遍历。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    /**
     * 准入是一个脚本，而不是 {@code ZPOPMIN}。
     *
     * <p>Redisson 解不了 {@code ZPOPMIN} 的应答：弹出在服务端发生了，客户端却永远不知道
     * 弹走了什么，于是排队位置凭空消失，而没有任何人被放进来。它的表象就是"队列是空的"
     * —— 和一切正常时的样子一模一样。
     */
    private static final RedisScript<List> ADMIT_SCRIPT =
            LuaScriptLoader.ofList("lua/queue_admit.lua");

    private final StringRedisTemplate redis;
    private final MovieClient movieClient;
    private final QueueProperties properties;

    /**
     * 拦住场次元数据拉取的惊群。
     *
     * <p>抢购一开始的那一瞬间，几万个 join 同时涌进来，而它们每一个都要同一份元数据。
     * 没有这道闸，每一次都会是缓存未命中，每一次未命中都会变成一次对 movie-service 的
     * 调用，把整场销售里最忙的那一秒变成一个扇出，打向一个并不是为此而建的服务。
     *
     * <p>进程本地、且很粗糙：一个线程去拉，其余等它。对一个所有调用方拿到的都是同一份、
     * 而且马上就要被缓存的值来说，这个取舍是对的。
     */
    private final AtomicLong sessionFetchGate = new AtomicLong();

    private static final long GATE_HOLD_MS = 2000;

    // ------------------------------------------------------------
    // 加入队列
    // ------------------------------------------------------------

    /**
     * 把用户排进队列，或者让他待在原地不动。
     *
     * <p>按构造就是幂等的：{@code ZADD NX} 只在 member 不存在时才添加，所以一个把页面
     * 刷新了一百次的用户会保住他最初的位置，而不是把它丢掉；一个想靠重复加入换个好位置
     * 的用户，则干脆不会挪动。
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

    /**
     * 离开队列。
     *
     * <p>只移除还没被放行的排队位置。一个拿着令牌就把标签页关掉的人，其实早就已经放弃了
     * 他的排队位置；他手上攥着的不是一个位置，而是对某个座位的主张，而那个会自己过期。
     * 在这里把它释放掉，等于让用户可以随手放掉一个他还在犹豫要不要的座位。
     */
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
     * <p>用 {@code ZPOPMIN} 而不是"先范围读再删除"：它是原子的，所以两个同时醒来的实例
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
