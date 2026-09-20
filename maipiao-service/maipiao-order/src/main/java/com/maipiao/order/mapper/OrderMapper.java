package com.maipiao.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Order persistence.
 *
 * <p>{@link #transition} is the only method that changes an order's status, and
 * everything about the state machine rests on it:
 *
 * <pre>
 * UPDATE ... WHERE order_no = ? AND status IN (allowed from-statuses)
 * </pre>
 *
 * <p>Two concurrent callers racing to move an order both reach the row; InnoDB
 * serialises them; one gets 1 and the other gets 0. The caller must treat 0 as
 * "somebody already did this" and return success, not as an error - which is
 * exactly what makes a redelivered message harmless.
 *
 * <p>A bare {@code UPDATE t_order_order SET status = ...} without a status
 * predicate is forbidden anywhere in this codebase.
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Select("""
            SELECT * FROM t_order_order WHERE order_no = #{orderNo}
            """)
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * Moves an order to {@code toStatus} only if it is currently in one of
     * {@code fromStatuses}.
     *
     * @return 1 when this call performed the transition, 0 when the order was
     *         already in another state
     */
    @Update("""
            <script>
            UPDATE t_order_order
               SET status = #{toStatus},
                   update_time = NOW(3)
             WHERE order_no = #{orderNo}
               AND status IN
               <foreach collection="fromStatuses" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    int transition(@Param("orderNo") String orderNo,
                   @Param("fromStatuses") List<Integer> fromStatuses,
                   @Param("toStatus") int toStatus);

    /** Payment succeeded: status plus the payment columns, in one guarded write. */
    @Update("""
            <script>
            UPDATE t_order_order
               SET status = 2,
                   pay_time = #{payTime},
                   update_time = NOW(3)
             WHERE order_no = #{orderNo}
               AND status IN
               <foreach collection="fromStatuses" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    int markPaid(@Param("orderNo") String orderNo,
                 @Param("fromStatuses") List<Integer> fromStatuses,
                 @Param("payTime") LocalDateTime payTime);

    /** Refund succeeded: status plus the refund columns. */
    @Update("""
            UPDATE t_order_order
               SET status = 6,
                   refund_time = #{refundTime},
                   refund_amount = #{refundAmount},
                   update_time = NOW(3)
             WHERE order_no = #{orderNo}
               AND status = 5
            """)
    int markRefunded(@Param("orderNo") String orderNo,
                     @Param("refundAmount") java.math.BigDecimal refundAmount,
                     @Param("refundTime") LocalDateTime refundTime);

    /**
     * Orders that are past their payment deadline.
     *
     * <p>Backed by {@code idx_status_expire}. Bounded by {@code limit} so a
     * backlog cannot turn one sweep into a table scan that blocks everything.
     */
    @Select("""
            SELECT * FROM t_order_order
             WHERE status = 0
               AND lock_expire_time < #{now}
             ORDER BY lock_expire_time
             LIMIT #{limit}
            """)
    List<Order> selectExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** Paid orders whose screening finished a while ago - ready to complete. */
    @Select("""
            SELECT * FROM t_order_order
             WHERE status = 2
               AND show_time < #{before}
             ORDER BY show_time
             LIMIT #{limit}
            """)
    List<Order> selectCompletable(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
