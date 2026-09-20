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
 * Payment persistence.
 *
 * <p>{@link #casPaySuccess} is layer L2 of the idempotency stack, and the
 * {@code status IN (0,2)} predicate in it is the entire out-of-order defence.
 * Two concurrent callbacks both reach this statement; InnoDB serialises them
 * on the row and one gets 0. No distributed lock is involved or needed.
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
     * Marks a payment successful, if it is in a state that allows it.
     *
     * <p>The amount check is not decoration: without it, a callback quoting a
     * different amount would be accepted, which is how a payment for one yuan
     * ends up marking a thousand-yuan order as paid.
     *
     * @return 1 when this call performed the transition, 0 when the payment
     *         was already successful, already closed, or the amount differs
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
     * Marks a payment failed.
     *
     * <p>Guarded on {@code status = 0} alone: a failure must never overwrite a
     * success, and 0 is the only state where failing is still meaningful.
     */
    @Update("""
            UPDATE t_pay_payment
               SET status = 2, notify_time = NOW(3), update_time = NOW(3)
             WHERE payment_no = #{paymentNo} AND status = 0
            """)
    int casPayFailed(@Param("paymentNo") String paymentNo);

    /**
     * Closes an expired payment.
     *
     * <p>{@code status IN (0,2)} so a payment that succeeded a moment ago is
     * not closed underneath its callback.
     */
    @Update("""
            UPDATE t_pay_payment
               SET status = 3, update_time = NOW(3)
             WHERE payment_no = #{paymentNo} AND status IN (0, 2)
            """)
    int closePayment(@Param("paymentNo") String paymentNo);

    /** Payments past their deadline but still open - the closing sweep. */
    @Select("""
            SELECT * FROM t_pay_payment
             WHERE status = 0
               AND expire_time < #{now}
             ORDER BY expire_time
             LIMIT #{limit}
            """)
    List<Payment> selectExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * Open payments old enough to be worth asking the provider about.
     *
     * <p>The lower bound matters: a payment created seconds ago has simply not
     * been paid yet, and querying it wastes a round trip per order.
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
