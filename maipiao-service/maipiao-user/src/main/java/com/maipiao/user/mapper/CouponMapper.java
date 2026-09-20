package com.maipiao.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.user.entity.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * Coupon persistence.
 *
 * <p>The three methods below exist for one reason: each of them is a
 * concurrency boundary, and a boundary has to be a single conditional UPDATE.
 * Writing them as "select, check in Java, update" would reintroduce exactly the
 * race they are meant to prevent.
 *
 * <p>Every caller MUST assert the returned row count. {@code 0} means somebody
 * else got there first (or the guard did not hold) and the operation must be
 * treated as failed, not as an idempotent success.
 */
@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * Locks a coupon for an order. This is branch ② of the G1 global transaction.
     *
     * <p>The {@code status = 0} predicate is the whole point: two orders racing
     * for the same coupon produce one update of 1 and one of 0.
     *
     * @return 1 when the lock was taken, 0 when the coupon was already taken,
     *         already used, expired, owned by someone else, or below the threshold
     */
    @Update("""
            UPDATE t_user_coupon
               SET status = 1,
                   order_no = #{orderNo},
                   lock_time = NOW(3),
                   update_time = NOW(3)
             WHERE id = #{couponId}
               AND user_id = #{userId}
               AND status = 0
               AND expire_time > NOW(3)
               AND #{orderAmount} >= threshold
            """)
    int lockCoupon(@Param("couponId") Long couponId,
                   @Param("userId") Long userId,
                   @Param("orderNo") String orderNo,
                   @Param("orderAmount") BigDecimal orderAmount);

    /** Locked -> used. Called once payment succeeds. */
    @Update("""
            UPDATE t_user_coupon
               SET status = 2,
                   update_time = NOW(3)
             WHERE id = #{couponId}
               AND order_no = #{orderNo}
               AND status = 1
            """)
    int consumeCoupon(@Param("couponId") Long couponId,
                      @Param("orderNo") String orderNo);

    /**
     * Locked -> unused. Called when the order is cancelled or times out, and by
     * the G1 rollback path.
     *
     * <p>Guarded on {@code order_no} so that a stale compensation message from
     * an older order cannot free a coupon that a newer order now holds.
     */
    @Update("""
            UPDATE t_user_coupon
               SET status = 0,
                   order_no = NULL,
                   lock_time = NULL,
                   update_time = NOW(3)
             WHERE id = #{couponId}
               AND order_no = #{orderNo}
               AND status = 1
            """)
    int releaseCoupon(@Param("couponId") Long couponId,
                      @Param("orderNo") String orderNo);
}
