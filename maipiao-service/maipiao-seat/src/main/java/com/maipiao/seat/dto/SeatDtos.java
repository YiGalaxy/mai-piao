package com.maipiao.seat.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Request and response shapes for the seat endpoints. */
public final class SeatDtos {

    private SeatDtos() {
    }

    public record LockSeatRequest(
            @NotNull(message = "场次不能为空")
            Long sessionId,

            @NotEmpty(message = "请选择座位")
            @Size(max = 6, message = "一次最多选择 6 个座位")
            List<Integer> seatIndexes
    ) {
    }

    /**
     * A successful lock.
     *
     * <p>{@code lockToken} is what the order call presents to prove the seats
     * are held; without it, seat locking and order creation could be driven
     * independently and someone could place an order for seats they never took.
     *
     * @param expireSeconds seconds until the hold lapses, for the countdown
     *                      the client shows on the payment page
     */
    public record LockSeatResponse(
            String lockToken,
            Long sessionId,
            List<Integer> seatIndexes,
            List<String> seatLabels,
            BigDecimal amount,
            int expireSeconds
    ) {
    }

    /** Returned when one or more of the requested seats went to somebody else. */
    public record LockConflictResponse(
            int conflictSeatIndex,
            String conflictSeatLabel,
            String message
    ) {
    }

    @Data
    public static class ReleaseSeatRequest {
        @NotNull(message = "场次不能为空")
        private Long sessionId;
    }
}
