package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Maps {@code maipiao_movie.t_movie_film}. */
@Data
@TableName("t_movie_film")
public class Film {

    public static final int STATUS_UPCOMING = 0;
    public static final int STATUS_NOW_SHOWING = 1;
    public static final int STATUS_OFFLINE = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String enName;

    private String posterUrl;

    private String director;

    /** Comma-separated cast, denormalised for list display. */
    private String actors;

    /** Runtime in minutes. */
    private Integer duration;

    /** Genre tags, comma separated. */
    private String filmType;

    private LocalDate releaseDate;

    /** 0.0 - 9.9; 0.0 means "not rated yet". */
    private BigDecimal score;

    /** {@link #STATUS_UPCOMING} / {@link #STATUS_NOW_SHOWING} / {@link #STATUS_OFFLINE}. */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
