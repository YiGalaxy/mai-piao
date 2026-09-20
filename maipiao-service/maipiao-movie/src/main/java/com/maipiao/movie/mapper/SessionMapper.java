package com.maipiao.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.movie.dto.SessionVO;
import com.maipiao.movie.entity.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * Session persistence.
 *
 * <p>The UPDATE statements at the bottom are concurrency boundaries, not
 * convenience wrappers. Each is a single conditional UPDATE whose affected row
 * count the caller asserts - reading the counters first and deciding in Java
 * would let two requests both see "2 seats left" and both proceed.
 *
 * <p>Column note: the queries select {@code s.*} plus explicitly aliased join
 * columns rather than a named column list. MyBatis maps the extra columns by
 * name and ignores the rest, and it avoids needing the column list to be
 * interpolated into the SQL as a string.
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /** Join columns shared by the list and detail queries. */
    String JOIN_AND_LABELS = """
              FROM t_event_session s
              JOIN t_event_project   f ON f.id = s.project_id
              JOIN t_event_venue c ON c.id = s.venue_id
              JOIN t_event_place   h ON h.id = s.place_id
            """;

    /**
     * Screenings on a given date, optionally narrowed to one film or cinema.
     *
     * <p>Only on-sale screenings are returned: one that has not opened yet, or
     * has already finished, is not something a buyer can act on.
     */
    @Select("""
            <script>
            SELECT s.*,
                   f.title AS project_title, f.duration, f.poster_url,
                   c.name AS venue_name,
                   h.name AS place_name, h.place_type,
                   (s.total_seat - s.locked_seat - s.sold_seat) AS remaining_seat
            """ + JOIN_AND_LABELS + """
             WHERE s.status = 1
               AND s.show_date = #{showDate}
               <if test="projectId != null">   AND s.project_id   = #{projectId}   </if>
               <if test="venueId != null"> AND s.venue_id = #{venueId} </if>
             ORDER BY s.start_time, s.id
            </script>
            """)
    List<SessionVO> selectScheduleList(@Param("projectId") Long projectId,
                                        @Param("venueId") Long venueId,
                                        @Param("showDate") LocalDate showDate);

    @Select("""
            SELECT s.*,
                   f.title AS project_title, f.duration, f.poster_url,
                   c.name AS venue_name,
                   h.name AS place_name, h.place_type,
                   (s.total_seat - s.locked_seat - s.sold_seat) AS remaining_seat
            """ + JOIN_AND_LABELS + """
             WHERE s.id = #{sessionId}
            """)
    SessionVO selectScheduleDetail(@Param("sessionId") Long sessionId);

    /**
     * Film ids that actually have a screening at this cinema on this date, so
     * the cinema page shows only what is bookable rather than the whole
     * catalogue.
     */
    @Select("""
            SELECT DISTINCT s.project_id
              FROM t_event_session s
             WHERE s.venue_id = #{venueId}
               AND s.show_date = #{showDate}
               AND s.status = 1
            """)
    List<Long> selectProjectIdsWithScreening(@Param("venueId") Long venueId,
                                          @Param("showDate") LocalDate showDate);

    // ------------------------------------------------------------
    // G1 branch - occupy inventory while an order is being placed
    // ------------------------------------------------------------

    /**
     * Reserves {@code count} seats against a screening's inventory.
     *
     * <p>The {@code locked + sold + count <= total} predicate is the
     * anti-oversell guard. Two concurrent orders can both reach this statement;
     * InnoDB serialises them on the row and the second gets 0, which the caller
     * must treat as "sold out".
     *
     * @return 1 when reserved, 0 when it would oversell or the screening is no
     *         longer on sale
     */
    @Update("""
            UPDATE t_event_session
               SET locked_seat = locked_seat + #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND status = 1
               AND locked_seat + sold_seat + #{count} <= total_seat
            """)
    int occupySeats(@Param("sessionId") Long sessionId, @Param("count") int count);

    /**
     * Moves seats from locked to sold once payment succeeds (G2).
     *
     * <p>Guarded on {@code locked_seat >= count}: if the locks were already
     * released by the timeout job, this returns 0 and the caller must not issue
     * tickets for seats that are no longer held.
     */
    @Update("""
            UPDATE t_event_session
               SET locked_seat = locked_seat - #{count},
                   sold_seat   = sold_seat   + #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND locked_seat >= #{count}
            """)
    int confirmSold(@Param("sessionId") Long sessionId, @Param("count") int count);

    /**
     * Gives reserved seats back - on cancellation, on timeout, or from a G1
     * rollback.
     *
     * <p>The {@code locked_seat >= count} guard keeps the counter from going
     * negative if a compensation runs twice.
     */
    @Update("""
            UPDATE t_event_session
               SET locked_seat = locked_seat - #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND locked_seat >= #{count}
            """)
    int releaseLocked(@Param("sessionId") Long sessionId, @Param("count") int count);

    /**
     * Gives <em>sold</em> seats back, for a refund that returns them to the
     * pool.
     *
     * <p>Distinct from {@link #releaseLocked}: those seats were never paid for,
     * so only the locked counter moves. These were, so only the sold counter
     * moves. Using the wrong one silently corrupts the remaining-seat
     * arithmetic - the seat map would show the seat as free while the sold
     * counter still claims it.
     */
    @Update("""
            UPDATE t_event_session
               SET sold_seat = sold_seat - #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND sold_seat >= #{count}
            """)
    int releaseSold(@Param("sessionId") Long sessionId, @Param("count") int count);
}
