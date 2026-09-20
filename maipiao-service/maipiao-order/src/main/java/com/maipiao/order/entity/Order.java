package com.maipiao.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_order.t_order_order}.
 *
 * <p>The film, cinema and hall names are denormalised snapshots. An order list
 * that called movie-service for each row would issue N remote calls to render
 * a page, and the names are what they were at purchase time anyway - a film
 * being renamed later must not rewrite someone's order history.
 */
@Data
@TableName("t_order_order")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Business number; also the seat lock token, so the two cannot drift apart. */
    private String orderNo;

    private Long userId;

    private Long sessionId;
    private Long projectId;
    private String projectTitle;

    private Long venueId;
    private String venueName;
    private String placeName;

    private LocalDateTime showTime;

    private Integer seatCount;
    private String seatLabels;

    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;

    private Long couponId;

    /** See {@link OrderStatus}. */
    private Integer status;

    /** Payment deadline. After this the order is cancelled and the seats freed. */
    private LocalDateTime lockExpireTime;

    private LocalDateTime payTime;
    private LocalDateTime refundTime;
    private BigDecimal refundAmount;

    private LocalDate showDate;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
