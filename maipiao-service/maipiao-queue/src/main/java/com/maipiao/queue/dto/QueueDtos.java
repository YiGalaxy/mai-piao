package com.maipiao.queue.dto;

import java.time.LocalDateTime;

/** 队列接口的数据结构。 */
public final class QueueDtos {

    private QueueDtos() {
    }

    /**
     * 用户排在哪，以及他能据此做什么。
     *
     * <p>用一个对象而不是拆成几个接口，因为一个等待中的客户端会一遍又一遍地问同一个
     * 问题，而且必须能拿着答案直接行动、不用再发一次调用：{@code PASSED} 会带着令牌，
     * 而 {@code SOLD_OUT} 就是那个能让它停止追问的答案。
     *
     * @param status        WAITING、PASSED、SOLD_OUT、PAUSED 或 NOT_STARTED
     * @param rank          队列中的位置，从 1 开始；被放行后为 0
     * @param ahead         前面还有多少人，这才是要显示给用户的数字
     * @param total         队列里一共有多少人，供客户端画进度
     * @param token         准入令牌，只有 PASSED 时才存在
     * @param expiresIn     令牌还能有效多少秒
     * @param rushStartTime 开售时间，给还没开始的队列用
     */
    public record PositionVO(
            String status,
            int rank,
            int ahead,
            int total,
            String token,
            long expiresIn,
            LocalDateTime rushStartTime
    ) {
        public static PositionVO of(String status, int rank, int ahead, int total,
                                    LocalDateTime rushStartTime) {
            return new PositionVO(status, rank, ahead, total, null, 0, rushStartTime);
        }
    }

    /** 队列状态，用字符串表示，这样客户端不会被某个序号绑死。 */
    public static final class Status {
        public static final String WAITING = "WAITING";
        public static final String PASSED = "PASSED";
        public static final String SOLD_OUT = "SOLD_OUT";
        public static final String PAUSED = "PAUSED";
        public static final String NOT_STARTED = "NOT_STARTED";

        private Status() {
        }
    }
}
