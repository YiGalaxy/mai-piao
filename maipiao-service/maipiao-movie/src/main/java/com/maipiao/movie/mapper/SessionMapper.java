package com.maipiao.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.movie.dto.SessionVO;
import com.maipiao.movie.entity.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 场次持久化。
 *
 * <p>底部那些 UPDATE 语句是并发边界，不是图省事的包装。每一条都是单条带条件的
 * UPDATE，由调用方断言它影响的行数 —— 先把计数器读出来在 Java 里判断，会让两个请求
 * 都看到「还剩 2 个座」然后双双往下走。
 *
 * <p>关于选列：这些查询选 {@code s.*} 加上显式起别名的 join 列，而不是写一串列名。
 * MyBatis 会按名字映射多出来的列并忽略其余的，而且这样不必把列名清单当字符串拼进
 * SQL 里。
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /** 列表查询和详情查询共用的 join 部分。 */
    String JOIN_AND_LABELS = """
              FROM t_event_session s
              JOIN t_event_project   f ON f.id = s.project_id
              JOIN t_event_venue c ON c.id = s.venue_id
              JOIN t_event_place   h ON h.id = s.place_id
            """;

    /**
     * 某一天的排片，可以再收窄到某部影片或某个影院。
     *
     * <p>只返回在售的排片：还没开票的、或者已经散场的，都不是买家能对之采取行动的
     * 东西。
     */
    @Select("""
            <script>
            SELECT s.*,
                   f.title AS project_title, f.category, f.duration, f.poster_url,
                   c.name AS venue_name,
                   h.name AS place_name, h.place_type,
                   (s.total_seat - s.locked_seat - s.sold_seat) AS remaining_seat
            """ + JOIN_AND_LABELS + """
             WHERE s.status = 1
               AND s.show_date = #{showDate}
               <if test="projectId != null">   AND s.project_id   = #{projectId}   </if>
               <if test="venueId != null"> AND s.venue_id = #{venueId} </if>
             ORDER BY s.start_time, s.id
            </script>
            """)
    List<SessionVO> selectScheduleList(@Param("projectId") Long projectId,
                                        @Param("venueId") Long venueId,
                                        @Param("showDate") LocalDate showDate);

    @Select("""
            SELECT s.*,
                   f.title AS project_title, f.category, f.duration, f.poster_url,
                   c.name AS venue_name,
                   h.name AS place_name, h.place_type,
                   (s.total_seat - s.locked_seat - s.sold_seat) AS remaining_seat
            """ + JOIN_AND_LABELS + """
             WHERE s.id = #{sessionId}
            """)
    SessionVO selectScheduleDetail(@Param("sessionId") Long sessionId);

    /**
     * 这一天在这个影院确实有排片的影片 id，这样影院页只展示可订的，而不是整个片库。
     */
    @Select("""
            SELECT DISTINCT s.project_id
              FROM t_event_session s
             WHERE s.venue_id = #{venueId}
               AND s.show_date = #{showDate}
               AND s.status = 1
            """)
    List<Long> selectProjectIdsWithScreening(@Param("venueId") Long venueId,
                                          @Param("showDate") LocalDate showDate);

    // ------------------------------------------------------------
    // G1 分支 —— 下单过程中占住库存
    // ------------------------------------------------------------

    /**
     * 从一场排片的库存里预占 {@code count} 个座位。
     *
     * <p>{@code locked + sold + count <= total} 这个条件就是防超卖关卡。两个并发订单
     * 可以同时走到这条语句；InnoDB 在行上把它们串行化，第二个拿到 0，调用方必须把它
     * 当成「已售罄」。
     *
     * @return 预占成功返回 1；会超卖或该排片已不在售返回 0
     */
    @Update("""
            UPDATE t_event_session
               SET locked_seat = locked_seat + #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND status = 1
               AND locked_seat + sold_seat + #{count} <= total_seat
            """)
    int occupySeats(@Param("sessionId") Long sessionId, @Param("count") int count);

    /**
     * 支付成功后把座位从锁定挪到已售（G2）。
     *
     * <p>用 {@code locked_seat >= count} 把关：如果这些占位已经被超时任务释放了，
     * 这里返回 0，调用方就不能再给出那些已经没人持有的座位的票。
     */
    @Update("""
            UPDATE t_event_session
               SET locked_seat = locked_seat - #{count},
                   sold_seat   = sold_seat   + #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND locked_seat >= #{count}
            """)
    int confirmSold(@Param("sessionId") Long sessionId, @Param("count") int count);

    /**
     * 把预占的座位还回去 —— 取消时、超时时，或 G1 回滚时。
     *
     * <p>{@code locked_seat >= count} 这个关卡防止补偿跑两遍时把计数器变成负数。
     */
    @Update("""
            UPDATE t_event_session
               SET locked_seat = locked_seat - #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND locked_seat >= #{count}
            """)
    int releaseLocked(@Param("sessionId") Long sessionId, @Param("count") int count);

    /**
     * 把<em>已售</em>的座位还回去，用于把座位放回池子的退款。
     *
     * <p>和 {@link #releaseLocked} 不是一回事：那些座位从来没付过钱，所以只动锁定
     * 计数器。这些付过，所以只动已售计数器。用错一个会无声地搞坏余座算术 —— 座位图
     * 会把这个座位显示成空闲，而已售计数器仍然声称它是卖掉的。
     */
    @Update("""
            UPDATE t_event_session
               SET sold_seat = sold_seat - #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND sold_seat >= #{count}
            """)
    int releaseSold(@Param("sessionId") Long sessionId, @Param("count") int count);
}
