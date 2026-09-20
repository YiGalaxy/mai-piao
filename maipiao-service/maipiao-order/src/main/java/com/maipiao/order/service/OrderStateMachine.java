package com.maipiao.order.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderStatus;
import com.maipiao.order.entity.OrderStatusLog;
import com.maipiao.order.mapper.OrderMapper;
import com.maipiao.order.mapper.OrderStatusLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The single exit for every order status change.
 *
 * <p>Nothing else in the codebase may write {@code t_order_order.status}. That
 * is not a style preference: the guard lives entirely in the WHERE clause, so a
 * write that bypasses this class bypasses the guard too, and the state machine
 * silently stops being one.
 *
 * <p>Every method returns whether <em>this</em> call performed the transition.
 * {@code false} is not an error - it means somebody else (a retried message, a
 * double-clicked button, the timeout job racing a payment callback) got there
 * first. Callers treat it as idempotent success.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStateMachine {

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;

    /**
     * Cancels an unpaid order.
     *
     * @return true when this call cancelled it, false when it was already
     *         cancelled, or had moved on to being paid
     */
    public boolean cancel(String orderNo, String operator, String remark) {
        // Only PENDING_PAY is cancellable. PAYING is excluded on purpose: a
        // payment may be in flight, and the timeout job checks with the
        // channel before cancelling rather than racing it.
        boolean moved = doTransition(orderNo,
                List.of(OrderStatus.PENDING_PAY), OrderStatus.CANCELLED, operator, remark);
        return moved;
    }

    /** @return true when this call was the one that marked it paid */
    public boolean markPaid(String orderNo, LocalDateTime payTime, String operator) {
        int rows = orderMapper.markPaid(orderNo, List.of(OrderStatus.PENDING_PAY, OrderStatus.PAYING), payTime);
        boolean moved = rows == 1;
        logTransition(orderNo, OrderStatus.PAID, operator,
                moved ? "payment confirmed" : "already paid or not payable");
        return moved;
    }

    /**
     * Moves a paid order to completed once its screening has finished.
     *
     * <p>Completed orders can no longer be refunded through the normal path,
     * which is why this is driven by the screening end time rather than by a
     * timer alone.
     */
    public boolean complete(String orderNo, String operator) {
        return doTransition(orderNo, List.of(OrderStatus.PAID), OrderStatus.COMPLETED, operator, "screening finished");
    }

    /** Marks a refund as requested. */
    public boolean startRefund(String orderNo, String operator) {
        return doTransition(orderNo, List.of(OrderStatus.PAID, OrderStatus.COMPLETED),
                OrderStatus.REFUNDING, operator, "refund requested");
    }

    /** @return true when this call was the one that recorded the refund */
    public boolean markRefunded(String orderNo, BigDecimal amount, Long operator) {
        int rows = orderMapper.markRefunded(orderNo, amount, LocalDateTime.now());
        boolean moved = rows == 1;
        logTransition(orderNo, OrderStatus.REFUNDED, String.valueOf(operator),
                moved ? "refund confirmed" : "not in refunding state");
        return moved;
    }

    /**
     * Puts a refunding order back to paid.
     *
     * <p>Used when the refund has failed permanently: the money never left, so
     * the order is still paid and the tickets are still valid. Leaving it in
     * REFUNDING would strand it - not usable, not refundable.
     */
    public boolean rollbackRefund(String orderNo, String reason) {
        return doTransition(orderNo, List.of(OrderStatus.REFUNDING), OrderStatus.PAID,
                "SYSTEM", "refund failed: " + reason);
    }

    // ------------------------------------------------------------

    private boolean doTransition(String orderNo, List<Integer> fromStatuses, int toStatus,
                                 String operator, String remark) {
        int rows = orderMapper.transition(orderNo, fromStatuses, toStatus);
        boolean moved = rows == 1;
        logTransition(orderNo, toStatus, operator, moved ? remark : "no-op, status already moved");
        return moved;
    }

    /**
     * Appends to the audit trail.
     *
     * <p>Deliberately not part of the transaction and failure-tolerant: the log
     * is useful, but losing a row must never roll back a status change that has
     * already happened.
     */
    private void logTransition(String orderNo, int toStatus, String operator, String remark) {
        try {
            OrderStatusLog entry = new OrderStatusLog();
            entry.setId(SnowflakeIdGenerator.next());
            entry.setOrderNo(orderNo);
            entry.setFromStatus(-1); // authoritative from-status is in the CAS itself
            entry.setToStatus(toStatus);
            entry.setOperator(operator == null ? "SYSTEM" : operator);
            entry.setRemark(remark);
            statusLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("could not write status log: order={}, status={}, reason={}",
                    orderNo, toStatus, e.getMessage());
        }
    }

    /** Reads an order and asserts it exists. */
    public Order require(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }
}
