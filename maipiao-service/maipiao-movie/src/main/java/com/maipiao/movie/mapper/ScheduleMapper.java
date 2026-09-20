package com.maipiao.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.movie.dto.ScheduleVO;
import com.maipiao.movie.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * Schedule persistence.
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
public interface ScheduleMapper extends BaseMapper<Schedule> {

    /** Join columns shared by the list and detail queries. */
    String JOIN_AND_LABELS = """
              FROM t_movie_schedule s
              JOIN t_movie_film   f ON f.id = s.film_id
              JOIN t_movie_cinema c ON c.id = s.cinema_id
              JOIN t_movie_hall   h ON h.id = s.hall_id
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
                   f.name AS film_name, f.duration, f.poster_url,
                   c.name AS cinema_name,
                   h.name AS hall_name, h.hall_type,
                   (s.total_seat - s.locked_seat - s.sold_seat) AS remaining_seat
            """ + JOIN_AND_LABELS + """
             WHERE s.status = 1
               AND s.show_date = #{showDate}
               <if test="filmId != null">   AND s.film_id   = #{filmId}   </if>
               <if test="cinemaId != null"> AND s.cinema_id = #{cinemaId} </if>
             ORDER BY s.start_time, s.id
            </script>
            """)
    List<ScheduleVO> selectScheduleList(@Param("filmId") Long filmId,
                                        @Param("cinemaId") Long cinemaId,
                                        @Param("showDate") LocalDate showDate);

    @Select("""
            SELECT s.*,
                   f.name AS film_name, f.duration, f.poster_url,
                   c.name AS cinema_name,
                   h.name AS hall_name, h.hall_type,
                   (s.total_seat - s.locked_seat - s.sold_seat) AS remaining_seat
            """ + JOIN_AND_LABELS + """
             WHERE s.id = #{scheduleId}
            """)
    ScheduleVO selectScheduleDetail(@Param("scheduleId") Long scheduleId);

    /**
     * Film ids that actually have a screening at this cinema on this date, so
     * the cinema page shows only what is bookable rather than the whole
     * catalogue.
     */
    @Select("""
            SELECT DISTINCT s.film_id
              FROM t_movie_schedule s
             WHERE s.cinema_id = #{cinemaId}
               AND s.show_date = #{showDate}
               AND s.status = 1
            """)
    List<Long> selectFilmIdsWithScreening(@Param("cinemaId") Long cinemaId,
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
            UPDATE t_movie_schedule
               SET locked_seat = locked_seat + #{count},
                   update_time = NOW(3)
             WHERE id = #{scheduleId}
               AND status = 1
               AND locked_seat + sold_seat + #{count} <= total_seat
            """)
    int occupySeats(@Param("scheduleId") Long scheduleId, @Param("count") int count);

    /**
     * Moves seats from locked to sold once payment succeeds (G2).
     *
     * <p>Guarded on {@code locked_seat >= count}: if the locks were already
     * released by the timeout job, this returns 0 and the caller must not issue
     * tickets for seats that are no longer held.
     */
    @Update("""
            UPDATE t_movie_schedule
               SET locked_seat = locked_seat - #{count},
                   sold_seat   = sold_seat   + #{count},
                   update_time = NOW(3)
             WHERE id = #{scheduleId}
               AND locked_seat >= #{count}
            """)
    int confirmSold(@Param("scheduleId") Long scheduleId, @Param("count") int count);

    /**
     * Gives reserved seats back - on cancellation, on timeout, or from a G1
     * rollback.
     *
     * <p>The {@code locked_seat >= count} guard keeps the counter from going
     * negative if a compensation runs twice.
     */
    @Update("""
            UPDATE t_movie_schedule
               SET locked_seat = locked_seat - #{count},
                   update_time = NOW(3)
             WHERE id = #{scheduleId}
               AND locked_seat >= #{count}
            """)
    int releaseLocked(@Param("scheduleId") Long scheduleId, @Param("count") int count);
}
