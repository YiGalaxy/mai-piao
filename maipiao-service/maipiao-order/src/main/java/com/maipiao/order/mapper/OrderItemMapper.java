package com.maipiao.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.order.entity.OrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * Inserts every seat of an order in one statement.
     *
     * <p>One round trip instead of one per seat, and inside the global
     * transaction the difference is real: each statement is a Seata branch
     * write with its own undo log entry, so six seats would otherwise mean six
     * times the bookkeeping for the same outcome.
     */
    @Insert("""
            <script>
            INSERT INTO t_order_item
              (id, order_no, session_id, seat_id, seat_index, seat_label,
               price, ticket_no, check_status, create_time, update_time)
            VALUES
            <foreach collection="items" item="i" separator=",">
              (#{i.id}, #{i.orderNo}, #{i.sessionId}, #{i.seatId}, #{i.seatIndex},
               #{i.seatLabel}, #{i.price}, #{i.ticketNo}, #{i.checkStatus}, NOW(3), NOW(3))
            </foreach>
            </script>
            """)
    int insertBatch(@Param("items") List<OrderItem> items);
}
