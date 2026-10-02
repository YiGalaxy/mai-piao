package com.maipiao.order.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 下单成功之后，投一条到点触发的延时消息。
 *
 * <p>它替代的是「定时扫表」成为超时取消的<b>主</b>触发器。差别不在能不能取消 ——
 * 两条路都能 —— 而在延迟：扫表每 60 秒才醒一次，所以一笔 15 分钟的占用实际会占到
 * 15 分 59 秒；而这条消息在到期那一刻就送达，多占的那几十秒没了。
 * 用户也会更早看到「已取消」，而不是对着一个按了没反应的支付按钮。
 *
 * <p>扫表没有删掉，它降级成兜底。这是有意的，理由在
 * {@link OrderTimeoutConsumer} 的类注释里。
 *
 * <h3>为什么发送点在事务之外</h3>
 *
 * <p>调用方是 {@code OrderController}，在 {@code OrderService.create} <b>返回之后</b>
 * 才调这里，而不是在 {@code create} 里面发。
 *
 * <p>直觉上「发消息」应该和下单在同一个事务里，这样两者要么都成功、要么都失败。
 * 但 Seata AT 模式下做不到：分支事务是<b>各自本地提交</b>完再去问 TC 要不要全局提交的。
 * 也就是说 Spring 的 {@code afterCommit} 回调会在「本地已提交、全局还没定」的时刻触发 ——
 * 此时发出去的消息，对应的订单仍可能被回滚。消费者拿到一个订单号，去查却查不到，
 * 或者更糟，查到一个还没写完的半成品。
 *
 * <p>放在 {@code create} 返回之后，全局事务已经定局，消息指向的订单一定存在。
 * 代价是这中间进程崩掉的话消息没发出去 —— 但这正是扫表还在的原因，
 * 而两件事同时失败才会漏掉一笔，那要的是一次很难凑齐的巧合。
 *
 * <h3>为什么不用 RocketMQ 的事务消息</h3>
 *
 * <p>事务消息解决的是「发送与本地事务原子」，做法是先发半消息、再回查本地事务状态。
 * 而这里的「本地事务」是一个横跨三个服务的 Seata 全局事务，回查逻辑最终只能问
 * 「t_order_order 里有没有这一行」—— 和扫表兜底问的是同一个问题。
 * 多一套半消息加回查的机制，换来的是同一个结果，却多了一个需要单独理解和排障的状态。
 * 否决它是因为它不解决新问题，不是因为它不好。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutProducer {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${maipiao.order.timeout-topic:maipiao-order-timeout}")
    private String topic;

    /**
     * 比订单的截止时刻晚这么多再投递。
     *
     * <p>不是因为 broker 不准。broker 的时间轮走的是绝对时间戳，精度足够；
     * 但业务侧的 {@code lock_expire_time} 是拿应用进程的 {@code LocalDateTime.now()}
     * 算出来的，而投递时刻是拿同一个进程的时钟换成的 epoch 毫秒 —— 真正会对不齐的，
     * 是 broker 所在容器和这个进程之间的时钟。留一秒，让消费者的时间判断几乎不会
     * 落在「消息到了但订单还没到期」那一侧。
     *
     * <p>真的落在了那一侧也不会出错 —— 消费者会拒绝并把它留给扫表 —— 只是白跑一趟。
     */
    private static final long SAFETY_MARGIN_MS = 1000L;

    /**
     * 安排一次超时取消。
     *
     * <p>失败只记日志，绝不往上抛。这条消息是一层优化，不是保证：下单已经成功、
     * 钱的事已经定了，让调用方因为一条没能投出去的消息而看到下单失败，是把
     * 一个次要问题升级成了一个假象。座位最坏也就是多占一会儿，扫表会收掉它。
     */
    public void scheduleCancel(String orderNo, LocalDateTime expireTime) {
        long deliverAt = expireTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                + SAFETY_MARGIN_MS;

        // orderNo 同时作为消息的 KEYS。RocketMQ 控制台按 key 查消息，而排查
        // 「这单为什么被取消了」时，手上有的就是这个单号。
        Message<String> message = MessageBuilder.withPayload(orderNo)
                .setHeader(RocketMQHeaders.KEYS, orderNo)
                .build();

        try {
            // 不去用那个 delayLevel 的重载：它的 18 个档位里没有 15 分钟
            // （只有 10 分钟和 20 分钟），取任何一个都会让实际占用时长对不上
            // 配置里写的 lock-minutes。这里用的是 5.x broker 的定时消息能力，
            // 传一个绝对时刻，精度由我们决定而不是由档位表决定。
            SendResult result = rocketMQTemplate.syncSendDeliverTimeMills(topic, message, deliverAt);
            log.info("order timeout message scheduled: orderNo={}, deliverAt={}, msgId={}",
                    orderNo, Instant.ofEpochMilli(deliverAt), result.getMsgId());
        } catch (Exception e) {
            log.error("could not schedule timeout message for order {}, "
                    + "it will be cancelled by the scheduled sweep instead: {}",
                    orderNo, e.getMessage());
        }
    }
}
