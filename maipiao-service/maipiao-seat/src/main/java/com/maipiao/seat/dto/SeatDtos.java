package com.maipiao.seat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 座位相关接口的请求与响应结构。 */
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
             * 准入令牌，只有抢购场次才需要，其他情况下忽略。之所以挂在请求上，是因为
             * 真正的执行点在座位服务 —— 网关那次检查是从 query string 里读场次 id 的，
             * 而那是由调用方控制的。
             */
            String queueToken
    ) {
    }

    /**
     * 一次成功的加锁。
     *
     * <p>{@code lockToken} 是下单调用拿来证明座位已被持有的凭据；没有它，锁座和创建
     * 订单就能被各自独立地驱动，有人就能为一个自己从未占下的座位下单。
     *
     * @param expireSeconds 距离持有失效还有多少秒，供客户端在支付页上做倒计时
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

    /** 当请求的座位中有一个或多个已经归了别人时返回。 */
    public record LockConflictResponse(
            int conflictSeatIndex,
            String conflictSeatLabel,
            String message
    ) {
    }

    /**
     * 向系统要座位，而不是点名要哪些座位。
     *
     * <p>没有座位索引：买家选一个票档和一个数量，这正是这个模式的意义所在 —— 屏幕上
     * 根本没有座位图可以点。
     *
     * <p>{@code adjacent} 默认为 true，它就是"两个座位"和"两个挨着的座位"之间的
     * 区别。宁愿要分开的座位也不要没座位的调用方把它设成 false；客户端是在失败之后
     * 把这个当成一个明确选项给出来的，绝不会悄悄替用户选掉。
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

            /** 准入令牌；见 {@link LockSeatRequest#queueToken}。 */
            String queueToken
    ) {
        public boolean wantsAdjacent() {
            return adjacent == null || adjacent;
        }
    }

    /**
     * 一次没能找到足够长连座的分配。
     *
     * <p>与普通的冲突分开，因为这是另一种情形，也要用另一种办法解决：什么都没被拿走，
     * 也没有任何东西坏掉，只是这个票档坐不下那么多人挨在一起。{@code longestRun} 就是
     * 调用方拿出来的备选方案。
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
