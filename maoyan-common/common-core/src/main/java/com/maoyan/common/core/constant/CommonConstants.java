package com.maoyan.common.core.constant;

/**
 * Constants shared across services.
 *
 * <p>Anything that two services must agree on lives here. If a value only
 * matters inside one service, keep it there instead.
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    // ------------------------------------------------------------
    // HTTP headers
    // ------------------------------------------------------------

    /** Set by the gateway after JWT verification; downstream services trust it. */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** Set by the gateway; the raw JWT, forwarded for services that re-verify. */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** Internal-only marker. The gateway strips it from inbound client requests. */
    public static final String HEADER_INTERNAL_CALL = "X-Internal-Call";

    /** Request id, used to correlate logs across services. */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    public static final String TOKEN_PREFIX = "Bearer ";

    // ------------------------------------------------------------
    // Redis key prefixes
    //
    // Every key is namespaced by domain so that a single Redis instance can
    // hold seat state, queue state and caches without collisions.
    // ------------------------------------------------------------

    /** seat:map:{scheduleId} - bitmap, bit N = seat_index N is taken. */
    public static final String SEAT_MAP_KEY = "seat:map:";

    /** seat:owner:{scheduleId} - hash, field = seat_index, value = orderNo or SOLD:orderNo. */
    public static final String SEAT_OWNER_KEY = "seat:owner:";

    /** seat:delay:{scheduleId} - zset, member = orderNo, score = lock expiry millis. */
    public static final String SEAT_DELAY_KEY = "seat:delay:";

    /** seat:order:{orderNo} - set of seat indexes held by one order. */
    public static final String SEAT_ORDER_KEY = "seat:order:";

    /** seat:stock:{scheduleId} - prefetched remaining seat count. */
    public static final String SEAT_STOCK_KEY = "seat:stock:";

    /** queue:wait:{scheduleId} - zset, member = userId, score = enqueue millis. */
    public static final String QUEUE_WAIT_KEY = "queue:wait:";

    /** queue:inflight:{scheduleId} - zset, member = userId, score = token expiry millis. */
    public static final String QUEUE_INFLIGHT_KEY = "queue:inflight:";

    /** queue:token:{scheduleId}:{userId} - admission token issued by the dispatcher. */
    public static final String QUEUE_TOKEN_KEY = "queue:token:";

    /** sold_out:{scheduleId} - presence means the schedule is sold out. */
    public static final String SOLD_OUT_KEY = "sold_out:";

    /** rush:paused:{scheduleId} - emergency pause switch for a rush sale. */
    public static final String RUSH_PAUSED_KEY = "rush:paused:";

    /** user:token:blacklist:{jti} - logged-out tokens that must be rejected until they expire. */
    public static final String TOKEN_BLACKLIST_KEY = "user:token:blacklist:";

    // ------------------------------------------------------------
    // Business defaults
    // ------------------------------------------------------------

    /** How long a seat stays locked while the user is on the payment page. */
    public static final int SEAT_LOCK_MINUTES = 15;

    /** How long a queue admission token stays valid once issued. */
    public static final int QUEUE_TOKEN_SECONDS = 300;

    /** How long before the screening a refund is still allowed. */
    public static final int REFUND_DEADLINE_HOURS = 2;

    /** Dispatcher wake-up interval, milliseconds. */
    public static final long QUEUE_DISPATCH_INTERVAL_MS = 200L;

    /**
     * Admission overshoot factor. Fewer people actually order than were let
     * through (some give up, some stall), so we admit slightly more than the
     * remaining stock. 1.0 risks unsold seats; 10.0 means thousands of people
     * queue for nothing.
     */
    public static final double QUEUE_ADMIT_FACTOR = 1.5d;

    /** Batch size cap for one dispatcher round. */
    public static final int QUEUE_ADMIT_MAX_BATCH = 500;

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
}
