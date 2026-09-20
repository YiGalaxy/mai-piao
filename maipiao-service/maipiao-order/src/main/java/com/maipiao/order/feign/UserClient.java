package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * Calls user-service for the coupon branch of G1.
 *
 * <p>{@link #lockCoupon} participates in the global transaction and throws on
 * failure, so a coupon that cannot be held rolls the whole order back rather
 * than letting it through with the discount already applied.
 */
@FeignClient(name = "maipiao-user", path = "/inner")
public interface UserClient {

    @PostMapping("/coupon/lock")
    R<Void> lockCoupon(@RequestParam Long couponId,
                       @RequestParam Long userId,
                       @RequestParam String orderNo,
                       @RequestParam BigDecimal orderAmount);

    /** Converts the hold into a permanent use, after payment succeeds. */
    @PostMapping("/coupon/consume")
    R<Void> consumeCoupon(@RequestParam Long couponId,
                          @RequestParam String orderNo);

    /** Gives the coupon back on cancellation or refund. */
    @PostMapping("/coupon/release")
    R<Void> releaseCoupon(@RequestParam Long couponId,
                          @RequestParam String orderNo);
}
