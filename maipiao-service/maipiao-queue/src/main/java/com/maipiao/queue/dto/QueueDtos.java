package com.maipiao.queue.dto;

import java.time.LocalDateTime;

/** Shapes for the queue endpoints. */
public final class QueueDtos {

    private QueueDtos() {
    }

    /**
     * Where a user stands, and what they can do about it.
     *
     * <p>One object rather than several endpoints, because a waiting client
     * asks the same question over and over and has to be able to act on the
     * answer without a second call: {@code PASSED} comes with the token, and
     * {@code SOLD_OUT} is the answer that lets it stop asking.
     *
     * @param status        WAITING, PASSED, SOLD_OUT, PAUSED or NOT_STARTED
     * @param rank          position in line, 1-based; 0 once admitted
     * @param ahead         how many are in front, which is the number to show
     * @param total         how many are in line, so a client can draw progress
     * @param token         admission token, present only when PASSED
     * @param expiresIn     seconds the token remains valid
     * @param rushStartTime when the sale opens, for a line that has not opened
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

    /** Queue states, as strings so the client is not coupled to an ordinal. */
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
