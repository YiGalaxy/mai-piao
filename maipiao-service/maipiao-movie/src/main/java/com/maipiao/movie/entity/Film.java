package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_event.t_event_project} - anything that is ticketed.
 *
 * <p>The class is still called Film because every reference to it predates the
 * performances being added, and renaming it is a separate mechanical change
 * from renaming the table. What matters is that the table no longer claims a
 * concert is a film.
 *
 * <p>The film-specific and performance-specific columns coexist and are empty
 * for the other kind. {@code category} is what says which set to read; without
 * it, a caller cannot tell a blank director from a missing one.
 */
@Data
@TableName("t_event_project")
public class Film {

    public static final int STATUS_UPCOMING = 0;
    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_CLOSED = 2;

    // ---- categories ----
    public static final String CATEGORY_MOVIE = "MOVIE";
    public static final String CATEGORY_CONCERT = "CONCERT";
    public static final String CATEGORY_TALK_SHOW = "TALK_SHOW";
    public static final String CATEGORY_THEATER = "THEATER";
    public static final String CATEGORY_MUSICAL = "MUSICAL";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL. */
    private String category;

    /** Film title or show name. Column is {@code title}. */
    private String title;

    private String enTitle;

    private String posterUrl;

    /** Runtime in minutes. */
    private Integer duration;

    /** Genre or style, comma separated. Column is {@code tags}. */
    private String tags;

    /** Release date for a film, opening date for a run. */
    private LocalDate showDate;

    /** 0.0 - 9.9; 0.0 means "not rated yet". */
    private BigDecimal score;

    /** {@link #STATUS_UPCOMING} / {@link #STATUS_ON_SALE} / {@link #STATUS_CLOSED}. */
    private Integer status;

    // ---- film ----

    private String director;

    /** Comma-separated cast, denormalised for list display. */
    private String actors;

    // ---- performance ----

    /** Headline act or lead performer. */
    private String artist;

    /** Presenting company. */
    private String organizer;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** True when this is a live performance rather than a film. */
    public boolean isPerformance() {
        return category != null && !CATEGORY_MOVIE.equals(category);
    }
}
