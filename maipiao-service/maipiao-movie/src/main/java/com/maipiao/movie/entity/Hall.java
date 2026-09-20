package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_movie.t_event_place}。
 *
 * <p>{@code seatTemplate} 是原始的 JSON 布局。它在生成排期时被解析一次，变成具体的
 * 座位行 —— 从不在请求时解析。
 */
@Data
@TableName("t_event_place")
public class Hall {

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ACTIVE = 1;

    public static final String SEATING_SEATED = "SEATED";
    public static final String SEATING_STANDING = "STANDING";
    public static final String SEATING_MIXED = "MIXED";

    /** 这个场地卖票时不提供座位图时为 true。 */
    public boolean isStanding() {
        return SEATING_STANDING.equals(seatingMode);
    }

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long venueId;

    private String name;

    /** NORMAL / IMAX / 3D / VIP / ARENA / THEATER / STUDIO / STANDING —— 决定定价和展示。 */
    private String placeType;

    /**
     * SEATED / STANDING / MIXED。
     *
     * <p>正是它让「只有站席的演唱会」这件事能被表达出来。它的 bitmap 仍然是每个容量
     * 单位一位，seat_index 也仍然连续，所以锁定、下单、退款都不需要特例 —— 只有座位图
     * 需要，它显示的是一个计数器而不是网格。
     */
    private String seatingMode;

    private Integer rowCount;

    private Integer colCount;

    /** JSON 座位布局，结构见 docs/sql/schema/04_event.sql。 */
    private String seatTemplate;

    /** 扣掉过道和坏座之后可卖的座位数。 */
    private Integer seatCount;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
