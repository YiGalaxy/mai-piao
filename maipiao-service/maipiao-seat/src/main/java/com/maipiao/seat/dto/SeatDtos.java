package com.maipiao.seat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            Long scheduleId,

            @NotEmpty(message = "请选择座位")
            @Size(max = 6, message = "一次最多选择 6 个座位")
            List<Integer> seatIndexes,

            /**
             * Admission token, required only for a rush sale and ignored
             * otherwise. Carried on the request because the seat service is
             * where it is enforced - the gateway's check reads the schedule id
             * from the query string, which the caller controls.
             */
            String queueToken
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
            Long scheduleId,
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

    /**
     * Ask the system for seats rather than naming them.
     *
     * <p>No seat indexes: the buyer picks a band and a quantity, which is the
     * whole point of the mode - there is no map on screen to pick from.
     *
     * <p>{@code adjacent} defaults to true and is what separates "two seats" from
     * "two seats together". A caller that would rather have split seats than
     * none sets it false; the client offers that as an explicit choice after a
     * failure, never silently.
     */
    public record AssignSeatRequest(
            @NotNull(message = "场次不能为空")
            Long scheduleId,

            @NotNull(message = "请选择票档")
            Long tierId,

            @NotNull(message = "请选择数量")
            @Min(value = 1, message = "至少购买 1 张")
            @Max(value = 6, message = "一次最多购买 6 张")
            Integer quantity,

            Boolean adjacent,

            /** Admission token; see {@link LockSeatRequest#queueToken}. */
            String queueToken
    ) {
        public boolean wantsAdjacent() {
            return adjacent == null || adjacent;
        }
    }

    /**
     * An allocation that found no run of that length.
     *
     * <p>Separate from a plain conflict because it is a different situation
     * with a different remedy: nothing was taken and nothing is broken, the
     * band simply cannot seat that many together. {@code longestRun} is what
     * the caller offers as the alternative.
     */
    public record NotAdjacentResponse(
            int requested,
            int longestRun,
            String message
    ) {
    }

    @Data
    public static class ReleaseSeatRequest {
        @NotNull(message = "场次不能为空")
        private Long scheduleId;
    }
}
