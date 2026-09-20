package com.maipiao.order.job;

import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.mapper.OrderMapper;
import com.maipiao.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>在更大的部署里，延迟消息本该是主触发器，这次清扫只是兜底。
 * 这里清扫是唯一的触发器，这样做更简单、少一个活动部件 ——
 * 一条延迟消息没能送达时，座位反正也要卡到这次清扫跑起来，所以两条路都还是需要它。
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
