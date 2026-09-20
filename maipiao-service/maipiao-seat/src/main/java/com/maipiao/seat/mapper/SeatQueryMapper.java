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
            SELECT s.id             AS sessionId,
                   s.start_time     AS startTime,
                   s.price          AS price,
                   s.total_seat     AS totalSeat,
                   s.locked_seat    AS lockedSeat,
                   s.sold_seat      AS soldSeat,
                   s.status         AS status,
                   s.rush_mode      AS rushMode,
                   f.title           AS projectTitle,
                   c.name           AS venueName,
                   h.name           AS placeName,
                   h.place_type      AS placeType,
                   h.row_count      AS rowCount,
                   h.col_count      AS colCount,
                   h.seat_template  AS seatTemplate
              FROM t_event_session s
              JOIN t_event_project   f ON f.id = s.project_id
              JOIN t_event_venue c ON c.id = s.venue_id
              JOIN t_event_place   h ON h.id = s.place_id
             WHERE s.id = #{sessionId}
            """)
    Map<String, Object> selectScheduleDetail(@Param("sessionId") Long sessionId);

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
                   seat_type  AS seatType,
                   tier_id    AS tierId
              FROM t_event_session_seat
             WHERE session_id = #{sessionId}
             ORDER BY seat_index
            """)
    List<Map<String, Object>> selectSeatLayout(@Param("sessionId") Long sessionId);

    /**
     * Price bands for a session.
     *
     * A film session has exactly one; a performance has several. The map is
     * coloured from this, and the price of a seat is looked up through its
     * tier rather than taken from the session.
     */
    @Select("""
            SELECT id,
                   name,
                   price,
                   color
              FROM t_event_price_tier
             WHERE session_id = #{scheduleId}
             ORDER BY row_start
            """)
    List<Map<String, Object>> selectTiers(@Param("scheduleId") Long scheduleId);

    /** Seats the ledger says are locked or sold - the input to a bitmap rebuild. */
    @Select("""
            SELECT seat_index AS seatIndex,
                   status     AS status,
                   COALESCE(sold_order_no, lock_order_no) AS orderNo
              FROM t_event_session_seat
             WHERE session_id = #{sessionId}
               AND status <> 0
            """)
    List<Map<String, Object>> selectOccupiedSeats(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT price FROM t_event_session WHERE id = #{sessionId}
            """)
    BigDecimal selectPrice(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT start_time FROM t_event_session WHERE id = #{sessionId}
            """)
    LocalDateTime selectStartTime(@Param("sessionId") Long sessionId);
}
