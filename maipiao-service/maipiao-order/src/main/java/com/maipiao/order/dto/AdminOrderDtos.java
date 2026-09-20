package com.maipiao.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单管理相关的结构。 */
public final class AdminOrderDtos {

    private AdminOrderDtos() {
    }

    /**
     * 订单列表的一行。
     *
     * <p>把买家的手机号和订单一起带上，因为客户打电话来时，
     * 管理员手里有的是手机号，没有的正是订单号。
     */
    public record OrderRow(
            String orderNo,
            Long userId,
            String phone,
            String projectTitle,
            String venueName,
            LocalDateTime showTime,
            Integer seatCount,
            BigDecimal payAmount,
            Integer status,
            String statusName,
            LocalDateTime createTime,
            LocalDateTime payTime
    ) {
    }

    public record OrderPage(List<OrderRow> rows, long total, int page, int size) {
    }

    /** 一笔订单连同它的座位，用来完整地查一笔。 */
    public record OrderDetail(
            com.maipiao.order.entity.Order order,
            List<com.maipiao.order.entity.OrderItem> items,
            String statusName
    ) {
    }

    /**
     * 某个用户买了什么。
     *
     * <p>已取消和已退款的订单在两个数字里都不算：管理员问「这个人买过东西吗」，
     * 问的不是「他点过购买按钮吗」。
     */
    public record UserOrderStats(int orderCount, BigDecimal paidAmount) {
    }
}
