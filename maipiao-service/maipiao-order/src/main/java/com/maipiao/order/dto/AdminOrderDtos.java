package com.maipiao.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Shapes for order administration. */
public final class AdminOrderDtos {

    private AdminOrderDtos() {
    }

    /**
     * One row of the order list.
     *
     * <p>Carries the buyer's phone alongside the order, because that is what
     * an administrator has when a customer calls, and the order number is what
     * they do not.
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

    /** An order with its seats, for looking one up in full. */
    public record OrderDetail(
            com.maipiao.order.entity.Order order,
            List<com.maipiao.order.entity.OrderItem> items,
            String statusName
    ) {
    }

    /**
     * What a user has bought.
     *
     * <p>Cancelled and refunded orders count for neither figure: an
     * administrator asking "has this person bought anything" does not mean
     * "have they ever clicked buy".
     */
    public record UserOrderStats(int orderCount, BigDecimal paidAmount) {
    }
}
