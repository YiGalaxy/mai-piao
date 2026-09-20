package com.maipiao.order.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.order.dto.AdminOrderDtos;
import com.maipiao.order.service.OrderAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Order lookup for administrators.
 *
 * <p>Read-only, deliberately. Adjusting an order means moving money or handing
 * out seats that were not paid for, and both need a flow of their own - a
 * refund, a reissue - rather than a general-purpose edit. An admin screen that
 * can quietly rewrite an order is an admin screen nobody can audit.
 *
 * <p>Administrators only; the gateway refuses {@code /api/*&#47;admin/**} to a
 * token without the role.
 */
@Slf4j
@RestController
@RequestMapping("/order/admin")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderAdminService adminService;

    /**
     * Searches orders.
     *
     * <p>Every filter is optional and they combine. Phone is the one an
     * administrator usually has; it is resolved to a user id rather than
     * matched against a stored copy, because the order does not store one.
     */
    @GetMapping("/orders")
    public R<AdminOrderDtos.OrderPage> orders(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(adminService.search(orderNo, phone, userId, status, from, to, page, size));
    }

    @GetMapping("/orders/{orderNo}")
    public R<AdminOrderDtos.OrderDetail> detail(@PathVariable String orderNo) {
        return R.ok(adminService.detail(orderNo));
    }
}
