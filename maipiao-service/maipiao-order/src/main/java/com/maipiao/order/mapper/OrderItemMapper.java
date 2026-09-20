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
     * 一条语句把一笔订单的所有座位都插进去。
     *
     * <p>一次往返，而不是每个座位一次；在全局事务里这个差别是实打实的：
     * 每条语句都是一次带自己 undo log 记录的 Seata 分支写入，
     * 否则六个座位就等于用六倍的记账量换同一个结果。
     */
    @Insert("""
            <script>
            INSERT INTO t_order_item
              (id, order_no, schedule_id, seat_id, seat_index, seat_label,
               price, tier_id, ticket_no, check_status, create_time, update_time)
            VALUES
            <foreach collection="items" item="i" separator=",">
              (#{i.id}, #{i.orderNo}, #{i.scheduleId}, #{i.seatId}, #{i.seatIndex},
               #{i.seatLabel}, #{i.price}, #{i.tierId}, #{i.ticketNo}, #{i.checkStatus},
               NOW(3), NOW(3))
            </foreach>
            </script>
            """)
    int insertBatch(@Param("items") List<OrderItem> items);
}
