package com.maoyan.common.core.result;

/**
 * Business error codes.
 *
 * <p>Code ranges - keep new codes inside their range so that a log line is
 * enough to tell which domain failed:
 * <pre>
 *   0         success
 *   400-599   transport / framework level
 *   10000+    seat and schedule
 *   20000+    order
 *   30000+    payment
 *   40000+    user and coupon
 *   50000+    queue / rush sale
 * </pre>
 */
public enum ErrorCode {

    SUCCESS(0, "success"),

    // ---------------- framework ----------------
    PARAM_ERROR(400, "invalid parameter"),
    UNAUTHORIZED(401, "not logged in or token expired"),
    FORBIDDEN(403, "no permission"),
    NOT_FOUND(404, "resource not found"),
    METHOD_NOT_ALLOWED(405, "method not allowed"),
    TOO_MANY_REQUESTS(429, "too many requests, please retry later"),
    SYSTEM_ERROR(500, "system error, please retry later"),
    SERVICE_UNAVAILABLE(503, "service temporarily unavailable"),

    // ---------------- seat / schedule (10xxx) ----------------
    SEAT_OCCUPIED(10001, "seat already taken"),
    SEAT_LOCK_EXPIRED(10002, "seat lock expired, please pick again"),
    SEAT_LOCK_NOT_FOUND(10003, "seat lock not found"),
    SEAT_INDEX_INVALID(10004, "invalid seat"),
    SCHEDULE_NOT_FOUND(10005, "schedule not found"),
    SCHEDULE_NOT_ON_SALE(10006, "schedule is not on sale"),
    SCHEDULE_SOLD_OUT(10007, "sold out"),
    SCHEDULE_STOCK_NOT_ENOUGH(10008, "not enough seats left"),
    SEAT_MAP_UNAVAILABLE(10009, "seat service unavailable, please retry later"),

    // ---------------- order (20xxx) ----------------
    ORDER_NOT_FOUND(20001, "order not found"),
    ORDER_STATUS_ILLEGAL(20002, "order status does not allow this operation"),
    ORDER_EXPIRED(20003, "order expired and was cancelled"),
    ORDER_DUPLICATE(20004, "duplicate submission"),
    ORDER_AMOUNT_MISMATCH(20005, "order amount mismatch"),
    REFUND_NOT_ALLOWED(20006, "refund is not allowed for this order"),
    REFUND_ALREADY_APPLIED(20007, "refund already applied"),

    // ---------------- payment (30xxx) ----------------
    PAYMENT_NOT_FOUND(30001, "payment not found"),
    PAYMENT_STATUS_ILLEGAL(30002, "payment status does not allow this operation"),
    PAYMENT_AMOUNT_MISMATCH(30003, "payment amount mismatch"),
    PAYMENT_CHANNEL_NOT_SUPPORTED(30004, "payment channel not supported"),
    PAYMENT_SIGN_INVALID(30005, "invalid signature"),
    PAYMENT_LATE_ARRIVAL(30006, "payment arrived after the order was closed, refunding"),

    // ---------------- user / coupon (40xxx) ----------------
    USER_NOT_FOUND(40001, "user not found"),
    USER_PHONE_EXISTS(40002, "phone number already registered"),
    USER_PASSWORD_WRONG(40003, "wrong phone number or password"),
    USER_DISABLED(40004, "account disabled"),
    COUPON_NOT_FOUND(40005, "coupon not found"),
    COUPON_NOT_AVAILABLE(40006, "coupon is not usable for this order"),

    // ---------------- queue / rush sale (50xxx) ----------------
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
