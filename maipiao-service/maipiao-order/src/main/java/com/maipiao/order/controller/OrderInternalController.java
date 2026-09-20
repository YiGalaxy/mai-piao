package com.maipiao.order.controller;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderItem;
import com.maipiao.order.mapper.OrderItemMapper;
import com.maipiao.order.service.OrderService;
import com.maipiao.order.service.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service-to-service endpoints for the payment flow (G2, G3).
 *
 * <p>Reachable only from inside the cluster: the gateway rejects
 * {@code /api/*&#47;inner/**} before consulting its public whitelist, because a
 * whitelist cannot express "public except for these" and these endpoints move
 * money and inventory.
 *
 * <p>Each is a branch of a Seata global transaction, so the rule is the same
 * as everywhere else: assert the affected row count and throw when it does not
 * match. A branch that reports success without changing anything would let a
 * payment commit with no tickets behind it.
 */
@Slf4j
@RestController
@RequestMapping("/inner")
@RequiredArgsConstructor
public class OrderInternalController {

    private final OrderService orderService;
    private final OrderStateMachine stateMachine;
    private final OrderItemMapper orderItemMapper;

    /**
     * G2 branch: order to PAID, and the seat ledger from locked to sold.
     *
     * <p>Both halves live behind this one call because they are one fact. Split
     * across two endpoints, a caller that did the first and not the second
     * would leave a paid order whose seats still read as held - which is what
     * happened while this was only the status change.
     */
    @PostMapping("/{orderNo}/paid")
    public R<Void> markPaid(@PathVariable String orderNo,
                            @RequestParam LocalDateTime payTime) {
        orderService.markPaid(orderNo, payTime);
        return R.ok();
    }

    /**
     * Marks the Redis hold as sold. Called by pay-service <b>after</b> the G2
     * transaction commits.
     *
     * <p>A separate endpoint rather than part of G2 precisely because it must
     * not be inside it: Redis cannot be rolled back, so a marker written within
     * the transaction outlives a rollback and pins the seat as sold forever.
     */
    @PostMapping("/{orderNo}/confirm-seats")
    public R<Void> confirmSeats(@PathVariable String orderNo) {
        orderService.confirmSeatHold(orderNo);
        return R.ok();
    }

    /**
     * G2 branch: issue the tickets.
     *
     * <p>Generates a ticket number per seat at the moment of payment. A ticket
     * number that exists before payment is one that could be presented before
     * payment.
     */
    @PostMapping("/{orderNo}/issue-tickets")
    public R<Void> issueTickets(@PathVariable String orderNo,
                                @RequestParam String paymentNo) {
        List<OrderItem> items = orderItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getOrderNo, orderNo));

        if (items.isEmpty()) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单没有座位明细");
        }

        int issued = 0;
        for (OrderItem item : items) {
            if (item.getTicketNo() != null && !item.getTicketNo().isBlank()) {
                continue; // already issued; a retry must not mint a second code
            }
            item.setTicketNo(SnowflakeIdGenerator.nextString());
            orderItemMapper.updateById(item);
            issued++;
        }

        log.info("tickets issued: orderNo={}, paymentNo={}, count={}", orderNo, paymentNo, issued);
        return R.ok();
    }

    /** G3 branch: order to REFUNDED. */
    @PostMapping("/{orderNo}/refund-success")
    public R<Void> markRefunded(@PathVariable String orderNo,
                                @RequestParam BigDecimal refundAmount) {
        boolean moved = stateMachine.markRefunded(orderNo, refundAmount, null);
        if (!moved) {
            // Not fatal in the same way as a missed payment: the refund itself
            // succeeded, so the money is back with the user. The order state
            // being wrong is worth a warning, not a rollback.
            log.warn("refund succeeded but order was not in REFUNDING: orderNo={}", orderNo);
        }
        return R.ok();
    }

    /** Snapshot for the payment page. */
    @org.springframework.web.bind.annotation.GetMapping("/{orderNo}/summary")
    public R<Order> summary(@PathVariable String orderNo) {
        return R.ok(stateMachine.require(orderNo));
    }
}
