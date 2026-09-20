package com.maipiao.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_pay.t_pay_refund}.
 *
 * <pre>
 *   status: 0 pending, 1 refunding, 2 success, 3 failed
 * </pre>
 *
 * <p>{@code releaseSeat} is the field that decides whether the seats go back
 * into the pool, and it is not always yes:
 *
 * <ul>
 *   <li>1 for an ordinary refund - the seats are free again and can be sold.</li>
 *   <li>0 for a payment that arrived after the order was already cancelled.
 *       Those seats were released when the order timed out; releasing them a
 *       second time would decrement a counter that no longer describes
 *       anything, and the seat map would start claiming seats nobody holds.</li>
 * </ul>
 *
 * <p>{@code uk_payment_no} is layer L4 of the idempotency stack: a repeated
 * refund request collides and the caller returns the existing refund rather
 * than issuing a second one.
 */
@Data
@TableName("t_pay_refund")
public class Refund {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_REFUNDING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    /** Reasons that change how the caller should behave afterwards. */
    public static final String REASON_USER_APPLY = "USER_APPLY";
    public static final String REASON_TIME_OUT_PAID = "TIME_OUT_PAID";
    public static final String REASON_SCHEDULE_CANCELLED = "SCHEDULE_CANCELLED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String refundNo;

    private String paymentNo;

    private String orderNo;

    private Long userId;

    private String channel;

    private BigDecimal refundAmount;

    private String reason;

    private Integer status;

    /** 1 = return the seats to the pool, 0 = they were already released. */
    private Integer releaseSeat;

    private String channelRefundNo;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime refundTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
