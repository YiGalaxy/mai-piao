package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 映射 {@code maipiao_movie.t_event_venue}。 */
@Data
@TableName("t_event_venue")
public class Cinema {

    public static final int STATUS_CLOSED = 0;
    public static final int STATUS_OPEN = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /**
     * CINEMA / GYMNASIUM / LIVEHOUSE / STADIUM / THEATER。
     *
     * <p>在此之前一直没有映射，于是后台界面在提供「找个地方办演出」时，分不清体育场
     * 和影厅。类名叫 Cinema 是因为电影先来；这张表从来就装着各种场馆，而这一列才是
     * 它用来区分的方式。
     */
    private String venueType;

    private String address;

    /** 用来做筛选（「我附近有哪些影院」）。 */
    private String district;

    private String phone;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
