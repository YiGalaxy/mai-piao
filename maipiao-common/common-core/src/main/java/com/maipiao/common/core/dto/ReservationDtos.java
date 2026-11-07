package com.maipiao.common.core.dto;

import java.util.List;

/** 服务间预约登记。有效期是首次锁座的绝对时间，不能通过重试续期。 */
public final class ReservationDtos {
    private ReservationDtos() {}

    public record Prepare(String token, Long scheduleId, Long userId, long expiresAt) {}

    public record Publish(String token, List<Integer> seatIndexes, List<String> seatLabels) {}
}
