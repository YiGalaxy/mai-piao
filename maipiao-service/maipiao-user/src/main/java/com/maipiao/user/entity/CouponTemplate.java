package com.maipiao.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_user.t_user_coupon_template} —— 管理员维护的优惠券定义。
 * 领取一张时，会把 {@code amount} / {@code threshold} 复制到发出去的那张 {@link Coupon} 上。
 */
@Data
@TableName("t_user_coupon_template")
public class CouponTemplate {

    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_ONLINE = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private BigDecimal amount;

    private BigDecimal threshold;

    /** 有效天数，从用户领取的那一刻起算。 */
    private Integer validDays;

    /** 0 表示不限量。 */
    private Integer totalQuantity;

    private Integer claimedQuantity;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
