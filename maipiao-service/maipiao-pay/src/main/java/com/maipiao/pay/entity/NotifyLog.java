package com.maipiao.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_pay.t_pay_notify_log} - layer L1 of the idempotency
 * stack.
 *
 * <p>What L1 does and does not do is easy to get wrong. It dedups the
 * <em>log</em>, not the <em>business effect</em>:
 *
 * <pre>
 *   INSERT ... ON DUPLICATE KEY UPDATE retry_times = retry_times + 1
 * </pre>
 *
 * <p>The row that comes back is then inspected:
 *
 * <ul>
 *   <li>{@code process_status = 1} - already handled. Safe to answer the
 *       provider with success.</li>
 *   <li>{@code process_status = 0} - a first attempt that never finished, or
 *       is still running. It must be processed.</li>
 *   <li>{@code process_status = 2} - the last attempt failed. Also processed
 *       again.</li>
 * </ul>
 *
 * <p>Returning success merely because the insert collided would be wrong: if
 * the first attempt inserted the row and then the process died, the payment
 * would be recorded as seen and never handled, and the money would sit there.
 * Business idempotency has to come from L2, the state CAS on the payment.
 */
@Data
@TableName("t_pay_notify_log")
public class NotifyLog {

    public static final int STATUS_NOT_PROCESSED = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String channel;

    private String channelTradeNo;

    /** PAY / REFUND. Part of the unique key, so the two do not collide. */
    private String notifyType;

    /** Raw payload, kept for replay and forensics. */
    private String rawBody;

    private Integer signVerified;

    private Integer processStatus;

    private String processResult;

    private Integer retryTimes;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
