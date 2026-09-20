package com.maipiao.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_user.t_user_coupon}.
 *
 * <p>Status values and the transitions that matter here:
 * <pre>
 *   0 unused --lock-->  1 locked --consume--> 2 used
 *                      1 locked --rollback--> 0 unused
 *   0 unused ---------- expire -------------> 3 expired
 * </pre>
 *
 * <p>Every one of those is a conditional UPDATE, never a read-then-write. The
 * lock in particular is
 * {@code UPDATE ... SET status=1 WHERE id=? AND user_id=? AND status=0}
 * and the caller must assert that one row changed - otherwise two concurrent
 * orders could both "successfully" lock the same coupon.
 */
@Data
@TableName("t_user_coupon")
public class Coupon {

    public static final int STATUS_UNUSED = 0;
    public static final int STATUS_LOCKED = 1;
    public static final int STATUS_USED = 2;
    public static final int STATUS_EXPIRED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long couponTemplateId;

    /** Discount amount, copied from the template at claim time so that editing
     *  the template later cannot retroactively change an issued coupon. */
    private BigDecimal amount;

    /** Minimum order amount required to use it. */
    private BigDecimal threshold;

    private Integer status;

    /** Set when an order locks or consumes this coupon. */
    private String orderNo;

    private LocalDateTime lockTime;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
