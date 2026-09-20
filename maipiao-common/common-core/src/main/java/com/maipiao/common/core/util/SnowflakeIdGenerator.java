package com.maipiao.common.core.util;

/**
 * Snowflake id 生成器。
 *
 * <p>位布局（64 位，符号位不用）：
 * <pre>
 *   0 | 41 位 timestamp | 5 位 datacenter | 5 位 worker | 12 位 sequence
 * </pre>
 * 41 位的毫秒时间戳从下面那个 epoch 起算约 69 年；12 位序列号允许每个节点每毫秒
 * 生成 4096 个 id。
 *
 * <p>这对本项目意味着什么：订单号和支付号都是 snowflake id。它们不需要一次数据库
 * 往返就能做到全局唯一，让热点路径不碰 DB；而且它们是按时间递增的，这才使它们将来
 * 能当分片键用。
 *
 * <p>时钟回拨：如果墙上时钟往回走，我们宁可拒绝生成，也不冒着发出重复 id 的风险。
 * 小幅回拨（<= 5ms）就短暂自旋等过去；再大就是真问题了，必须大声暴露出来。
 */
public final class SnowflakeIdGenerator {

    /** 自定义 epoch：2024-01-01T00:00:00Z。从这里起算能买到约 69 年。 */
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);            // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);    // 31
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);             // 4095

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                                     // 12
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;                 // 17
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22

    /** 我们愿意靠等来容忍的最大回拨幅度。 */
    private static final long MAX_TOLERATED_ROLLBACK_MS = 5L;

    private final long workerId;
    private final long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId must be between 0 and " + MAX_DATACENTER_ID);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /**
     * @return 一个唯一的、按时间递增的 64 位 id
     */
    public synchronized long nextId() {
        long timestamp = currentTime();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > MAX_TOLERATED_ROLLBACK_MS) {
                throw new IllegalStateException(
                        "clock moved backwards by " + offset + "ms, refusing to generate an id");
            }
            // 小幅回拨：等时钟追上来。
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 这一毫秒的序列号用完了 —— 推进到下一毫秒。
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            // 新的毫秒：把序列号归 0，可以让同一毫秒内的 id 单调递增，分片键正是
            // 依赖这一点。
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /** 方便那些以字符串形式呈现的 id 类型（order_no、payment_no）。 */
    public String nextIdString() {
        return Long.toString(nextId());
    }

    private long waitUntil(long targetTimestamp) {
        long timestamp = currentTime();
        while (timestamp < targetTimestamp) {
            Thread.onSpinWait();
            timestamp = currentTime();
        }
        return timestamp;
    }

    private long currentTime() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------
    // 给没有配置节点 id 的服务用的默认实例。
    //
    // 在真实的多节点部署里，每个实例都需要一对不同的
    // (datacenterId, workerId)。常见做法是从 hostname / pod 序号推导；
    // 写死一对值在本地开发没问题，但两个实例共用它就会撞出重复 id。
    // ------------------------------------------------------------
    private static final SnowflakeIdGenerator DEFAULT =
            new SnowflakeIdGenerator(workerIdFromEnv(), datacenterIdFromEnv());

    public static long next() {
        return DEFAULT.nextId();
    }

    public static String nextString() {
        return DEFAULT.nextIdString();
    }

    private static long workerIdFromEnv() {
        return readEnv("MAIPIAO_WORKER_ID", 1L);
    }

    private static long datacenterIdFromEnv() {
        return readEnv("MAIPIAO_DATACENTER_ID", 1L);
    }

    private static long readEnv(String key, long fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
