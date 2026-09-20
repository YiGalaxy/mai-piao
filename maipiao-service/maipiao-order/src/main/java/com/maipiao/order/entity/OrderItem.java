package com.maipiao.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_order.t_order_item} —— 一个座位一行。
 *
 * <p>{@code ticketNo} 是在订单被支付时（G2）生成的，不是在下单时。
 * 支付之前就已存在的票号，是一张能在支付之前被拿出来用的票。
 */
@Data
@TableName("t_order_item")
public class OrderItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private Long scheduleId;

    private String seatId;

    /** 位图偏移量，留着好让对账能拿它和账本比对。 */
    private Integer seatIndex;

    /** 给人看的，例如 "5排7座"。 */
    private String seatLabel;

    private BigDecimal price;

    /**
     * 这个座位售出时所在的票档。
     *
     * <p>记下来，而不是事后重建：退款要退的是这个座位当时花的钱，
     * 而一笔把 1880 的 VIP 座和 580 的看台座放在一起的订单，
     * 根本没有任何一个单一数字可以拿来推导。
     */
    private Long tierId;

    /** 入场码，在订单支付之前是空的。 */
    private String ticketNo;

    private Integer checkStatus;

    private LocalDateTime checkTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
