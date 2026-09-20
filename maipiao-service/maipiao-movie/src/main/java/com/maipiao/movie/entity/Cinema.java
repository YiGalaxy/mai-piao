package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Maps {@code maipiao_movie.t_event_venue}. */
@Data
@TableName("t_event_venue")
public class Cinema {

    public static final int STATUS_CLOSED = 0;
    public static final int STATUS_OPEN = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /**
     * CINEMA / GYMNASIUM / LIVEHOUSE / STADIUM / THEATER.
     *
     * <p>Unmapped until now, which meant the admin screen could not tell a
     * stadium from a cinema screen when offering somewhere to put a show on.
     * The class is called Cinema because films came first; the table has
     * always held every kind of venue, and this column is how it says which.
     */
    private String venueType;

    private String address;

    /** Used for filtering ("which cinemas are near me"). */
    private String district;

    private String phone;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
