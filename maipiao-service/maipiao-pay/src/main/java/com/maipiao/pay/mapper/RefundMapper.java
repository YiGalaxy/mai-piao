package com.maipiao.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.pay.entity.Refund;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RefundMapper extends BaseMapper<Refund> {

    /**
     * Creates a refund, or does nothing when one already exists.
     *
     * <p>{@code INSERT IGNORE} against the unique key on payment_no is layer
     * L4: a double-clicked refund button produces one row, and the caller then
     * reads it back rather than issuing a second refund at the provider.
     *
     * @return 1 when a row was created, 0 when one already existed
     */
    @Insert("""
            INSERT IGNORE INTO t_pay_refund
              (id, refund_no, payment_no, order_no, user_id, channel, refund_amount,
               reason, status, release_seat, retry_count, create_time, update_time)
            VALUES
              (#{id}, #{refundNo}, #{paymentNo}, #{orderNo}, #{userId}, #{channel},
               #{refundAmount}, #{reason}, 0, #{releaseSeat}, 0, NOW(3), NOW(3))
            """)
    int insertIfAbsent(@Param("id") Long id,
                       @Param("refundNo") String refundNo,
                       @Param("paymentNo") String paymentNo,
                       @Param("orderNo") String orderNo,
                       @Param("userId") Long userId,
                       @Param("channel") String channel,
                       @Param("refundAmount") BigDecimal refundAmount,
                       @Param("reason") String reason,
                       @Param("releaseSeat") int releaseSeat);

    @Select("""
            SELECT * FROM t_pay_refund WHERE payment_no = #{paymentNo}
            """)
    Refund selectByPaymentNo(@Param("paymentNo") String paymentNo);

    @Select("""
            SELECT * FROM t_pay_refund WHERE refund_no = #{refundNo}
            """)
    Refund selectByRefundNo(@Param("refundNo") String refundNo);

    /**
     * Marks a refund successful.
     *
     * <p>{@code status IN (0,1)}: both "not started" and "in flight" may
     * succeed, and the amount check stops a notification for a different
     * amount from closing it out.
     */
    @Update("""
            UPDATE t_pay_refund
               SET status = 2,
                   channel_refund_no = #{channelRefundNo},
                   refund_time = NOW(3),
                   last_error = NULL,
                   update_time = NOW(3)
             WHERE refund_no = #{refundNo}
               AND status IN (0, 1)
               AND refund_amount = #{amount}
            """)
    int casRefundSuccess(@Param("refundNo") String refundNo,
                         @Param("channelRefundNo") String channelRefundNo,
                         @Param("amount") BigDecimal amount);

    /**
     * Records a failed attempt and schedules the next one.
     *
     * <p>Backoff is exponential with a ceiling, computed in SQL so that every
     * caller gets the same schedule without reimplementing it.
     */
    @Update("""
            UPDATE t_pay_refund
               SET status = #{nextStatus},
                   retry_count = retry_count + 1,
                   last_error = #{error},
                   next_retry_time = DATE_ADD(NOW(3), INTERVAL POW(2, LEAST(retry_count, 4)) MINUTE),
                   update_time = NOW(3)
             WHERE refund_no = #{refundNo}
               AND status IN (0, 1)
            """)
    int recordFailure(@Param("refundNo") String refundNo,
                      @Param("nextStatus") int nextStatus,
                      @Param("error") String error);

    /** Refunds due for another attempt. */
    @Select("""
            SELECT * FROM t_pay_refund
             WHERE status IN (0, 1)
               AND retry_count < #{maxRetry}
               AND (next_retry_time IS NULL OR next_retry_time <= NOW(3))
             ORDER BY create_time
             LIMIT #{limit}
            """)
    List<Refund> selectRetryable(@Param("maxRetry") int maxRetry, @Param("limit") int limit);
}
