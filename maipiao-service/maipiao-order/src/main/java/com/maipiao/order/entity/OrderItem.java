package com.maipiao.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_order.t_order_item} - one row per seat.
 *
 * <p>{@code ticketNo} is generated when the order is paid for (G2), not when
 * it is created. A ticket number that exists before payment is a ticket that
 * could be presented before payment.
 */
@Data
@TableName("t_order_item")
public class OrderItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private Long scheduleId;

    private String seatId;

    /** Bitmap offset, kept so a reconciliation can compare against the ledger. */
    private Integer seatIndex;

    /** Human readable, e.g. "5排7座". */
    private String seatLabel;

    private BigDecimal price;

    /**
     * The price band this seat was sold at.
     *
     * <p>Recorded rather than reconstructed: a refund has to give back what
     * this seat cost, and an order holding a 1880 VIP seat next to a 580 stand
     * seat has no single figure to derive it from.
     */
    private Long tierId;

    /** Admission code, empty until the order is paid. */
    private String ticketNo;

    private Integer checkStatus;

    private LocalDateTime checkTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
