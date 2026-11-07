package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 每场一个座位账本行；Redis 保存实时锁定状态。seatIndex 是固定 bitmap 偏移量。 */
@Data
@TableName("t_event_session_seat")
public class SessionSeat {

    public static final int STATUS_AVAILABLE = 0;
    public static final int STATUS_LOCKED = 1;
    public static final int STATUS_SOLD = 2;

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_COUPLE = 1;
    public static final int TYPE_ACCESSIBLE = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;

    /** 展示用座位编号，如 {@code 3_5}。 */
    private String seatId;

    /** 固定的 Redis bitmap 偏移量。 */
    private Integer seatIndex;

    private Integer rowNum;

    private Integer colNum;

    private Integer seatType;

    /** 创建场次时确定的票价档，座位图和下单均使用它。 */
    private Long tierId;

    private Integer status;

    private String lockOrderNo;

    private Long lockUserId;

    private LocalDateTime lockExpireTime;

    private String soldOrderNo;

    private LocalDateTime soldTime;

    /** 状态变更版本号。 */
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
