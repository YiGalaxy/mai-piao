package com.maipiao.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_user.t_user_coupon_template} - the coupon definition that
 * admins manage. Claiming one copies {@code amount} / {@code threshold} onto
 * the issued {@link Coupon}.
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

    /** Validity in days, counted from the moment a user claims it. */
    private Integer validDays;

    /** 0 means unlimited. */
    private Integer totalQuantity;

    private Integer claimedQuantity;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
