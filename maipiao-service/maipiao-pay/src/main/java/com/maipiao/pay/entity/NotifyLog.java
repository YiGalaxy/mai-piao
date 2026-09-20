package com.maipiao.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_pay.t_pay_notify_log} —— 幂等体系里的 L1 层。
 *
 * <p>L1 做什么、不做什么，很容易搞错。它去重的是<em>日志</em>，不是<em>业务效果</em>：
 *
 * <pre>
 *   INSERT ... ON DUPLICATE KEY UPDATE retry_times = retry_times + 1
 * </pre>
 *
 * <p>随后会检查返回的那一行：
 *
 * <ul>
 *   <li>{@code process_status = 1} —— 已经处理过。可以放心地回给渠道方一个成功。</li>
 *   <li>{@code process_status = 0} —— 第一次尝试没跑完，或者还在跑。必须处理。</li>
 *   <li>{@code process_status = 2} —— 上一次尝试失败了。同样要再处理一遍。</li>
 * </ul>
 *
 * <p>仅仅因为插入撞了唯一键就返回成功是错的：如果第一次尝试插入了这一行、随后进程就死了，
 * 这笔支付就会被记成「见过」却从未被处理，钱就搁在那儿了。业务上的幂等必须由 L2
 * 来提供，也就是支付单上的状态 CAS。
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

    /** PAY / REFUND。唯一键的一部分，好让这两种回调不会互相撞上。 */
    private String notifyType;

    /** 原始报文，留着重放和事后追查用。 */
    private String rawBody;

    private Integer signVerified;

    private Integer processStatus;

    private String processResult;

    private Integer retryTimes;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
