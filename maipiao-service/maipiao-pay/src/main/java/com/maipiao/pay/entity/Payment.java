package com.maipiao.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_pay.t_pay_payment}。
 *
 * <pre>
 *   status: 0 待支付, 1 成功, 2 失败, 3 已关闭
 * </pre>
 *
 * <p>转成功的那条语句是
 * {@code UPDATE ... WHERE status IN (0,2) AND amount = ?}，而它接受哪些状态，
 * 就是整个防乱序的全部：
 *
 * <ul>
 *   <li>0 待支付 —— 正常的首次支付。</li>
 *   <li>2 失败 —— 失败回调有可能比成功回调先到，后到的成功仍然必须允许它赢。</li>
 *   <li>1 成功 —— 永远不匹配，于是一次重复会落进「幂等命中」的分支，
 *       而不会去覆写交易号。</li>
 *   <li>3 已关闭 —— 永远不匹配，订单取消之后才到的支付就是靠这一点被识别出来，
 *       而不是被默默接受。</li>
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

    /** MOCK / ALIPAY。 */
    private String channel;

    private BigDecimal amount;

    private Integer status;

    /** 渠道方自己的交易号；每家渠道内唯一。 */
    private String channelTradeNo;

    /** 过了这个时间点，支付单被关闭，座位占用也随之释放。 */
    private LocalDateTime expireTime;

    private LocalDateTime payTime;

    private LocalDateTime notifyTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
