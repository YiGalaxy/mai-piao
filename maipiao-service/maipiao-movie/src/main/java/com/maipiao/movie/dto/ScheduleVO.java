package com.maipiao.movie.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A screening joined with the labels a client needs to render it.
 *
 * <p>The joins happen in one query rather than as follow-up calls, because a
 * schedule list is read constantly (every cinema page, every film page) and
 * the alternative is an N+1 across three tables for data that never changes
 * during the request.
 *
 * <p>Seats are exposed as counts only - the per-seat detail lives in Redis and
 * is fetched separately when the user actually opens the seat map.
 */
@Data
public class ScheduleVO {

    private Long id;

    private Long filmId;
    private String filmName;
    private Integer duration;
    private String posterUrl;

    private Long cinemaId;
    private String cinemaName;

    private Long hallId;
    private String hallName;
    private String hallType;

    private LocalDate showDate;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private BigDecimal price;

    private Integer totalSeat;
    private Integer lockedSeat;
    private Integer soldSeat;

    private Integer status;
    private Integer rushMode;
    private LocalDateTime rushStartTime;

    /** Derived, not stored: total - locked - sold, floored at zero. */
    private Integer remainingSeat;
}
