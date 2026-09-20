package com.maipiao.common.core.result;

/**
 * 业务错误码。
 *
 * <p>码段划分 —— 新加的码要留在自己那一段里，这样看一眼日志就能知道是哪个领域
 * 出的错：
 * <pre>
 *   0         成功
 *   400-599   传输 / 框架层
 *   10000+    座位与场次
 *   20000+    订单
 *   30000+    支付
 *   40000+    用户与优惠券
 *   50000+    排队 / 抢购
 * </pre>
 */
public enum ErrorCode {

    SUCCESS(0, "success"),

    // ---------------- 框架层 ----------------
    PARAM_ERROR(400, "invalid parameter"),
    UNAUTHORIZED(401, "not logged in or token expired"),
    FORBIDDEN(403, "no permission"),
    NOT_FOUND(404, "resource not found"),
    METHOD_NOT_ALLOWED(405, "method not allowed"),
    TOO_MANY_REQUESTS(429, "too many requests, please retry later"),
    SYSTEM_ERROR(500, "system error, please retry later"),
    SERVICE_UNAVAILABLE(503, "service temporarily unavailable"),

    // ---------------- 座位 / 场次 (10xxx) ----------------
    SEAT_OCCUPIED(10001, "seat already taken"),
    SEAT_LOCK_EXPIRED(10002, "seat lock expired, please pick again"),
    SEAT_LOCK_NOT_FOUND(10003, "seat lock not found"),
    SEAT_INDEX_INVALID(10004, "invalid seat"),
    SCHEDULE_NOT_FOUND(10005, "schedule not found"),
    SCHEDULE_NOT_ON_SALE(10006, "schedule is not on sale"),
    SCHEDULE_SOLD_OUT(10007, "sold out"),
    SCHEDULE_STOCK_NOT_ENOUGH(10008, "not enough seats left"),
    SEAT_MAP_UNAVAILABLE(10009, "seat service unavailable, please retry later"),
    /**
     * 该票档里找不到一段满足所需长度的连座。
     *
     * <p>和 {@link #SEAT_OCCUPIED} 分开，是因为没有座位被拿走，也没有任何东西失败：
     * 只是这个票档坐不下这么多连在一起的人。客户端对此的应对是提示可以拆开坐，
     * 这个选择该由买家来做，而不是服务端替他们做。
     */
    SEAT_NOT_ADJACENT(10010, "no adjacent seats available in this price tier"),

    // ---------------- 订单 (20xxx) ----------------
    ORDER_NOT_FOUND(20001, "order not found"),
    ORDER_STATUS_ILLEGAL(20002, "order status does not allow this operation"),
    ORDER_EXPIRED(20003, "order expired and was cancelled"),
    ORDER_DUPLICATE(20004, "duplicate submission"),
    ORDER_AMOUNT_MISMATCH(20005, "order amount mismatch"),
    REFUND_NOT_ALLOWED(20006, "refund is not allowed for this order"),
    REFUND_ALREADY_APPLIED(20007, "refund already applied"),

    // ---------------- 支付 (30xxx) ----------------
    PAYMENT_NOT_FOUND(30001, "payment not found"),
    PAYMENT_STATUS_ILLEGAL(30002, "payment status does not allow this operation"),
    PAYMENT_AMOUNT_MISMATCH(30003, "payment amount mismatch"),
    PAYMENT_CHANNEL_NOT_SUPPORTED(30004, "payment channel not supported"),
    PAYMENT_SIGN_INVALID(30005, "invalid signature"),
    PAYMENT_LATE_ARRIVAL(30006, "payment arrived after the order was closed, refunding"),

    // ---------------- 用户 / 优惠券 (40xxx) ----------------
    USER_NOT_FOUND(40001, "user not found"),
    USER_PHONE_EXISTS(40002, "phone number already registered"),
    USER_PASSWORD_WRONG(40003, "wrong phone number or password"),
    USER_DISABLED(40004, "account disabled"),
    COUPON_NOT_FOUND(40005, "coupon not found"),
    COUPON_NOT_AVAILABLE(40006, "coupon is not usable for this order"),

    // ---------------- 排队 / 抢购 (50xxx) ----------------
    QUEUE_SOLD_OUT(50001, "sold out"),
    QUEUE_PAUSED(50002, "rush sale is paused"),
    QUEUE_TOKEN_INVALID(50003, "queue ticket invalid or expired"),
    QUEUE_NOT_JOINED(50004, "you are not in the queue"),
    QUEUE_NOT_STARTED(50005, "rush sale has not started yet"),
    ;

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
