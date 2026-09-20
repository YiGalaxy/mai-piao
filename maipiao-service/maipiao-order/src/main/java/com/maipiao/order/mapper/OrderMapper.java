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
 * 订单持久化。
 *
 * <p>{@link #transition} 是唯一会改订单状态的方法，整个状态机都压在它身上：
 *
 * <pre>
 * UPDATE ... WHERE order_no = ? AND status IN (允许的来源状态)
 * </pre>
 *
 * <p>两个并发调用者抢着推进同一笔订单，都会摸到那一行；InnoDB 把它们串行化；
 * 一个拿到 1，另一个拿到 0。调用方必须把 0 解读成「别人已经做过了」并返回成功，
 * 而不是当成错误 —— 重投的消息之所以无害，靠的正是这一点。
 *
 * <p>在这个代码库里，任何地方都不允许出现不带状态谓词的裸
 * {@code UPDATE t_order_order SET status = ...}。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Select("""
            SELECT * FROM t_order_order WHERE order_no = #{orderNo}
            """)
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 只有当订单当前处于 {@code fromStatuses} 之一时，才把它推进到 {@code toStatus}。
     *
     * @return 本次调用完成了迁移时为 1；订单已经处在别的状态时为 0
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

    /** 支付成功：状态和支付相关的列，在一次带守护条件的写入里一起改掉。 */
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

    /** 退款成功：状态和退款相关的列。 */
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
     * 已经过了支付截止时间的订单。
     *
     * <p>由 {@code idx_status_expire} 支撑。用 {@code limit} 兜住上限，
     * 免得一次积压把单次清扫变成一次卡住所有人的全表扫描。
     */
    @Select("""
            SELECT * FROM t_order_order
             WHERE status = 0
               AND lock_expire_time < #{now}
             ORDER BY lock_expire_time
             LIMIT #{limit}
            """)
    List<Order> selectExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 已支付、且场次已经结束一段时间的订单 —— 可以置为已完成了。 */
    @Select("""
            SELECT * FROM t_order_order
             WHERE status = 2
               AND show_time < #{before}
             ORDER BY show_time
             LIMIT #{limit}
            """)
    List<Order> selectCompletable(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
