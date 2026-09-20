package com.maipiao.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maipiao.pay.entity.NotifyLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NotifyLogMapper extends BaseMapper<NotifyLog> {

    /**
     * Records a callback, counting repeats instead of rejecting them.
     *
     * <p>Counting rather than ignoring is deliberate: the retry count is the
     * signal that a provider is hammering us, and it is lost if duplicates are
     * silently dropped.
     *
     * @return 1 when the row was created, 0 when it already existed
     */
    @Insert("""
            INSERT INTO t_pay_notify_log
              (id, channel, channel_trade_no, notify_type, raw_body, sign_verified,
               process_status, retry_times, create_time, update_time)
            VALUES
              (#{id}, #{channel}, #{channelTradeNo}, #{notifyType}, #{rawBody}, #{signVerified},
               0, 0, NOW(3), NOW(3))
            ON DUPLICATE KEY UPDATE
              retry_times = retry_times + 1,
              update_time = NOW(3)
            """)
    int insertOrCount(@Param("id") Long id,
                      @Param("channel") String channel,
                      @Param("channelTradeNo") String channelTradeNo,
                      @Param("notifyType") String notifyType,
                      @Param("rawBody") String rawBody,
                      @Param("signVerified") int signVerified);

    @Select("""
            SELECT * FROM t_pay_notify_log
             WHERE channel = #{channel} AND channel_trade_no = #{channelTradeNo}
               AND notify_type = #{notifyType}
            """)
    NotifyLog selectOne(@Param("channel") String channel,
                        @Param("channelTradeNo") String channelTradeNo,
                        @Param("notifyType") String notifyType);

    @Update("""
            UPDATE t_pay_notify_log
               SET process_status = #{status}, process_result = #{result}, update_time = NOW(3)
             WHERE id = #{id}
            """)
    int markProcessed(@Param("id") Long id,
                      @Param("status") int status,
                      @Param("result") String result);

    /**
     * Callbacks that failed and are worth replaying.
     *
     * <p>Bounded by age: a callback from a week ago that never succeeded is a
     * human problem, not something to keep retrying quietly.
     */
    @Select("""
            SELECT * FROM t_pay_notify_log
             WHERE process_status = 2
               AND retry_times < #{maxRetry}
               AND create_time > #{since}
             ORDER BY create_time
             LIMIT #{limit}
            """)
    List<NotifyLog> selectReplayable(@Param("maxRetry") int maxRetry,
                                     @Param("since") java.time.LocalDateTime since,
                                     @Param("limit") int limit);
}
