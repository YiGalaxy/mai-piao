package com.maipiao.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderItem;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.feign.MovieClient;
import com.maipiao.order.feign.SeatClient;
import com.maipiao.order.mapper.OrderItemMapper;
import com.maipiao.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Refunds, and the rules about when one is allowed.
 *
 * <p>Every guard here exists because the answer is different for a cinema
 * ticket than for a shop return, and getting it wrong costs either the venue
 * or the customer:
 *
 * <ul>
 *   <li><b>Two hours before the show.</b> A cinema seat released ten minutes
 *       before curtain is a seat nobody buys. Refusing close to the show is
 *       how every real ticketing system behaves, and it is why the window is
 *       measured to the performance rather than to the purchase.</li>
 *   <li><b>Not after entry.</b> A checked ticket is a ticket that was used.</li>
 *   <li><b>Only while paid.</b> A pending order is cancelled, not refunded;
 *       an already-refunded one is not refunded twice.</li>
 * </ul>
 *
 * <p>The refund window is a property of the ticket, not of the customer's
 * patience, so it is evaluated here rather than left to the client to police.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefundService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStateMachine stateMachine;
    private final MovieClient movieClient;
    private final SeatClient seatClient;

    /** How close to the performance a refund stops being allowed. */
    @Value("${maipiao.order.refund-deadline-hours:2}")
    private int refundDeadlineHours;

    // ------------------------------------------------------------

    /**
     * Whether this order could be refunded right now, and why not.
     *
     * <p>Separate from the request so the order page can grey the button out
     * before anybody presses it. The check and the act share this method, so
     * the button cannot promise something the server will refuse.
     */
    public RefundCheck checkRefundable(Order order) {
        if (order == null) {
            return RefundCheck.no("订单不存在");
        }

        Integer status = order.getStatus();
        if (status == null) {
            return RefundCheck.no("订单状态异常");
        }
        if (status == OrderStatus.REFUNDING || status == OrderStatus.REFUNDED) {
            return RefundCheck.no("该订单已在退款流程中");
        }
        if (status == OrderStatus.CANCELLED) {
            return RefundCheck.no("订单已取消");
        }
        if (status != OrderStatus.PAID && status != OrderStatus.COMPLETED) {
            return RefundCheck.no("订单尚未支付");
        }

        if (order.getShowTime() == null) {
            return RefundCheck.no("场次信息缺失");
        }
        LocalDateTime deadline = order.getShowTime().minusHours(refundDeadlineHours);
        if (LocalDateTime.now().isAfter(deadline)) {
            return RefundCheck.no("距开演不足 " + refundDeadlineHours + " 小时，不可退票");
        }

        List<OrderItem> items = itemsOf(order.getOrderNo());
        boolean anyChecked = items.stream()
                .anyMatch(item -> item.getCheckStatus() != null && item.getCheckStatus() == 1);
        if (anyChecked) {
            return RefundCheck.no("已验票，不可退票");
        }

        return RefundCheck.yes(order.getPayAmount());
    }

    /**
     * Moves an order into REFUNDING.
     *
     * <p>Only the state transition. Actually getting the money back is
     * pay-service's job, and it runs its own global transaction - this one
     * must not be open when that happens, or a slow provider would hold a row
     * lock on the order for as long as its HTTP call takes.
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal startRefund(String orderNo, String reason) {
        Order order = stateMachine.require(orderNo);
        RefundCheck check = checkRefundable(order);
        if (!check.allowed()) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, check.reason());
        }

        if (!stateMachine.startRefund(orderNo, "USER")) {
            // Lost a race with the timeout job, or with a second press.
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, "订单状态已变化，请刷新后重试");
        }

        log.info("refund started: orderNo={}, amount={}, reason={}",
                orderNo, check.amount(), reason);
        return check.amount();
    }

    /**
     * G3 branch two: the money is back, so the order says so.
     *
     * <p>Three things happen and all three are consequences of one fact. The
     * order becomes REFUNDED; the seat ledger goes from sold back to
     * available, into the pool, because the seats can be sold again; and the
     * Redis hold is cleared so the seat map offers them.
     *
     * <p>The Redis call is deliberately <b>outside</b> the transaction.
     * Redis cannot be rolled back, so a clearing written inside would survive
     * a rollback and leave an order that still reads as paid with its seats
     * already on sale to somebody else.
     *
     * <p>The order and the ledger are inside it, so a failure in either leaves
     * neither done - and the refund retry sweep will run this again.
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRefunded(String orderNo, BigDecimal amount) {
        Order order = stateMachine.require(orderNo);

        if (!stateMachine.markRefunded(orderNo, amount, null)) {
            // Already refunded. Not an error: the retry sweep may have got
            // here first, and the outcome is the one the caller wanted.
            log.info("refund already recorded: orderNo={}", orderNo);
            return;
        }

        try {
            // releaseToPool = true: these seats were sold, so the sold counter
            // comes down and the seats go back on sale.
            movieClient.release(order.getScheduleId(), orderNo, order.getSeatCount(), true);
        } catch (Exception e) {
            // Loud, because the transaction is about to roll back and the
            // refund will be retried - the money has moved but the ledger
            // disagrees, and that is the difference the sweep exists to close.
            log.error("refund recorded but the seat ledger was not released: orderNo={}",
                    orderNo, e);
            throw e;
        }

        log.info("order refunded: orderNo={}, amount={}", orderNo, amount);
    }

    /** Clears the Redis hold for a refunded order. Best-effort, after the fact. */
    public void clearSeatHold(Order order) {
        try {
            // includeSold: the seats are marked SOLD:, and a refund is the one
            // flow where those go back on the market.
            seatClient.release(order.getScheduleId(), order.getOrderNo(), true);
        } catch (Exception e) {
            log.error("could not clear the seat hold for refunded order: {} - "
                    + "the seats stay unsellable until the reconciliation job runs",
                    order.getOrderNo(), e);
        }
    }

    // ------------------------------------------------------------

    private List<OrderItem> itemsOf(String orderNo) {
        return orderItemMapper.selectList(
                Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderNo, orderNo));
    }

    /** The verdict, with the amount so a caller does not have to re-derive it. */
    public record RefundCheck(boolean allowed, String reason, BigDecimal amount) {

        public static RefundCheck yes(BigDecimal amount) {
            return new RefundCheck(true, "", amount);
        }

        public static RefundCheck no(String reason) {
            return new RefundCheck(false, reason, BigDecimal.ZERO);
        }
    }
}
