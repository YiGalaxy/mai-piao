package com.maipiao.order.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.order.dto.OrderDtos;
import com.maipiao.order.entity.Order;
import com.maipiao.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

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
    @PostMapping("/{orderNo}/cancel")
    public R<Boolean> cancel(@PathVariable String orderNo) {
        return R.ok(orderService.cancel(orderNo, UserContext.require(), true));
    }
}
