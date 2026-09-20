package com.maipiao.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_order.t_order_order}。
 *
 * <p>影片名、影院名和影厅名是反范式化的快照。一个每行都去调 movie-service 的订单列表，
 * 渲染一页就要发出 N 次远程调用；而且这些名字本来就是购买当时的名字 ——
 * 影片之后改了名，不能反过来改写某个人的订单历史。
 */
@Data
@TableName("t_order_order")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务单号；同时也是座位锁凭证，所以两者不可能对不上。 */
    private String orderNo;

    private Long userId;

    private Long scheduleId;
    private Long projectId;
    private String projectTitle;

    /**
     * 买的是哪一类东西：MOVIE、CONCERT、TALK_SHOW、THEATER、MUSICAL。
     *
     * <p>和标题、场馆一样做成快照，而不是通过 project_id 去查，因为这是一份历史记录。
     * 一个项目之后被改了分类或被删掉，不能反过来改写别人已经买下的东西 ——
     * 而且订单页需要这个答案来说「到场馆取票」，而不是「到影院取票」。
     */
    private String category;

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

    /** 见 {@link OrderStatus}。 */
    private Integer status;

    /** 支付截止时间。过了这个点订单会被取消、座位被释放。 */
    private LocalDateTime lockExpireTime;

    private LocalDateTime payTime;
    private LocalDateTime refundTime;
    private BigDecimal refundAmount;

    private LocalDate showDate;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
