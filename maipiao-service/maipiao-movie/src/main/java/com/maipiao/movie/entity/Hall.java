package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_movie.t_event_place}.
 *
 * <p>{@code seatTemplate} is the raw JSON layout. It is parsed once, when a
 * schedule is generated, into concrete seat rows - never at request time.
 */
@Data
@TableName("t_event_place")
public class Hall {

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ACTIVE = 1;

    public static final String SEATING_SEATED = "SEATED";
    public static final String SEATING_STANDING = "STANDING";
    public static final String SEATING_MIXED = "MIXED";

    /** True when tickets for this place are sold without a seat map. */
    public boolean isStanding() {
        return SEATING_STANDING.equals(seatingMode);
    }

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long venueId;

    private String name;

    /** NORMAL / IMAX / 3D / VIP / ARENA / THEATER / STUDIO / STANDING - drives pricing and display. */
    private String placeType;

    /**
     * SEATED / STANDING / MIXED.
     *
     * <p>This is what makes a standing-only concert expressible. Its bitmap is
     * still one bit per unit of capacity and its seat_index is still
     * contiguous, so locking, ordering and refunds need no special case - only
     * the seat map does, and it shows a counter instead of a grid.
     */
    private String seatingMode;

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
