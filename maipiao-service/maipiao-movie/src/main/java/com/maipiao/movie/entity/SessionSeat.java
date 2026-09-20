package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Maps {@code maipiao_movie.t_event_session_seat} - one row per seat per
 * screening. This is the durable ledger; Redis holds live availability.
 *
 * <p>Status transitions and their guards, all of which must assert the
 * affected row count:
 * <pre>
 *   0 available --occupy--> 1 locked    WHERE status = 0
 *   1 locked    --sold----> 2 sold      WHERE status = 1 AND lock_order_no = ?
 *   2 sold      --refund--> 0 available WHERE status = 2 AND sold_order_no = ?
 * </pre>
 *
 * <p>{@code seatIndex} is the Redis bitmap offset. It is assigned once, when
 * the schedule is generated, and is contiguous across the hall's sellable
 * seats. It is never recomputed from row and column, because aisle columns
 * make the arithmetic non-contiguous and any drift would silently corrupt
 * seat selection.
 */
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

    /** Human readable, "{row}_{col}". */
    private String seatId;

    /** Contiguous bitmap offset. Assigned once, never recomputed. */
    private Integer seatIndex;

    private Integer rowNum;

    private Integer colNum;

    private Integer seatType;

    /**
     * Price band this seat belongs to.
     *
     * <p>Assigned once, when the session is generated, and never recomputed
     * from the row afterwards. The seat map colours by it and the order prices
     * by it, so deriving it per request would mean the same seat could be
     * priced differently depending on which code path asked.
     */
    private Long tierId;

    private Integer status;

    private String lockOrderNo;

    private Long lockUserId;

    private LocalDateTime lockExpireTime;

    private String soldOrderNo;

    private LocalDateTime soldTime;

    /** Optimistic lock counter, bumped on every state change. */
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
