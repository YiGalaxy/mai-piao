package com.maipiao.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.movie.entity.SessionSeat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 座位账本持久化。
 *
 * <p>那三个 UPDATE 是座位层面的并发边界。每一条都是单条带条件的 UPDATE，由调用方
 * 断言它影响的行数；{@code lock_order_no} / {@code sold_order_no} 这两个条件是拦住
 * 过期补偿的关键 —— 免得它去碰一个后来已经卖给了别人的座位。
 */
@Mapper
public interface SessionSeatMapper extends BaseMapper<SessionSeat> {

    /** 批量插入，生成场次时按批调用。 */
    @org.apache.ibatis.annotations.Insert("""
            <script>
            INSERT INTO t_event_session_seat
              (id, session_id, seat_id, seat_index, row_num, col_num, seat_type,
               tier_id, status, version, create_time, update_time)
            VALUES
            <foreach collection="seats" item="s" separator=",">
              (#{s.id}, #{s.sessionId}, #{s.seatId}, #{s.seatIndex}, #{s.rowNum}, #{s.colNum},
               #{s.seatType}, #{s.tierId}, #{s.status}, 0, NOW(3), NOW(3))
            </foreach>
            </script>
            """)
    int batchInsert(@Param("seats") List<SessionSeat> seats);

    @Select("""
            SELECT COUNT(*) FROM t_event_session_seat WHERE session_id = #{sessionId}
            """)
    int countBySchedule(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT seat_index FROM t_event_session_seat
             WHERE session_id = #{sessionId} AND status = 2
            """)
    List<Integer> selectSoldIndexes(@Param("sessionId") Long sessionId);

    /** G1：可售 -> 锁定。用 status = 0 把关。 */
    @Update("""
            <script>
            UPDATE t_event_session_seat
               SET status = 1,
                   lock_order_no = #{orderNo},
                   lock_user_id = #{userId},
                   lock_expire_time = #{expireTime},
                   version = version + 1,
                   update_time = NOW(3)
             WHERE session_id = #{sessionId}
               AND seat_id IN
               <foreach collection="seatIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND status = 0
            </script>
            """)
    int lockSeats(@Param("sessionId") Long sessionId,
                  @Param("seatIds") List<String> seatIds,
                  @Param("orderNo") String orderNo,
                  @Param("userId") Long userId,
                  @Param("expireTime") LocalDateTime expireTime);

    /**
     * G2：锁定 -> 已售。
     *
     * <p>{@code lock_order_no = #{orderNo}} 不是摆设：没有它，一个在占位过期并被
     * 重新卖出之后才到的支付，会把别人的座位标成这个订单的。
     */
    @Update("""
            <script>
            UPDATE t_event_session_seat
               SET status = 2,
                   sold_order_no = #{orderNo},
                   sold_time = NOW(3),
                   lock_order_no = NULL,
                   lock_user_id = NULL,
                   lock_expire_time = NULL,
                   version = version + 1,
                   update_time = NOW(3)
             WHERE session_id = #{sessionId}
               AND seat_id IN
               <foreach collection="seatIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND status = 1
               AND lock_order_no = #{orderNo}
            </script>
            """)
    int markSold(@Param("sessionId") Long sessionId,
                 @Param("seatIds") List<String> seatIds,
                 @Param("orderNo") String orderNo);

    /** 取消 / 超时 / G1 回滚：锁定 -> 可售。 */
    @Update("""
            <script>
            UPDATE t_event_session_seat
               SET status = 0,
                   lock_order_no = NULL,
                   lock_user_id = NULL,
                   lock_expire_time = NULL,
                   version = version + 1,
                   update_time = NOW(3)
             WHERE session_id = #{sessionId}
               AND seat_id IN
               <foreach collection="seatIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND status = 1
               AND lock_order_no = #{orderNo}
            </script>
            """)
    int releaseLockedSeats(@Param("sessionId") Long sessionId,
                           @Param("seatIds") List<String> seatIds,
                           @Param("orderNo") String orderNo);

    /** G3 退款：已售 -> 可售，且只对买下它们的那个订单生效。 */
    @Update("""
            <script>
            UPDATE t_event_session_seat
               SET status = 0,
                   sold_order_no = NULL,
                   sold_time = NULL,
                   version = version + 1,
                   update_time = NOW(3)
             WHERE session_id = #{sessionId}
               AND seat_id IN
               <foreach collection="seatIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND status = 2
               AND sold_order_no = #{orderNo}
            </script>
            """)
    int releaseSoldSeats(@Param("sessionId") Long sessionId,
                         @Param("seatIds") List<String> seatIds,
                         @Param("orderNo") String orderNo);

    /**
     * 超时清扫：释放已经过了到期时间的占位。
     *
     * @return 释放掉的座位数
     */
    @Update("""
            UPDATE t_event_session_seat
               SET status = 0,
                   lock_order_no = NULL,
                   lock_user_id = NULL,
                   lock_expire_time = NULL,
                   version = version + 1,
                   update_time = NOW(3)
             WHERE session_id = #{sessionId}
               AND status = 1
               AND lock_expire_time < #{now}
            """)
    int releaseExpiredLocks(@Param("sessionId") Long sessionId,
                            @Param("now") LocalDateTime now);
}
