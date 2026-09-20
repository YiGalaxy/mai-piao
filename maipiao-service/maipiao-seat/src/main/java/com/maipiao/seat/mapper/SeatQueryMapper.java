package com.maipiao.seat.mapper;

import com.maipiao.seat.dto.SeatMapVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对 {@code maipiao_event} 的只读查询。
 *
 * <p>seat-service 读这个库，但从不写它 —— 账本由 movie-service 在全局事务里更新。
 * 把读操作放在这里，是为了在延迟敏感的座位图路径上省掉一次 Feign 跳转。
 */
@Mapper
public interface SeatQueryMapper {

    /**
     * 放映场次，外加它所在厅的几何模板。
     *
     * <p>座位图会读的每一列都必须在这里被选出来。曾经有四列没选 ——
     * {@code seatingMode}、{@code purchaseLimit}、{@code requireRealName}、
     * {@code saleStartTime} —— 而座位图却从一个根本不在结果里的 key 去取值，于是它们
     * 拿回来是 null；只要还没有任何东西依赖它们，这几个字段就默不作声地表示"无限制"。
     */
    @Select("""
            SELECT s.id                AS sessionId,
                   s.start_time        AS startTime,
                   s.price             AS price,
                   s.total_seat        AS totalSeat,
                   s.locked_seat       AS lockedSeat,
                   s.sold_seat         AS soldSeat,
                   s.status            AS status,
                   s.rush_mode         AS rushMode,
                   s.rush_start_time   AS rushStartTime,
                   s.seat_mode         AS seatMode,
                   s.sale_start_time   AS saleStartTime,
                   s.purchase_limit    AS purchaseLimit,
                   s.require_real_name AS requireRealName,
                   f.title           AS projectTitle,
                   c.name            AS venueName,
                   h.name            AS placeName,
                   h.place_type      AS placeType,
                   h.seating_mode    AS seatingMode,
                   h.row_count       AS rowCount,
                   h.col_count       AS colCount,
                   h.seat_template   AS seatTemplate
              FROM t_event_session s
              JOIN t_event_project f ON f.id = s.project_id
              JOIN t_event_venue   c ON c.id = s.venue_id
              JOIN t_event_place   h ON h.id = s.place_id
             WHERE s.id = #{sessionId}
            """)
    Map<String, Object> selectScheduleDetail(@Param("sessionId") Long sessionId);

    /**
     * 场次的座位布局：每个座位的位置和种类。
     *
     * <p>状态是刻意不选的 —— 那来自 Redis。
     */
    @Select("""
            SELECT seat_id    AS seatId,
                   seat_index AS seatIndex,
                   row_num    AS rowNum,
                   col_num    AS colNum,
                   seat_type  AS seatType,
                   tier_id    AS tierId
              FROM t_event_session_seat
             WHERE session_id = #{sessionId}
             ORDER BY seat_index
            """)
    List<Map<String, Object>> selectSeatLayout(@Param("sessionId") Long sessionId);

    /**
     * 这些座位值多少钱，按各自所在的票档算。
     *
     * <p>这才是订单价格的权威来源，放在这里是因为座位到票档的映射本来就在这里。场次
     * 自己的 {@code price} 列只是个挂牌数字 —— "¥580 起" —— 拿它当订单总价，会让 VIP
     * 座和站席收一样的钱；一场原本有四个票档的演唱会最后只剩一个价格，就是这么来的。
     *
     * <p>用 LEFT JOIN，并由调用方断言返回行数：没有票档的座位会带着 0 价格返回，而不是
     * 凭空消失，这样数据上的窟窿是看得见的，而不是默默把总价缩小。
     */
    @Select("""
            <script>
            SELECT s.seat_index        AS seatIndex,
                   s.tier_id           AS tierId,
                   COALESCE(t.price, 0) AS price
              FROM t_event_session_seat s
              LEFT JOIN t_event_price_tier t ON t.id = s.tier_id
             WHERE s.session_id = #{sessionId}
               AND s.seat_index IN
               <foreach collection="seatIndexes" item="i" open="(" separator="," close=")">#{i}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectSeatPrices(@Param("sessionId") Long sessionId,
                                               @Param("seatIndexes") List<Integer> seatIndexes);

    /**
     * 场次的票档。
     *
     * 电影场次恰好只有一个；演出有多个。座位图按它着色，座位价格也是经由它的票档查
     * 出来的，而不是从场次上取的。
     */
    @Select("""
            SELECT id,
                   name,
                   price,
                   color
              FROM t_event_price_tier
             WHERE session_id = #{scheduleId}
             ORDER BY row_start
            """)
    List<Map<String, Object>> selectTiers(@Param("scheduleId") Long scheduleId);

    /** 账本上记载为已锁或已售的座位 —— bitmap 重建的输入。 */
    @Select("""
            SELECT seat_index AS seatIndex,
                   status     AS status,
                   COALESCE(sold_order_no, lock_order_no) AS orderNo
              FROM t_event_session_seat
             WHERE session_id = #{sessionId}
               AND status <> 0
            """)
    List<Map<String, Object>> selectOccupiedSeats(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT price FROM t_event_session WHERE id = #{sessionId}
            """)
    BigDecimal selectPrice(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT start_time FROM t_event_session WHERE id = #{sessionId}
            """)
    LocalDateTime selectStartTime(@Param("sessionId") Long sessionId);
}
