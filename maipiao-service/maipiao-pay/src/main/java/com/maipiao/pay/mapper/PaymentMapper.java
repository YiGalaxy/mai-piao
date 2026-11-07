package com.maipiao.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.pay.entity.Payment;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 支付单持久化；状态更新使用条件 SQL 保证回调幂等和乱序安全。 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    @Select(
            """
            SELECT * FROM t_pay_payment WHERE payment_no = #{paymentNo}
            """)
    Payment selectByPaymentNo(@Param("paymentNo") String paymentNo);

    @Select(
            """
            SELECT * FROM t_pay_payment WHERE order_no = #{orderNo} ORDER BY create_time DESC LIMIT 1
            """)
    Payment selectLatestByOrderNo(@Param("orderNo") String orderNo);

    @org.apache.ibatis.annotations.Insert(
            """
            INSERT INTO t_pay_payment
              (id,payment_no,order_no,user_id,channel,amount,status,expire_time)
            VALUES (#{id},#{paymentNo},#{orderNo},#{userId},#{channel},#{amount},0,#{expireTime})
            ON DUPLICATE KEY UPDATE id=id
            """)
    int insertIntent(Payment payment);

    @Update(
            """
            UPDATE t_pay_payment SET channel_trade_no=#{tradeNo}, cashier_url=COALESCE(#{url},cashier_url), update_time=NOW(3)
            WHERE payment_no=#{paymentNo}
              AND (channel_trade_no IS NULL OR channel_trade_no=#{tradeNo})
            """)
    int bindChannel(
            @Param("paymentNo") String paymentNo,
            @Param("tradeNo") String tradeNo,
            @Param("url") String url);

    @Select("SELECT * FROM t_pay_payment WHERE payment_no=#{paymentNo} FOR UPDATE")
    Payment selectForUpdate(String paymentNo);

    @Select(
            "SELECT * FROM t_pay_payment WHERE status IN(0,2) AND update_time<#{before} ORDER BY update_time LIMIT 50")
    List<Payment> selectForReconciliation(LocalDateTime before);

    @Update(
            "UPDATE t_pay_payment SET update_time=NOW(3) WHERE payment_no=#{paymentNo} AND status IN(0,2)")
    int touch(String paymentNo);

    @Select(
            "SELECT * FROM t_pay_payment WHERE status=1 AND seat_synced=0 AND update_time<#{before} ORDER BY update_time LIMIT 50")
    List<Payment> selectSeatSyncPending(LocalDateTime before);

    @Update(
            "UPDATE t_pay_payment SET update_time=NOW(3) WHERE payment_no=#{paymentNo} AND status=1 AND seat_synced=0")
    int touchSeatSync(String paymentNo);

    @Update("UPDATE t_pay_payment SET seat_synced=1 WHERE payment_no=#{paymentNo} AND status=1")
    int markSeatSynced(String paymentNo);

    /** 支付成功 CAS；同时校验金额、渠道流水和可迁移状态。 */
    @Update(
            """
            UPDATE t_pay_payment
               SET status = 1,
                   channel_trade_no = #{channelTradeNo},
                   pay_time = #{payTime},
                   notify_time = NOW(3),
                   update_time = NOW(3)
             WHERE payment_no = #{paymentNo}
               AND status IN (0, 2)
               AND amount = #{amount}
               AND channel_trade_no = #{channelTradeNo}
            """)
    int casPaySuccess(
            @Param("paymentNo") String paymentNo,
            @Param("channelTradeNo") String channelTradeNo,
            @Param("amount") BigDecimal amount,
            @Param("payTime") LocalDateTime payTime);

    /** 标记支付失败，不能覆盖成功状态。 */
    @Update(
            """
            UPDATE t_pay_payment
               SET status = 2, notify_time = NOW(3), update_time = NOW(3)
             WHERE payment_no = #{paymentNo} AND status = 0
            """)
    int casPayFailed(@Param("paymentNo") String paymentNo);

    /** 关闭未完成支付，避免覆盖成功回调。 */
    @Update(
            """
            UPDATE t_pay_payment
               SET status = 3, update_time = NOW(3)
             WHERE payment_no = #{paymentNo} AND status IN (0, 2)
            """)
    int closePayment(@Param("paymentNo") String paymentNo);

    /** 查询已过期的开放支付单。 */
    @Select(
            """
            SELECT * FROM t_pay_payment
             WHERE status = 0
               AND expire_time < #{now}
             ORDER BY expire_time
             LIMIT #{limit}
            """)
    List<Payment> selectExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 查询需要向渠道核对的支付单。 */
    @Select(
            """
            SELECT * FROM t_pay_payment
             WHERE status IN (0, 2)
               AND create_time < #{createdBefore}
               AND create_time > #{createdAfter}
             ORDER BY create_time
             LIMIT #{limit}
            """)
    List<Payment> selectQueryable(
            @Param("createdBefore") LocalDateTime createdBefore,
            @Param("createdAfter") LocalDateTime createdAfter,
            @Param("limit") int limit);
}
