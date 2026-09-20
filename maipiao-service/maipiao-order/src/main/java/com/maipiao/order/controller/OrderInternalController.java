package com.maipiao.order.controller;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderItem;
import com.maipiao.order.dto.AdminOrderDtos;
import com.maipiao.order.mapper.OrderItemMapper;
import com.maipiao.order.service.OrderAdminService;
import com.maipiao.order.service.OrderRefundService;
import com.maipiao.order.service.OrderService;
import com.maipiao.order.service.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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
    private final OrderAdminService orderAdminService;
    private final OrderRefundService orderRefundService;

    /**
     * How much a user has bought.
     *
     * <p>Called by user-service for the user detail screen, which is the only
     * place that needs it. Deliberately not part of the user list: a page of
     * twenty users would become twenty of these calls for a number nobody
     * reads while scanning.
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam Long userId) {
        AdminOrderDtos.UserOrderStats stats = orderAdminService.statsOf(userId);
        Map<String, Object> body = new HashMap<>();
        body.put("orderCount", stats.orderCount());
        body.put("paidAmount", stats.paidAmount());
        return R.ok(body);
    }

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

    /**
     * G3 分支：订单转已退款，座位回到池子里。
     *
     * <p>三件事一起做，因为它们描述的是同一个事实：订单变成已退款、账本里那几行从
     * 已售改回可选、售出计数减回去。少做任何一件，都会要么票卖不出去，要么卖出去
     * 却不减计数。
     */
    @PostMapping("/{orderNo}/refund-success")
    public R<Void> markRefunded(@PathVariable String orderNo,
                                @RequestParam BigDecimal refundAmount) {
        orderRefundService.markRefunded(orderNo, refundAmount);
        return R.ok();
    }

    /**
     * 清掉 Redis 里的座位占用。在 G3 提交之后调用。
     *
     * <p>单独一个接口，理由和确认出票那个一样：Redis 回滚不了。写在事务里面的话，
     * 事务一旦回滚，这些位还在，座位就以「已售」的姿态长期占着，而账本说它是空的。
     */
    @PostMapping("/{orderNo}/release-refunded-seats")
    public R<Void> releaseRefundedSeats(@PathVariable String orderNo) {
        orderRefundService.clearSeatHold(stateMachine.require(orderNo));
        return R.ok();
    }

    /** Snapshot for the payment page. */
    @GetMapping("/{orderNo}/summary")
    public R<Order> summary(@PathVariable String orderNo) {
        return R.ok(stateMachine.require(orderNo));
    }
}
