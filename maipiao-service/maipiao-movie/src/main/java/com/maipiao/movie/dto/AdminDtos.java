package com.maipiao.movie.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Shapes for the admin screen.
 *
 * <p>Separate from the public DTOs because the two answer different questions.
 * A buyer asks what is on; an administrator says what will be on, and needs to
 * be able to state a date, a venue and a price rather than pick from a list
 * somebody else generated.
 *
 * <p>This is the piece the demo generator was standing in for. A performance
 * is announced - one night, one venue, months ahead - and there was no way to
 * say that, so the generator said it instead and said it wrong: a concert
 * booked twelve times in a day, at six venues, for a week.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * Creates the thing being sold, before any date is attached.
     *
     * <p>{@code category} decides almost everything downstream: whether the
     * poster says 导演 or 艺人, whether the sessions are called 排片 or 场次,
     * and whether a seat map is offered at all.
     */
    public record CreateProjectRequest(
            @NotBlank(message = "标题不能为空") String title,
            String enTitle,
            @NotBlank(message = "类型不能为空") String category,
            /** Concerts and stage shows; films use director and actors instead. */
            String artist,
            String organizer,
            String director,
            String actors,
            String tags,
            String posterUrl,
            String description,
            Integer duration,
            LocalDate showDate
    ) {
    }

    /**
     * One price band.
     *
     * <p>Row ranges, not seat lists, because that is how a venue sells: "rows
     * 1 to 8 are the VIP block". {@code rowEnd} of 0 means to the end of the
     * hall, so the last band does not have to know how many rows there are.
     */
    public record TierSpec(
            @NotBlank(message = "票档名不能为空") String name,
            @NotNull(message = "票价不能为空") @Positive(message = "票价必须为正") BigDecimal price,
            @NotNull(message = "起始排不能为空") Integer rowStart,
            Integer rowEnd,
            String color
    ) {
    }

    /**
     * Puts a project on sale at a place and a time.
     *
     * <p>Every field a cinema would derive from a repeating schedule is stated
     * here instead, because a performance has no schedule to derive from - it
     * has a date. One call creates one night.
     *
     * <p>{@code tierSpecs} is required rather than defaulted. A session with no
     * bands would price every seat at nothing, and a free concert is a mistake
     * far more often than it is an intention.
     */
    public record CreateSessionRequest(
            @NotNull(message = "项目不能为空") Long projectId,
            @NotNull(message = "场馆不能为空") Long placeId,
            @NotNull(message = "日期不能为空") LocalDate showDate,
            @NotNull(message = "开演时间不能为空") LocalTime startTime,
            /** 0 = the buyer picks a seat, 1 = the system assigns. Null means 0. */
            Integer seatMode,
            /** 0 means no limit beyond the platform ceiling. */
            Integer purchaseLimit,
            Integer requireRealName,
            /** 1 puts the sale behind a queue. */
            Integer rushMode,
            /** When the queue opens; required when rushMode is 1. */
            LocalDateTime rushStartTime,
            /** When tickets open; null means immediately. */
            LocalDateTime saleStartTime,
            @NotEmpty(message = "至少需要一个票档") @Valid List<TierSpec> tierSpecs
    ) {
    }

    /**
     * A performance with the dates it plays.
     *
     * <p>Returned as a group because that is what an administrator thinks in:
     * the show, and when it is on. The flat session list is available too, but
     * it answers a scheduling question rather than an editorial one.
     */
    public record ProjectSummary(
            Long id,
            String title,
            String category,
            String artist,
            LocalDate showDate,
            Integer status,
            int sessionCount
    ) {
    }

    /** What creating a session produced, so the caller can check it. */
    public record SessionCreated(
            Long sessionId,
            Long placeId,
            String venueName,
            String placeName,
            LocalDate showDate,
            LocalTime startTime,
            int totalSeat,
            int tierCount
    ) {
    }
}
