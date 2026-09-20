package com.maipiao.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 映射 {@code maipiao_movie.t_event_session} —— 一场排片。
 *
 * <p>库存模型：{@code total_seat = locked_seat + sold_seat + remaining}。三者一起
 * 更新，从不「先读再写」，防超卖关卡就是一条带条件的 UPDATE：
 *
 * <pre>
 * UPDATE t_event_session
 *    SET locked_seat = locked_seat + N
 *  WHERE id = ? AND status = 1
 *    AND locked_seat + sold_seat + N &lt;= total_seat
 * </pre>
 *
 * 调用方必须断言恰好有一行被改动。先把计数器读进 Java 再在那里做判断，会把这条语句
 * 刻意避开的竞态重新引回来。
 */
@Data
@TableName("t_event_session")
public class Session {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_SCREENING = 2;
    public static final int STATUS_FINISHED = 3;
    public static final int STATUS_CANCELLED = 4;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;

    private Long venueId;

    private Long placeId;

    /** 将来给订单数据用的分片键。 */
    private LocalDate showDate;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal price;

    private Integer totalSeat;

    private Integer lockedSeat;

    private Integer soldSeat;

    private Integer status;

    /** 1 = 抢购，选座之前必须先过排队准入。 */
    private Integer rushMode;

    private LocalDateTime rushStartTime;

    /**
     * 谁选座：0 = 买家，1 = 系统。
     *
     * <p>和场馆的 {@code seating_mode} 不是一回事，那个说的是这个场地到底有没有固定
     * 座位。一个对号入座的体育场既有座位、又由系统分配 —— 这是销售策略，所以它属于
     * 场次。
     */
    private Integer seatMode;

    /**
     * DEMO 或 ADMIN —— 这个场次是谁建的。
     *
     * <p>生成器的重置在写新数据之前会删掉所有场次，在它是唯一创建者时这没问题。有了
     * 后台界面之后，那次重置会一声不吭地删掉别人的劳动成果。现在重置只清自己的。
     */
    private String source;

    // ---- 入场规则 ----
    //
    // 一场演出在固定时间开票，并限制一个人能买几张；电影两样都不做。默认值让电影的
    // 行为保持不变，这样下游不必在动作之前先查一遍类型。

    /** 开票时间。NULL 表示已经开票。 */
    private LocalDateTime saleStartTime;

    /** 每单最多几张票。0 表示不限。 */
    private Integer purchaseLimit;

    /** 1 = 每张票都必须填一个观演人。 */
    private Integer requireRealName;

    /** 还没开票时为 true。 */
    public boolean isSaleNotStarted() {
        return saleStartTime != null && saleStartTime.isAfter(LocalDateTime.now());
    }

    /** 结算时需要实名信息时为 true。 */
    public boolean needsRealName() {
        return requireRealName != null && requireRealName == 1;
    }

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 此刻还能选的座位。 */
    public int remainingSeats() {
        int total = totalSeat == null ? 0 : totalSeat;
        int locked = lockedSeat == null ? 0 : lockedSeat;
        int sold = soldSeat == null ? 0 : soldSeat;
        return Math.max(0, total - locked - sold);
    }
}
