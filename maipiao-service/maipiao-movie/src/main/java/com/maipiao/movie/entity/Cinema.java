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
