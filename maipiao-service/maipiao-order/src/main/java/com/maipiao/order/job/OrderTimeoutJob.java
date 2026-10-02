package com.maipiao.order.job;

import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.mapper.OrderMapper;
import com.maipiao.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 取消那些占用已经失效的未支付订单。
 *
 * <p>没有它，一笔始终没付款的订单会永远躺在 PENDING_PAY，座位也一直被占着：
 * 客户对着几个小时前就该过期的订单还能看见「去支付」按钮，而那些座位也卖不给别人。
 *
 * <p>每笔过期订单要做两件事，而这两件事的先后顺序是有讲究的。
 * 状态先变成 CANCELLED —— 那才是权威事实 —— 然后才释放座位。
 * 如果释放失败，订单仍然已经正确地取消了，对账任务之后可以把座位放掉；
 * 反过来做，则会在订单还自称可支付的时候，短暂地把座位显示成可选。
 *
 * <h3>它现在是兜底，不再是主路径</h3>
 *
 * <p>准时的那条路径是 {@code OrderTimeoutProducer} 投递的延时消息：订单到期那一刻
 * 就送达，所以占用时长正好是配置里写的 15 分钟。这个任务每 60 秒才醒一次，
 * 走它的话实际会占到 15 分 59 秒。
 *
 * <p>但兜底不等于「可以删」。消息会因为没有 broker、因为生产者在崩溃窗口里没发出去、
 * 因为磁盘故障而消失，而「这个座位能不能放出来」不该押在一条消息上。
 * 两条路各自覆盖对方的失败模式，缺一条，另一条就要独自承担它本来没打算承担的事。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 小批量，免得一次积压把清扫变成全表扫描。 */
    @Value("${maipiao.order.timeout-batch:200}")
    private int batchSize;

    // lockAtMostFor 比任务本身可能花的时间宽裕得多：一轮最多 200 笔，
    // 每笔几次数据库往返，正常是秒级。持锁实例要是直接死了，锁会在 5 分钟后自动放开，
    // 而不是把后面所有实例一起挡住 —— 一个死掉的实例不该让这个任务永远不再运行。
    //
    // lockAtLeastFor 是下限，不是「略短于间隔」：任务跑完后锁不立即删除，
    // 而是留到至少 10 秒之后再放开（实现上是把 TTL 改成剩余时间，而不是 DELETE）。
    // 静止状态下它不起作用 —— 下一轮本来就是 60 秒之后。它真正管的是
    // 间隔被调得很短的情况：那时没有它，锁会在实例之间被抢来抢去，
    // 每一轮都换一个实例执行，日志会碎得没法读。
    @SchedulerLock(name = "order-timeout-sweep",
            lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Scheduled(fixedDelayString = "${maipiao.order.timeout-interval-ms:60000}")
    public void cancelExpiredOrders() {
        List<Order> expired = orderMapper.selectExpired(LocalDateTime.now(), batchSize);
        if (expired.isEmpty()) {
            return;
        }

        int cancelled = 0;
        for (Order order : expired) {
            try {
                // byUser = false：这是系统在动作，随之而来的座位释放是尽力而为。
                if (orderService.cancel(order.getOrderNo(), order.getUserId(), false)) {
                    cancelled++;
                }
            } catch (Exception e) {
                // 一笔坏订单不能打断整趟清扫 —— 积压里剩下的那些一样过期了。
                log.error("could not cancel expired order: orderNo={}", order.getOrderNo(), e);
            }
        }

        if (cancelled > 0) {
            log.info("expired orders cancelled: {} of {} scanned", cancelled, expired.size());
        }
    }

    /**
     * 场次结束后，把已支付的订单置为已完成。
     *
     * <p>置为完成就是关上退款窗口的那个动作，所以它由场次的结束时间来驱动，
     * 而不是单靠一个定时器 —— 订单不能在它的场次还在放映时就变得不可退。
     */
    @SchedulerLock(name = "order-complete-sweep",
            lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Scheduled(fixedDelayString = "${maipiao.order.complete-interval-ms:300000}")
    public void completeFinishedOrders() {
        List<Order> finished = orderMapper.selectCompletable(
                LocalDateTime.now().minusMinutes(30), batchSize);
        if (finished.isEmpty()) {
            return;
        }

        int completed = 0;
        for (Order order : finished) {
            try {
                if (orderService.complete(order.getOrderNo())) {
                    completed++;
                }
            } catch (Exception e) {
                log.error("could not complete order: orderNo={}", order.getOrderNo(), e);
            }
        }

        if (completed > 0) {
            log.info("orders completed: {}", completed);
        }
    }
}
