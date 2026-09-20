package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_event.t_event_project} —— 任何要卖票的东西。
 *
 * <p>类名还叫 Film，因为所有对它的引用都早于演出功能的加入，而改名和改表名是两件
 * 各自独立的机械操作。要紧的是这张表不再声称演唱会是电影了。
 *
 * <p>电影特有的列和演出特有的列并存，对另一种类型来说就是空的。是 {@code category}
 * 在说该读哪一组；没有它，调用方分不清「导演是空的」和「根本就没有导演这一项」。
 */
@Data
@TableName("t_event_project")
public class Film {

    public static final int STATUS_UPCOMING = 0;
    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_CLOSED = 2;

    // ---- 类型 ----
    public static final String CATEGORY_MOVIE = "MOVIE";
    public static final String CATEGORY_CONCERT = "CONCERT";
    public static final String CATEGORY_TALK_SHOW = "TALK_SHOW";
    public static final String CATEGORY_THEATER = "THEATER";
    public static final String CATEGORY_MUSICAL = "MUSICAL";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** MOVIE / CONCERT / TALK_SHOW / THEATER / MUSICAL。 */
    private String category;

    /** 影片标题或演出名称。字段是 {@code title}。 */
    private String title;

    private String enTitle;

    private String posterUrl;

    /** 时长，单位分钟。 */
    private Integer duration;

    /** 题材或风格，逗号分隔。字段是 {@code tags}。 */
    private String tags;

    /** 电影是上映日期，演出是开演日期。 */
    private LocalDate showDate;

    /** 0.0 - 9.9；0.0 表示「还没有评分」。 */
    private BigDecimal score;

    /** {@link #STATUS_UPCOMING} / {@link #STATUS_ON_SALE} / {@link #STATUS_CLOSED}。 */
    private Integer status;

    // ---- 电影 ----

    private String director;

    /** 逗号分隔的演员表，为了列表展示做了反范式。 */
    private String actors;

    // ---- 演出 ----

    /** 主打艺人或领衔主演。 */
    private String artist;

    /** 主办方。 */
    private String organizer;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 这是现场演出而不是电影时为 true。 */
    public boolean isPerformance() {
        return category != null && !CATEGORY_MOVIE.equals(category);
    }
}
