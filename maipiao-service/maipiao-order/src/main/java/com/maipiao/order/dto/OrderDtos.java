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

    private OrderDtos() {
    }

    /**
     * 下单入参。
     *
     * <p>{@code lockToken} 是 seat-service 在锁座时返回的东西，它会成为订单号。
     * 把它设成必填，是拦住「为一组没人持有的座位下单」的那道关。
     */
    public record CreateOrderRequest(
            /**
             * seat-service 在锁座时返回的凭证。
             *
             * <p>它会成为订单号，而这是让 Redis 里的占用和订单行在构造上就指向同一件事、
             * 而不是靠事后再对账去对齐的原因。把它设成必填，
             * 同样也是拦住「为一组没人持有的座位下单」的那道关。
             */
            @NotBlank(message = "选座凭证不能为空")
            String lockToken,

            @NotNull(message = "场次不能为空")
            Long scheduleId,

            @NotEmpty(message = "座位不能为空")
            @Size(max = 6, message = "一次最多购买 6 张票")
            List<Integer> seatIndexes,

            /** 展示用标签，例如 ["5排7座"]；由客户端送来，省掉一次查询。 */
            List<String> seatLabels,

            /** 可选。会在服务端针对用户真实的优惠券做校验。 */
            Long couponId,

            /** 客户端算出来的优惠金额；使用之前会在服务端重新推导一遍。 */
            BigDecimal discountAmount
    ) {
    }

    public record CreateOrderResponse(
            String orderNo,
            BigDecimal payAmount,
            LocalDateTime expireTime
    ) {
    }

    public record OrderDetail(
            Order order,
            List<OrderItem> items,
            String statusText
    ) {
    }

    /**
     * 留着的别名，好让 controller 不用去 import entity 包就能给这个请求命名。
     */
    public record CancelRequest(String reason) {
    }
}
