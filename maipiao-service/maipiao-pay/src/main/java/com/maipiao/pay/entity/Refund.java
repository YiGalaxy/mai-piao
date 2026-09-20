package com.maipiao.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_pay.t_pay_refund}。
 *
 * <pre>
 *   status: 0 待处理, 1 退款中, 2 成功, 3 失败
 * </pre>
 *
 * <p>{@code releaseSeat} 决定了座位要不要回到池子里，而答案并不总是「要」：
 *
 * <ul>
 *   <li>普通退款是 1 —— 座位重新空出来，可以再卖。</li>
 *   <li>订单早已取消、钱才到账的那种退款是 0。那些座位在订单超时的时候就已经释放过了；
 *       再释放一次会让一个早就不再描述任何东西的计数往下减，座位图会开始声称
 *       一些根本没人占的座位被占着。</li>
 * </ul>
 *
 * <p>{@code uk_payment_no} 是幂等体系里的 L4 层：重复的退款请求会撞上唯一键，
 * 调用方于是返回已有的那笔退款，而不是又发一笔。
 */
@Data
@TableName("t_pay_refund")
public class Refund {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_REFUNDING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    /** 这些原因会改变调用方后续的行为方式。 */
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
