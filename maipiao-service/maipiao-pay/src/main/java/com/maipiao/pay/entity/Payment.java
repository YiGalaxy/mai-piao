package com.maipiao.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_pay.t_pay_payment}.
 *
 * <pre>
 *   status: 0 pending, 1 success, 2 failed, 3 closed
 * </pre>
 *
 * <p>The success transition is
 * {@code UPDATE ... WHERE status IN (0,2) AND amount = ?} and the set of states
 * it accepts is the whole out-of-order defence:
 *
 * <ul>
 *   <li>0 pending - the normal first-time case.</li>
 *   <li>2 failed - a failure callback can arrive before the success one, and
 *       the later success must still be allowed to win.</li>
 *   <li>1 success - never matches, so a repeat falls into the idempotent-hit
 *       branch instead of overwriting the trade number.</li>
 *   <li>3 closed - never matches, which is how a payment that arrives after
 *       the order was cancelled is detected rather than silently accepted.</li>
 * </ul>
 */
@Data
@TableName("t_pay_payment")
public class Payment {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_CLOSED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String paymentNo;

    private String orderNo;

    private Long userId;

    /** MOCK / ALIPAY. */
    private String channel;

    private BigDecimal amount;

    private Integer status;

    /** The provider's own trade number; unique per channel. */
    private String channelTradeNo;

    /** After this the payment is closed and the hold is released. */
    private LocalDateTime expireTime;

    private LocalDateTime payTime;

    private LocalDateTime notifyTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
