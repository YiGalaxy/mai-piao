package com.maipiao.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.movie.dto.SessionVO;
import com.maipiao.movie.entity.Session;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 场次持久化；库存 UPDATE 通过条件和影响行数实现并发 CAS。 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /** 列表和详情查询共用的关联片段。 */
    String JOIN_AND_LABELS =
            """
              FROM t_event_session s
              JOIN t_event_project   f ON f.id = s.project_id
              JOIN t_event_venue c ON c.id = s.venue_id
              JOIN t_event_place   h ON h.id = s.place_id
            """;

    /** 查询指定日期的在售场次。 */
    @Select(
            """
            <script>
            SELECT s.*,
                   f.title AS project_title, f.category, f.duration, f.poster_url,
                   c.name AS venue_name,
                   h.name AS place_name, h.place_type,
                   (s.total_seat - s.locked_seat - s.sold_seat) AS remaining_seat
            """
                    + JOIN_AND_LABELS
                    + """
             WHERE s.status = 1
               AND s.show_date = #{showDate}
               <if test="projectId != null">   AND s.project_id   = #{projectId}   </if>
               <if test="venueId != null"> AND s.venue_id = #{venueId} </if>
             ORDER BY s.start_time, s.id
            </script>
            """)
    List<SessionVO> selectScheduleList(
            @Param("projectId") Long projectId,
            @Param("venueId") Long venueId,
            @Param("showDate") LocalDate showDate);

    @Select(
            """
            SELECT s.*,
                   f.title AS project_title, f.category, f.duration, f.poster_url,
                   c.name AS venue_name,
                   h.name AS place_name, h.place_type,
                   (s.total_seat - s.locked_seat - s.sold_seat) AS remaining_seat
            """
                    + JOIN_AND_LABELS
                    + """
             WHERE s.id = #{sessionId}
            """)
    SessionVO selectScheduleDetail(@Param("sessionId") Long sessionId);

    /** 查询影院当天有排片的项目。 */
    @Select(
            """
            SELECT DISTINCT s.project_id
              FROM t_event_session s
             WHERE s.venue_id = #{venueId}
               AND s.show_date = #{showDate}
               AND s.status = 1
            """)
    List<Long> selectProjectIdsWithScreening(
            @Param("venueId") Long venueId, @Param("showDate") LocalDate showDate);

    /** 预占座位；条件更新是防超卖关卡。 */
    @Update(
            """
            UPDATE t_event_session
               SET locked_seat = locked_seat + #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND status = 1
               AND (sale_start_time IS NULL OR sale_start_time <= NOW(3))
               AND start_time > NOW(3)
               AND (purchase_limit IS NULL OR purchase_limit=0 OR #{count} <= purchase_limit)
               AND #{count} > 0
               AND (rush_mode=0 OR rush_start_time IS NULL OR rush_start_time <= NOW(3))
               AND locked_seat + sold_seat + #{count} <= total_seat
            """)
    int occupySeats(@Param("sessionId") Long sessionId, @Param("count") int count);

    /** G2：支付成功后将锁定库存转为已售。 */
    @Update(
            """
            UPDATE t_event_session
               SET locked_seat = locked_seat - #{count},
                   sold_seat   = sold_seat   + #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND locked_seat >= #{count}
            """)
    int confirmSold(@Param("sessionId") Long sessionId, @Param("count") int count);

    /** 释放锁定库存；条件更新保证补偿幂等。 */
    @Update(
            """
            UPDATE t_event_session
               SET locked_seat = locked_seat - #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND locked_seat >= #{count}
            """)
    int releaseLocked(@Param("sessionId") Long sessionId, @Param("count") int count);

    /** 退款时释放已售库存，单独扣减 sold_seat。 */
    @Update(
            """
            UPDATE t_event_session
               SET sold_seat = sold_seat - #{count},
                   update_time = NOW(3)
             WHERE id = #{sessionId}
               AND sold_seat >= #{count}
            """)
    int releaseSold(@Param("sessionId") Long sessionId, @Param("count") int count);
}
