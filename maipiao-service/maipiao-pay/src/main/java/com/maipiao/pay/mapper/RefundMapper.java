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
     * 创建一笔退款；已经存在时什么都不做。
     *
     * <p>针对 payment_no 上那个唯一键的 {@code INSERT IGNORE} 就是 L4 层：
     * 被双击的退款按钮只会产生一行，调用方随后把它读回来，
     * 而不是去渠道方那里再发一笔退款。
     *
     * @return 建出了新行返回 1，已经存在返回 0
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
     * 把退款单标记为成功。
     *
     * <p>{@code status IN (0,1)}：「尚未开始」和「在途」都有可能成功；
     * 而金额检查拦住了另一笔金额的通知把它结掉。
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
     * 记下一次失败的尝试，并把下一次安排上。
     *
     * <p>退避是指数式且有上限的，在 SQL 里算出来，好让每个调用方拿到同一套节奏，
     * 不必各自再实现一遍。
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

    /** 到了该再试一次的时候的退款单。 */
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
