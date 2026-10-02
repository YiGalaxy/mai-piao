package com.maipiao.order.mq;

import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.mapper.OrderMapper;
import com.maipiao.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 收到延时消息，如果订单还没付款就取消它。
 *
 * <h3>为什么扫表没有被删掉</h3>
 *
 * <p>消息投递在这个系统里不是「保证送达」的。生产者那侧，broker 抖动会让消息根本
 * 发不出去（那条路径只记日志）；broker 这侧，消息落盘后如果磁盘先坏了，
 * 它也一起没了。任何一条消息都不该是「这个座位能不能被放出来」的唯一依据。
 *
 * <p>所以两条路都在：消息负责<b>准时</b>，扫表负责<b>一定</b>。消息没到，
 * 座位最多多占一个扫描周期；扫表挂了，取消只是晚几十秒。这是一对冗余，
 * 而不是一份主代码加一份可以删掉的备份 —— 删掉任何一边，留下的那一边都会
 * 独自承担它本来不打算承担的失败模式。
 *
 * <h3>为什么这里没有分布式锁</h3>
 *
 * <p>同一条消息可能被投递两次（RocketMQ 在消费者超时或重启时会重投），
 * 但不需要在这里加锁去重：{@link OrderService#cancel} 是一次状态机的
 * 比较并交换，第二次执行时订单已经不是 PENDING_PAY 了，会直接返回 false。
 * 幂等由状态机提供，而状态机是唯一说真话的地方。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${maipiao.order.timeout-topic:maipiao-order-timeout}",
        consumerGroup = "${maipiao.order.timeout-consumer-group:maipiao-order-timeout-consumer}")
public class OrderTimeoutConsumer implements RocketMQListener<String> {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /**
     * 消息的载荷只有订单号，其余一切现查。
     *
     * <p>不把截止时间、座位数这些东西一起塞进消息，是因为它们在 15 分钟里都可能变 ——
     * 订单可能已经被付掉、被用户自己取消、被别的路径释放过座位。消息里带的每个字段
     * 都是一个会过期的副本，而消费者真正该读的是数据库里那一行现在的样子。
     * 载荷越薄，能过期的东西越少。
     */
    @Override
    public void onMessage(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            // 消息比订单先到，或者订单被物理删了。两种情况都无事可做。
            log.warn("timeout message for an unknown order: orderNo={}", orderNo);
            return;
        }

        // 和扫表用的是同一个条件（见 OrderMapper.selectExpired）：待支付、且已过期。
        // 复用它而不是另外写一套判断，是因为两处判断一旦分叉，就会出现
        // 「消息认为该取消、扫表认为不该」这种要靠时序才能复现的怪事。
        if (!OrderStatus.CANCELLABLE.contains(order.getStatus())) {
            // 绝大多数情况下走的是这条：用户已经付了，或者自己取消了。
            // 这不是异常，是延时消息的正常归宿 —— 它只是来问一句，问完就走。
            log.debug("timeout message arrived for an order that is no longer payable: "
                    + "orderNo={}, status={}", orderNo, order.getStatus());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (order.getLockExpireTime() != null && order.getLockExpireTime().isAfter(now)) {
            // 消息早到了。生产侧留了一秒余量，所以这一般意味着两边的时钟有偏差。
            // 不在这里自己补一次延时，也不硬取消 —— 硬取消会砍掉用户仍然有效的支付时间，
            // 而这个订单本来就还有一个扫表兜着。
            log.warn("timeout message arrived before the order expired, leaving it to the sweep: "
                    + "orderNo={}, expireTime={}", orderNo, order.getLockExpireTime());
            return;
        }

        // byUser = false：这是系统在动作，所以不做归属校验，
        // 消失的座位也不会因此被算成用户的错。
        orderService.cancel(orderNo, order.getUserId(), false);
        log.info("order cancelled by timeout message: orderNo={}", orderNo);
    }
}
