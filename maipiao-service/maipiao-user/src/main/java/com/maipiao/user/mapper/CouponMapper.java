package com.maipiao.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.user.entity.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 优惠券持久化。
 *
 * <p>下面三个方法存在的理由只有一个：它们每一个都是一道并发边界，
 * 而边界必须是一条带条件的 UPDATE。把它们写成「查询、在 Java 里判断、再更新」，
 * 会把他们本来要挡住的竞态原封不动地请回来。
 *
 * <p>每一个调用方都必须断言返回的行数。{@code 0} 意味着别人先到了（或者守护条件没成立），
 * 而这个操作必须被当作失败，不能当成幂等成功。
 */
@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 为一笔订单锁住一张优惠券。这是 G1 全局事务的分支 ②。
     *
     * <p>{@code status = 0} 这个条件就是全部要点：两笔订单抢同一张券，
     * 会产生一个影响 1 行的更新和一个影响 0 行的更新。
     *
     * @return 锁到手时为 1；券已被占用、已使用、已过期、属于别人、
     *         或金额低于门槛时为 0
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

    /** 已锁定 -> 已使用。支付成功后调用。 */
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
     * 已锁定 -> 未使用。订单取消或超时时调用，G1 回滚路径上也会调用。
     *
     * <p>用 {@code order_no} 做守护，这样一笔更早订单发来的过期补偿消息，
     * 就不能释放一张已经被更新的订单占住的券。
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
