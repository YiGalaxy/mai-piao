package com.maipiao.order.dto;

import com.maipiao.order.entity.Order;
import com.maipiao.order.entity.OrderItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单接口的请求与响应结构。 */
public final class OrderDtos {

    private OrderDtos() {}

    /**
     * 下单入参。
     *
     * <p>{@code lockToken} 是 seat-service 在锁座时返回的东西，它会成为订单号。 把它设成必填，是拦住「为一组没人持有的座位下单」的那道关。
     */
    public record CreateOrderRequest(
            /**
             * seat-service 在锁座时返回的凭证。
             *
             * <p>它会成为订单号，而这是让 Redis 里的占用和订单行在构造上就指向同一件事、 而不是靠事后再对账去对齐的原因。把它设成必填，
             * 同样也是拦住「为一组没人持有的座位下单」的那道关。
             */
            @NotBlank(message = "选座凭证不能为空") String lockToken,
            @NotNull(message = "场次不能为空") Long scheduleId,
            @NotEmpty(message = "座位不能为空") @Size(max = 6, message = "一次最多购买 6 张票")
                    List<Integer> seatIndexes,

            /** 兼容旧客户端的展示标签；写库使用锁座服务登记的标签。 */
            List<String> seatLabels,

            /** 可选。会在服务端针对用户真实的优惠券做校验。 */
            Long couponId,

            /** 兼容旧客户端；服务端忽略此值，以锁券数据库结果计价。 */
            BigDecimal discountAmount,

            /** 仅前次已确认回滚后，允许明确调整优惠券。 */
            Boolean revise) {}

    public record CreateOrderResponse(
            String orderNo, BigDecimal payAmount, LocalDateTime expireTime) {}

    public record CreationResult(
            String state,
            long expiresAt,
            boolean heldVerified,
            String orderNo,
            BigDecimal payAmount,
            LocalDateTime expireTime,
            Integer orderStatus,
            String message) {}

    public record OrderDetail(Order order, List<OrderItem> items, String statusText) {}

    /** 留着的别名，好让 controller 不用去 import entity 包就能给这个请求命名。 */
    public record CancelRequest(String reason) {}
}
