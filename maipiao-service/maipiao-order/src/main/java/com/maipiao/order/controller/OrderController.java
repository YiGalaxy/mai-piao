package com.maipiao.order.controller;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.order.dto.OrderDtos;
import com.maipiao.order.entity.Order;
import com.maipiao.order.feign.PayClient;
import com.maipiao.order.service.OrderRefundService;
import com.maipiao.order.service.OrderService;
import com.maipiao.order.service.OrderStateMachine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderRefundService orderRefundService;
    private final OrderStateMachine stateMachine;
    private final PayClient payClient;

    /**
     * Creates an order for seats the caller already holds.
     *
     * <p>This is the G1 entry point: the service method opens a Seata global
     * transaction spanning this service, movie-service and user-service.
     */
    @PostMapping("/create")
    public R<OrderDtos.CreateOrderResponse> create(@Valid @RequestBody OrderDtos.CreateOrderRequest request) {
        return R.ok(orderService.create(request, UserContext.require()));
    }

    /** My orders, newest first, optionally filtered by status. */
    @GetMapping("/list")
    public R<List<Order>> list(@RequestParam(required = false) Integer status) {
        return R.ok(orderService.listByUser(UserContext.require(), status));
    }

    @GetMapping("/{orderNo}")
    public R<OrderDtos.OrderDetail> detail(@PathVariable String orderNo) {
        return R.ok(orderService.detail(orderNo, UserContext.require()));
    }

    /**
     * Cancels an unpaid order and frees its seats.
     *
     * <p>Returns whether this call is the one that cancelled it. A false result
     * is not an error - it means the order was already cancelled, typically by
     * the timeout job firing a second before the user pressed the button.
     */
    /**
     * Asks for a refund.
     *
     * <p>Guards are evaluated here, not trusted from the client: within the
     * refund window, not yet entered, and actually paid. A button that is
     * greyed out in the browser is a courtesy, not a rule.
     *
     * <p>The state move and the money move are separate calls on purpose. The
     * order goes to REFUNDING first and the provider is asked afterwards, so
     * a provider that is slow or down leaves a record of what was wanted
     * rather than a customer who pressed a button that did nothing.
     */
    @PostMapping("/{orderNo}/refund")
    public R<Map<String, Object>> refund(@PathVariable String orderNo,
                                         @RequestParam(required = false) String reason) {
        Long userId = UserContext.require();
        Order order = stateMachine.require(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        BigDecimal amount = orderRefundService.startRefund(orderNo,
                reason == null || reason.isBlank() ? "USER_REQUEST" : reason);

        // Then hand it to pay-service, which owns the refund transaction.
        String refundNo = payClient.applyRefund(orderNo, amount).getData();

        Map<String, Object> body = new HashMap<>();
        body.put("refundNo", refundNo);
        body.put("amount", amount);
        return R.ok(body);
    }

    /** Whether a refund is possible, so the page can grey the button out. */
    @GetMapping("/{orderNo}/refundable")
    public R<Map<String, Object>> refundable(@PathVariable String orderNo) {
        Long userId = UserContext.require();
        Order order = stateMachine.require(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        OrderRefundService.RefundCheck check = orderRefundService.checkRefundable(order);

        Map<String, Object> body = new HashMap<>();
        body.put("allowed", check.allowed());
        body.put("reason", check.reason());
        body.put("amount", check.amount());
        return R.ok(body);
    }

    @PostMapping("/{orderNo}/cancel")
    public R<Boolean> cancel(@PathVariable String orderNo) {
        return R.ok(orderService.cancel(orderNo, UserContext.require(), true));
    }
}
