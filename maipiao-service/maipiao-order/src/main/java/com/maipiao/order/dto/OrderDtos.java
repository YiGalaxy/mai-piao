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

/** Request and response shapes for the order endpoints. */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * Order creation input.
     *
     * <p>{@code lockToken} is what seat-service returned when the seats were
     * held, and it becomes the order number. Requiring it is what stops an
     * order being placed for seats nobody holds.
     */
    public record CreateOrderRequest(
            /**
             * The token seat-service returned when the seats were held.
             *
             * <p>It becomes the order number, which is what keeps the Redis
             * hold and the order row referring to the same thing by
             * construction rather than by a later reconciliation. Requiring it
             * is also what stops an order being placed for seats nobody holds.
             */
            @NotBlank(message = "选座凭证不能为空")
            String lockToken,

            @NotNull(message = "场次不能为空")
            Long scheduleId,

            @NotEmpty(message = "座位不能为空")
            @Size(max = 6, message = "一次最多购买 6 张票")
            List<Integer> seatIndexes,

            /** Display labels, e.g. ["5排7座"]; sent by the client to avoid a lookup. */
            List<String> seatLabels,

            /** Optional. Validated server-side against the user's actual coupons. */
            Long couponId,

            /** Client-computed discount; re-derived server-side before use. */
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
     * Alias kept so the controller can name the request without importing the
     * entity package.
     */
    public record CancelRequest(String reason) {
    }
}
