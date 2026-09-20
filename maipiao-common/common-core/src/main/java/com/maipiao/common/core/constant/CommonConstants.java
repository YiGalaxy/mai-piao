package com.maipiao.common.core.constant;

/**
 * 各服务共用的常量。
 *
 * <p>凡是需要两个服务达成一致的值都放这里。如果一个值只在某个服务内部有意义，
 * 就留在那个服务里。
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    // ------------------------------------------------------------
    // HTTP 请求头
    // ------------------------------------------------------------

    /** 由网关在 JWT 校验通过后设置；下游服务信任它。 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 由网关设置；原始 JWT，转发给那些要自己再验一遍的服务。 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** 仅供内部使用的标记。网关会把它从客户端进来的请求上剥掉。 */
    public static final String HEADER_INTERNAL_CALL = "X-Internal-Call";

    /** 请求 id，用来跨服务串联日志。 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    public static final String TOKEN_PREFIX = "Bearer ";

    // ------------------------------------------------------------
    // Redis key 前缀
    //
    // 每个 key 都按领域做命名空间，这样一台 Redis 实例可以同时存放座位状态、
    // 排队状态和各种缓存而不互相撞车。
    // ------------------------------------------------------------

    /** seat:map:{scheduleId} —— bitmap，第 N 位为 1 表示 seat_index 为 N 的座位已被占。 */
    public static final String SEAT_MAP_KEY = "seat:map:";

    /** seat:owner:{scheduleId} —— hash，field = seat_index，value = orderNo 或 SOLD:orderNo。 */
    public static final String SEAT_OWNER_KEY = "seat:owner:";

    /** seat:delay:{scheduleId} —— zset，member = orderNo，score = 锁的过期毫秒时间戳。 */
    public static final String SEAT_DELAY_KEY = "seat:delay:";

    /** seat:order:{orderNo} —— set，一个订单持有的所有座位序号。 */
    public static final String SEAT_ORDER_KEY = "seat:order:";

    /** seat:stock:{scheduleId} —— 预取的剩余座位数。 */
    public static final String SEAT_STOCK_KEY = "seat:stock:";

    /** queue:wait:{scheduleId} —— zset，member = userId，score = 入队毫秒时间戳。 */
    public static final String QUEUE_WAIT_KEY = "queue:wait:";

    /** queue:inflight:{scheduleId} —— zset，member = userId，score = token 过期毫秒时间戳。 */
    public static final String QUEUE_INFLIGHT_KEY = "queue:inflight:";

    /** queue:token:{scheduleId}:{userId} —— 由叫号器签发的准入 token。 */
    public static final String QUEUE_TOKEN_KEY = "queue:token:";

    /** sold_out:{scheduleId} —— 存在即表示该场次已售罄。 */
    public static final String SOLD_OUT_KEY = "sold_out:";

    /** rush:paused:{scheduleId} —— 抢购的紧急暂停开关。 */
    public static final String RUSH_PAUSED_KEY = "rush:paused:";

    /**
     * rush:schedules —— 当前正在抢购的 scheduleId 集合。
     *
     * <p>由第一个入场的人登记，队列排空时清理掉，这样叫号器有一份现成的名单可以
     * 遍历，不必去问 movie-service 哪些场次在抢购。也免了两个服务必须就一套 key
     * 的划分达成一致。
     */
    public static final String RUSH_SCHEDULES_KEY = "rush:schedules";

    /**
     * queue:session:{scheduleId} —— 缓存的抢购元数据（总座位数、开抢时间），
     * 这样入队这条路径不必每来一个人就调一次 movie-service。
     */
    public static final String QUEUE_SESSION_KEY = "queue:session:";

    /** user:token:blacklist:{jti} —— 已登出的 token，在自然过期之前必须一律拒绝。 */
    public static final String TOKEN_BLACKLIST_KEY = "user:token:blacklist:";

    // ------------------------------------------------------------
    // 业务默认值
    // ------------------------------------------------------------

    /** 用户停留在支付页期间，座位保持锁定多久。 */
    public static final int SEAT_LOCK_MINUTES = 15;

    /** 排队准入 token 签发后有效期多久。 */
    public static final int QUEUE_TOKEN_SECONDS = 300;

    /** 距离开场还有多久之前仍然可以退票。 */
    public static final int REFUND_DEADLINE_HOURS = 2;

    /** 叫号器的唤醒间隔，单位毫秒。 */
    public static final long QUEUE_DISPATCH_INTERVAL_MS = 200L;

    /**
     * 放行量的超额系数。真正下单的人比放进来的人少（有人放弃，有人卡住），
     * 所以放行数比剩余库存略多一点。取 1.0 有卖不完的风险；取 10.0 意味着几千人
     * 白排一场队。
     */
    public static final double QUEUE_ADMIT_FACTOR = 1.5d;

    /** 叫号器单轮放行的批量上限。 */
    public static final int QUEUE_ADMIT_MAX_BATCH = 500;

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
}
