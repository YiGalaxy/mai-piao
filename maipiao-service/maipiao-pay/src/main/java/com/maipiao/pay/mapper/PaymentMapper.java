package com.maipiao.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.pay.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付单的持久化。
 *
 * <p>{@link #casPaySuccess} 是幂等体系里的 L2 层，其中 {@code status IN (0,2)}
 * 这个条件就是防乱序的全部。两条并发的回调都会走到这条语句；InnoDB 会在行上把它们串起来，
 * 其中一条拿到 0。整个过程没有用到分布式锁，也不需要。
 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    @Select("""
            SELECT * FROM t_pay_payment WHERE payment_no = #{paymentNo}
            """)
    Payment selectByPaymentNo(@Param("paymentNo") String paymentNo);

    @Select("""
            SELECT * FROM t_pay_payment WHERE order_no = #{orderNo} ORDER BY create_time DESC LIMIT 1
            """)
    Payment selectLatestByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 把支付单标记为成功，前提是它处在允许这么做的状态。
     *
     * <p>金额检查不是装饰：没有它，一条报了别的金额的回调也会被接受，
     * 一笔一元的支付就是这样把一千元的订单标成已支付的。
     *
     * @return 本次调用真正完成了状态迁移时返回 1；支付单已经成功、已经关闭，
     *         或者金额不符时返回 0
     */
    @Update("""
            UPDATE t_pay_payment
               SET status = 1,
                   channel_trade_no = #{channelTradeNo},
                   pay_time = #{payTime},
                   notify_time = NOW(3),
                   update_time = NOW(3)
             WHERE payment_no = #{paymentNo}
               AND status IN (0, 2)
               AND amount = #{amount}
            """)
    int casPaySuccess(@Param("paymentNo") String paymentNo,
                      @Param("channelTradeNo") String channelTradeNo,
                      @Param("amount") BigDecimal amount,
                      @Param("payTime") LocalDateTime payTime);

    /**
     * 把支付单标记为失败。
     *
     * <p>只以 {@code status = 0} 为守卫：失败绝不能覆盖一个成功，而 0 是唯一
     * 一个「失败」还算有意义的状态。
     */
    @Update("""
            UPDATE t_pay_payment
               SET status = 2, notify_time = NOW(3), update_time = NOW(3)
             WHERE payment_no = #{paymentNo} AND status = 0
            """)
    int casPayFailed(@Param("paymentNo") String paymentNo);

    /**
     * 关闭一笔已过期的支付。
     *
     * <p>条件是 {@code status IN (0,2)}，这样一笔刚刚成功的支付，
     * 不会被关在自己的回调底下。
     */
    @Update("""
            UPDATE t_pay_payment
               SET status = 3, update_time = NOW(3)
             WHERE payment_no = #{paymentNo} AND status IN (0, 2)
            """)
    int closePayment(@Param("paymentNo") String paymentNo);

    /** 已经过了截止时间却仍然开放的支付单 —— 关闭扫描用。 */
    @Select("""
            SELECT * FROM t_pay_payment
             WHERE status = 0
               AND expire_time < #{now}
             ORDER BY expire_time
             LIMIT #{limit}
            """)
    List<Payment> selectExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 开放得足够久、值得去问一下渠道方的支付单。
     *
     * <p>下界是有意义的：几秒前才创建的支付单纯粹是还没来得及付，
     * 去查它等于给每个订单白费一次往返。
     */
    @Select("""
            SELECT * FROM t_pay_payment
             WHERE status IN (0, 2)
               AND create_time < #{createdBefore}
               AND create_time > #{createdAfter}
             ORDER BY create_time
             LIMIT #{limit}
            """)
    List<Payment> selectQueryable(@Param("createdBefore") LocalDateTime createdBefore,
                                  @Param("createdAfter") LocalDateTime createdAfter,
                                  @Param("limit") int limit);
}
