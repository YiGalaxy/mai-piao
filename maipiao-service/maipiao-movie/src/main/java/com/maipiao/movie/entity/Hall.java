package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_movie.t_movie_hall}.
 *
 * <p>{@code seatTemplate} is the raw JSON layout. It is parsed once, when a
 * schedule is generated, into concrete seat rows - never at request time.
 */
@Data
@TableName("t_movie_hall")
public class Hall {

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ACTIVE = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long cinemaId;

    private String name;

    /** NORMAL / IMAX / 3D / VIP - drives pricing and display. */
    private String hallType;

    private Integer rowCount;

    private Integer colCount;

    /** JSON seat layout, see docs/sql/02_schema_movie.sql for the shape. */
    private String seatTemplate;

    /** Sellable seats after aisles and broken seats are removed. */
    private Integer seatCount;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
