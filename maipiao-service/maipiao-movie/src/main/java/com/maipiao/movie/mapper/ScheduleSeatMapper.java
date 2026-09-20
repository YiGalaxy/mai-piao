package com.maipiao.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.movie.entity.ScheduleSeat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Seat ledger persistence.
 *
 * <p>The three UPDATEs are the seat-level concurrency boundaries. Each is a
 * single conditional UPDATE whose affected row count the caller asserts; the
 * {@code lock_order_no} / {@code sold_order_no} predicates are what stop a
 * stale compensation from touching a seat that has since been sold to
 * somebody else.
 */
@Mapper
public interface ScheduleSeatMapper extends BaseMapper<ScheduleSeat> {

    /** Bulk insert, called in batches during schedule generation. */
    @org.apache.ibatis.annotations.Insert("""
            <script>
            INSERT INTO t_movie_schedule_seat
              (id, schedule_id, seat_id, seat_index, row_num, col_num, seat_type,
               status, version, create_time, update_time)
            VALUES
            <foreach collection="seats" item="s" separator=",">
              (#{s.id}, #{s.scheduleId}, #{s.seatId}, #{s.seatIndex}, #{s.rowNum}, #{s.colNum},
               #{s.seatType}, #{s.status}, 0, NOW(3), NOW(3))
            </foreach>
            </script>
            """)
    int batchInsert(@Param("seats") List<ScheduleSeat> seats);

    @Select("""
            SELECT COUNT(*) FROM t_movie_schedule_seat WHERE schedule_id = #{scheduleId}
            """)
    int countBySchedule(@Param("scheduleId") Long scheduleId);

    @Select("""
            SELECT seat_index FROM t_movie_schedule_seat
             WHERE schedule_id = #{scheduleId} AND status = 2
            """)
    List<Integer> selectSoldIndexes(@Param("scheduleId") Long scheduleId);

    /** G1: available -> locked. Guarded on status = 0. */
    @Update("""
            <script>
            UPDATE t_movie_schedule_seat
               SET status = 1,
                   lock_order_no = #{orderNo},
                   lock_user_id = #{userId},
                   lock_expire_time = #{expireTime},
                   version = version + 1,
                   update_time = NOW(3)
             WHERE schedule_id = #{scheduleId}
               AND seat_id IN
               <foreach collection="seatIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND status = 0
            </script>
            """)
    int lockSeats(@Param("scheduleId") Long scheduleId,
                  @Param("seatIds") List<String> seatIds,
                  @Param("orderNo") String orderNo,
                  @Param("userId") Long userId,
                  @Param("expireTime") LocalDateTime expireTime);

    /**
     * G2: locked -> sold.
     *
     * <p>{@code lock_order_no = #{orderNo}} is not decoration: without it, a
     * payment arriving after the lock expired and was re-sold would mark
     * somebody else's seat as this order's.
     */
    @Update("""
            <script>
            UPDATE t_movie_schedule_seat
               SET status = 2,
                   sold_order_no = #{orderNo},
                   sold_time = NOW(3),
                   lock_order_no = NULL,
                   lock_user_id = NULL,
                   lock_expire_time = NULL,
                   version = version + 1,
                   update_time = NOW(3)
             WHERE schedule_id = #{scheduleId}
               AND seat_id IN
               <foreach collection="seatIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND status = 1
               AND lock_order_no = #{orderNo}
            </script>
            """)
    int markSold(@Param("scheduleId") Long scheduleId,
                 @Param("seatIds") List<String> seatIds,
                 @Param("orderNo") String orderNo);

    /** Cancel / timeout / G1 rollback: locked -> available. */
    @Update("""
            <script>
            UPDATE t_movie_schedule_seat
               SET status = 0,
                   lock_order_no = NULL,
                   lock_user_id = NULL,
                   lock_expire_time = NULL,
                   version = version + 1,
                   update_time = NOW(3)
             WHERE schedule_id = #{scheduleId}
               AND seat_id IN
               <foreach collection="seatIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND status = 1
               AND lock_order_no = #{orderNo}
            </script>
            """)
    int releaseLockedSeats(@Param("scheduleId") Long scheduleId,
                           @Param("seatIds") List<String> seatIds,
                           @Param("orderNo") String orderNo);

    /** G3 refund: sold -> available, only for the order that bought them. */
    @Update("""
            <script>
            UPDATE t_movie_schedule_seat
               SET status = 0,
                   sold_order_no = NULL,
                   sold_time = NULL,
                   version = version + 1,
                   update_time = NOW(3)
             WHERE schedule_id = #{scheduleId}
               AND seat_id IN
               <foreach collection="seatIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND status = 2
               AND sold_order_no = #{orderNo}
            </script>
            """)
    int releaseSoldSeats(@Param("scheduleId") Long scheduleId,
                         @Param("seatIds") List<String> seatIds,
                         @Param("orderNo") String orderNo);

    /**
     * Timeout sweep: releases locks whose expiry has passed.
     *
     * @return number of seats released
     */
    @Update("""
            UPDATE t_movie_schedule_seat
               SET status = 0,
                   lock_order_no = NULL,
                   lock_user_id = NULL,
                   lock_expire_time = NULL,
                   version = version + 1,
                   update_time = NOW(3)
             WHERE schedule_id = #{scheduleId}
               AND status = 1
               AND lock_expire_time < #{now}
            """)
    int releaseExpiredLocks(@Param("scheduleId") Long scheduleId,
                            @Param("now") LocalDateTime now);
}
