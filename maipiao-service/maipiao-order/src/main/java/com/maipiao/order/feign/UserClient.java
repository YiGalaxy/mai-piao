package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    /**
     * Looks a user up by phone, for the admin order search.
     *
     * <p>Orders do not store a phone number - it is a value that changes, and
     * an order is a historical record. So a phone search resolves to an id
     * here and the order table is queried by that.
     */
    @GetMapping("/user/find-by-phone")
    R<Map<String, Object>> findByPhone(@RequestParam("phone") String phone);

    /**
     * Phone numbers for a set of user ids, in one call.
     *
     * <p>Batched because the alternative is one lookup per row: a page of
     * twenty orders would be twenty round trips to render a list nobody reads
     * closely.
     */
    @GetMapping("/user/phones")
    R<Map<Long, String>> phones(@RequestParam("userIds") List<Long> userIds);
}
