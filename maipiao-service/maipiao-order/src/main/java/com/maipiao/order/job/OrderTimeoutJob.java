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
 * Cancels unpaid orders whose hold has lapsed.
 *
 * <p>Without this, an order that is never paid sits in PENDING_PAY forever and
 * its seats stay held: the customer sees a "to pay" button for an order that
 * expired hours ago, and the seats cannot be sold to anybody else.
 *
 * <p>Two things happen per expired order, and the order between them matters.
 * The status moves to CANCELLED first - that is the authoritative fact - and
 * only then are the seats released. If the release fails, the order is still
 * correctly cancelled and the reconciliation job can free the seats later;
 * doing it the other way round would briefly show seats as available for an
 * order that still claims to be payable.
 *
 * <p>The delay message would be the primary trigger in a larger deployment,
 * with this sweep as the fallback. Here the sweep is the only trigger, which
 * is simpler and has one fewer moving part - a delayed message that fails to
 * arrive leaves seats held until this runs anyway, so both paths still need it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** Small batches, so one backlog cannot turn a sweep into a table scan. */
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
                // byUser = false: this is the system acting, and the seat
                // release that follows is best-effort.
                if (orderService.cancel(order.getOrderNo(), order.getUserId(), false)) {
                    cancelled++;
                }
            } catch (Exception e) {
                // One bad order must not stop the sweep - the rest of the
                // backlog is just as expired.
                log.error("could not cancel expired order: orderNo={}", order.getOrderNo(), e);
            }
        }

        if (cancelled > 0) {
            log.info("expired orders cancelled: {} of {} scanned", cancelled, expired.size());
        }
    }

    /**
     * Marks paid orders complete once their session has finished.
     *
     * <p>Completion is what closes the refund window, so it is driven by the
     * session's end time rather than by a timer alone - an order cannot become
     * non-refundable while its screening is still running.
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
