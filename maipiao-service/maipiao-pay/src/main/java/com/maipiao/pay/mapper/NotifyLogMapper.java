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
     * 记录一条回调，重复的算次数而不是直接拒掉。
     *
     * <p>刻意选择计数而不是忽略：重试次数正是「渠道方在反复捶我们」的信号，
     * 而重复如果被默默丢掉，这个信号也就没了。
     *
     * @return 建出了新行返回 1，已经存在返回 0
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
     * 失败过、值得重放的回调。
     *
     * <p>按时间设了上界：一周前那条从未成功的回调是个人工问题，
     * 不是可以一直悄悄重试下去的东西。
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
