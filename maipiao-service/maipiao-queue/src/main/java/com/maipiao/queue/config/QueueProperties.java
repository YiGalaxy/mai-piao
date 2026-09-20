package com.maipiao.queue.config;

import com.maipiao.common.core.constant.CommonConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 准入相关的可调参数。
 *
 * <p>默认值取自 {@link CommonConstants} 而不是在这里再写一遍，这样设计里讨论的那些数字
 * 和服务实际跑的那个数字不会各走各的。
 */
@Data
@Component
@ConfigurationProperties(prefix = "maipiao.queue")
public class QueueProperties {

    /**
     * 每剩余一个座位放多少人进来。
     *
     * <p>不是 1.0，因为被放进来的人并不是个个都会下单 —— 有人犹豫，有人关掉标签页，
     * 有人发现想要的座位没了就走了。严格按座位数放人，最后几个座位就卖不掉。也不是
     * 10.0，那意味着几千人排着队，等的是一张从来就不存在的票。
     */
    private double admitFactor = CommonConstants.QUEUE_ADMIT_FACTOR;

    /** 准入令牌的有效期。超过之后，这个位置就算放弃了。 */
    private int tokenSeconds = CommonConstants.QUEUE_TOKEN_SECONDS;

    /** 调度器多久醒一次。 */
    private long dispatchIntervalMs = CommonConstants.QUEUE_DISPATCH_INTERVAL_MS;

    /** 单轮准入的上限，免得一场大销售一瞬间全涌进来。 */
    private int maxBatch = CommonConstants.QUEUE_ADMIT_MAX_BATCH;

    /** 一条队列在空无一人的情况下还能存活多久，超时就把注册表里的条目丢掉。 */
    private int scheduleTtlSeconds = 6 * 3600;

    /** 拉取到的场次元数据可以被信任多久。 */
    private int sessionCacheSeconds = 60;
}
