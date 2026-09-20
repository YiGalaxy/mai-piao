package com.maipiao.seat.mapper;

import com.maipiao.seat.dto.SeatMapVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only queries against {@code maipiao_movie}.
 *
 * <p>seat-service reads this schema but never writes it - the ledger is
 * updated by movie-service during the global transactions. Keeping the reads
 * here avoids a Feign hop on the latency-critical seat map path.
 */
@Mapper
public interface SeatQueryMapper {

    /** Screening plus its hall geometry template. */
    @Select("""
            SELECT s.id             AS scheduleId,
                   s.start_time     AS startTime,
                   s.price          AS price,
                   s.total_seat     AS totalSeat,
                   s.locked_seat    AS lockedSeat,
                   s.sold_seat      AS soldSeat,
                   s.status         AS status,
                   s.rush_mode      AS rushMode,
                   f.name           AS filmName,
                   c.name           AS cinemaName,
                   h.name           AS hallName,
                   h.hall_type      AS hallType,
                   h.row_count      AS rowCount,
                   h.col_count      AS colCount,
                   h.seat_template  AS seatTemplate
              FROM t_movie_schedule s
              JOIN t_movie_film   f ON f.id = s.film_id
              JOIN t_movie_cinema c ON c.id = s.cinema_id
              JOIN t_movie_hall   h ON h.id = s.hall_id
             WHERE s.id = #{scheduleId}
            """)
    Map<String, Object> selectScheduleDetail(@Param("scheduleId") Long scheduleId);

    /**
     * The seat layout of a screening: position and kind of every seat.
     *
     * <p>State is deliberately not selected - that comes from Redis.
     */
    @Select("""
            SELECT seat_id    AS seatId,
                   seat_index AS seatIndex,
                   row_num    AS rowNum,
                   col_num    AS colNum,
                   seat_type  AS seatType
              FROM t_movie_schedule_seat
             WHERE schedule_id = #{scheduleId}
             ORDER BY seat_index
            """)
    List<Map<String, Object>> selectSeatLayout(@Param("scheduleId") Long scheduleId);

    /** Seats the ledger says are locked or sold - the input to a bitmap rebuild. */
    @Select("""
            SELECT seat_index AS seatIndex,
                   status     AS status,
                   COALESCE(sold_order_no, lock_order_no) AS orderNo
              FROM t_movie_schedule_seat
             WHERE schedule_id = #{scheduleId}
               AND status <> 0
            """)
    List<Map<String, Object>> selectOccupiedSeats(@Param("scheduleId") Long scheduleId);

    @Select("""
            SELECT price FROM t_movie_schedule WHERE id = #{scheduleId}
            """)
    BigDecimal selectPrice(@Param("scheduleId") Long scheduleId);

    @Select("""
            SELECT start_time FROM t_movie_schedule WHERE id = #{scheduleId}
            """)
    LocalDateTime selectStartTime(@Param("scheduleId") Long scheduleId);
}
