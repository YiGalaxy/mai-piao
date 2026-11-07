package com.maipiao.order.mapper;

import com.maipiao.order.entity.OrderReservation;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 每个方法独立提交，调用方不得携带全局事务 XID。状态更新均由数据库 CAS 仲裁。 */
@Mapper
public interface OrderReservationMapper {
    @Insert(
            """
        INSERT INTO t_order_reservation(token, schedule_id, user_id, expires_at, state)
        VALUES(#{token}, #{scheduleId}, #{userId}, #{expiresAt}, 'PREPARING')
        ON DUPLICATE KEY UPDATE token=token
        """)
    int prepare(OrderReservation row);

    @Select("SELECT * FROM t_order_reservation WHERE token=#{token}")
    OrderReservation find(String token);

    @Update(
            """
        UPDATE t_order_reservation SET seat_indexes=#{seats}, seat_labels=#{labels}, state='RESERVED'
        WHERE token=#{token} AND state='PREPARING' AND expires_at>#{now}
        """)
    int publish(
            @Param("token") String token,
            @Param("seats") String seats,
            @Param("labels") String labels,
            @Param("now") long now);

    @Update(
            """
        UPDATE t_order_reservation SET state='PROCESSING', xid=#{xid}, request_hash=#{hash}
        WHERE token=#{token} AND state IN ('RESERVED','RETRYABLE') AND expires_at>#{now}
          AND (request_hash IS NULL OR request_hash=#{hash} OR (state='RETRYABLE' AND #{revise}=TRUE))
        """)
    int claim(
            @Param("token") String token,
            @Param("xid") String xid,
            @Param("hash") String hash,
            @Param("now") long now,
            @Param("revise") boolean revise);

    @Update(
            """
        UPDATE t_order_reservation SET state=#{state}
        WHERE token=#{token} AND state='PROCESSING' AND xid=#{xid}
        """)
    int finish(
            @Param("token") String token, @Param("xid") String xid, @Param("state") String state);

    @Update(
            """
        UPDATE t_order_reservation SET state='CLOSED'
        WHERE token=#{token} AND state IN ('PREPARING','RESERVED','RETRYABLE')
          AND (expires_at<=#{now} OR #{abandon}=TRUE)
        """)
    int close(
            @Param("token") String token,
            @Param("now") long now,
            @Param("abandon") boolean abandon);

    @Insert(
            """
        INSERT INTO t_order_reservation(token, schedule_id, user_id, expires_at, state)
        VALUES(#{token}, #{scheduleId}, 0, 0, 'CLOSED') ON DUPLICATE KEY UPDATE token=token
        """)
    int closeOrphan(@Param("token") String token, @Param("scheduleId") Long scheduleId);

    @Select(
            """
        SELECT * FROM t_order_reservation
        WHERE state='PROCESSING' OR (state IN ('PREPARING','RESERVED','RETRYABLE','CLOSED') AND expires_at<=#{now})
        ORDER BY update_time LIMIT #{limit}
        """)
    List<OrderReservation> pending(@Param("now") long now, @Param("limit") int limit);

    @Update(
            "UPDATE t_order_reservation SET state='RELEASED' WHERE token=#{token} AND state='CLOSED'")
    int released(String token);

    @Update("UPDATE t_order_reservation SET update_time=NOW(3) WHERE token=#{token}")
    int touch(String token);
}
