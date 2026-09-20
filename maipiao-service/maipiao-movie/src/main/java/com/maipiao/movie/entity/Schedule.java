package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_movie.t_movie_schedule} - one screening.
 *
 * <p>Inventory model: {@code total_seat = locked_seat + sold_seat + remaining}.
 * The three are updated together, never read-then-written, and the
 * anti-oversell guard is a single conditional UPDATE:
 *
 * <pre>
 * UPDATE t_movie_schedule
 *    SET locked_seat = locked_seat + N
 *  WHERE id = ? AND status = 1
 *    AND locked_seat + sold_seat + N &lt;= total_seat
 * </pre>
 *
 * The caller must assert that exactly one row changed. Reading the counters
 * into Java first and deciding there would reintroduce the race this avoids.
 */
@Data
@TableName("t_movie_schedule")
public class Schedule {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_SCREENING = 2;
    public static final int STATUS_FINISHED = 3;
    public static final int STATUS_CANCELLED = 4;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long filmId;

    private Long cinemaId;

    private Long hallId;

    /** Future sharding key for order data. */
    private LocalDate showDate;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal price;

    private Integer totalSeat;

    private Integer lockedSeat;

    private Integer soldSeat;

    private Integer status;

    /** 1 = rush sale, which requires queue admission before seat selection. */
    private Integer rushMode;

    private LocalDateTime rushStartTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** Seats still available to pick right now. */
    public int remainingSeats() {
        int total = totalSeat == null ? 0 : totalSeat;
        int locked = lockedSeat == null ? 0 : lockedSeat;
        int sold = soldSeat == null ? 0 : soldSeat;
        return Math.max(0, total - locked - sold);
    }
}
