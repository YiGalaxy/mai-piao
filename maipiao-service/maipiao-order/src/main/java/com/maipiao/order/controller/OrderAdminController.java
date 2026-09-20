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
 * 给管理员用的订单查询。
 *
 * <p>故意做成只读。改一笔订单意味着要么动钱，要么把没付过款的座位发出去，
 * 而这两件事都需要各自专属的流程 —— 一次退款、一次补发 ——
 * 而不是一个万能的编辑接口。一个能悄悄改写订单的后台，是一个没人审计得了的后台。
 *
 * <p>仅限管理员；网关会把没有该角色的 token 挡在 {@code /api/*&#47;admin/**} 之外。
 */
@Slf4j
@RestController
@RequestMapping("/order/admin")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderAdminService adminService;

    /**
     * 搜索订单。
     *
     * <p>每个筛选条件都是可选的，并且可以叠加。手机号是管理员通常手里有的那一个；
     * 它会被换算成用户 id，而不是去比对一份存下来的副本，因为订单里根本没存。
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
