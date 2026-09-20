package com.maoyan.common.core.util;

/**
 * Snowflake id generator.
 *
 * <p>Layout (64 bits, sign bit unused):
 * <pre>
 *   0 | 41 bits timestamp | 5 bits datacenter | 5 bits worker | 12 bits sequence
 * </pre>
 * 41 bits of millisecond timestamp is ~69 years from the epoch below; 12 bits of
 * sequence allows 4096 ids per millisecond per node.
 *
 * <p>Why this matters to the project: order numbers and payment numbers are
 * snowflake ids. They are globally unique without a database round trip, which
 * keeps the hot path off the DB, and they are time-ordered, which is what makes
 * them usable as a future sharding gene.
 *
 * <p>Clock rollback: if the wall clock moves backwards we refuse to generate
 * rather than risk emitting a duplicate id. For a small rollback (<= 5ms) we
 * briefly spin; anything larger is a real problem and must surface loudly.
 */
public final class SnowflakeIdGenerator {

    /** Custom epoch: 2024-01-01T00:00:00Z. Buying ~69 years from here. */
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

    /** Smallest rollback we tolerate by waiting it out. */
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
     * @return a unique, time-ordered 64-bit id
     */
    public synchronized long nextId() {
        long timestamp = currentTime();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > MAX_TOLERATED_ROLLBACK_MS) {
                throw new IllegalStateException(
                        "clock moved backwards by " + offset + "ms, refusing to generate an id");
            }
            // Short rollback: wait for the clock to catch up.
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // Sequence exhausted for this millisecond - advance to the next one.
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            // New millisecond: resetting the sequence to 0 makes ids within the
            // same millisecond monotonically increasing, which the sharding gene relies on.
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /** Convenience for id types that are rendered as strings (order_no, payment_no). */
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
    // Default instance for services that do not configure a node id.
    //
    // In a real multi-node deployment each instance needs a distinct
    // (datacenterId, workerId). Deriving it from the hostname / pod ordinal
    // is the usual approach; a hard-coded pair is fine for local dev but
    // will produce collisions if two instances share it.
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
        return readEnv("MAOYAN_WORKER_ID", 1L);
    }

    private static long datacenterIdFromEnv() {
        return readEnv("MAOYAN_DATACENTER_ID", 1L);
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
