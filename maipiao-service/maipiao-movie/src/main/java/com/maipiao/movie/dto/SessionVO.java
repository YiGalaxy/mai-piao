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
public class SessionVO {

    private Long id;

    private Long projectId;
    private String projectTitle;

    /** MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL. */
    private String category;
    private Integer duration;
    private String posterUrl;

    private Long venueId;
    private String venueName;

    private Long placeId;
    private String placeName;
    private String placeType;

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

    /** 0 = the buyer picks a seat, 1 = the system assigns from a price band. */
    private Integer seatMode;

    /** When tickets open, for a screening that is not on sale yet. */
    private LocalDateTime saleStartTime;

    /** Derived, not stored: total - locked - sold, floored at zero. */
    private Integer remainingSeat;
}
