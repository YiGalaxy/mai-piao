package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_movie.t_event_session_seat} —— 每场排片每个座位一行。这是持久
 * 账本；Redis 里放的是实时可售状态。
 *
 * <p>状态流转及其守卫，每一个都必须断言受影响的行数：
 * <pre>
 *   0 可售 --occupy--> 1 锁定    WHERE status = 0
 *   1 锁定 --sold----> 2 已售    WHERE status = 1 AND lock_order_no = ?
 *   2 已售 --refund--> 0 可售    WHERE status = 2 AND sold_order_no = ?
 * </pre>
 *
 * <p>{@code seatIndex} 是 Redis bitmap 的偏移量。它在生成排期时被赋一次值，在场馆
 * 所有可售座位上是连续的。它从不按排和列重算，因为过道列会让那个算式不再连续，而
 * 任何偏移都会无声地搞坏选座。
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

    /** 给人看的，"{row}_{col}"。 */
    private String seatId;

    /** 连续的 bitmap 偏移量。只赋一次值，从不重算。 */
    private Integer seatIndex;

    private Integer rowNum;

    private Integer colNum;

    private Integer seatType;

    /**
     * 这个座位所属的票价档。
     *
     * <p>在场次生成时赋一次值，之后从不按排重算。座位图按它着色，下单按它定价，所以
     * 改成每次请求再推导，意味着同一个座位会因为在哪条代码路径上被问到而卖出不同的
     * 价格。
     */
    private Long tierId;

    private Integer status;

    private String lockOrderNo;

    private Long lockUserId;

    private LocalDateTime lockExpireTime;

    private String soldOrderNo;

    private LocalDateTime soldTime;

    /** 乐观锁计数器，每次状态变化都自增。 */
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
