package com.maipiao.user.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.user.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Service-to-service endpoints. Not routed by the gateway - the paths live
 * under {@code /inner} and the gateway only forwards {@code /api/**}.
 *
 * <p>{@link #lock} is branch ② of the G1 global transaction. It throws when
 * the coupon cannot be held, which is what rolls the whole order back -
 * silently proceeding would create an order with a discount applied against a
 * coupon that is still spendable elsewhere.
 *
 * <p>The other two are called after the transaction, so they tolerate being
 * called twice.
 */
@Slf4j
@RestController
@RequestMapping("/inner/coupon")
@RequiredArgsConstructor
public class UserInternalController {

    private final CouponService couponService;

    /** G1 branch: hold the coupon for this order. Throws when it cannot. */
    @PostMapping("/lock")
    public R<Void> lock(@RequestParam Long couponId,
                        @RequestParam Long userId,
                        @RequestParam String orderNo,
                        @RequestParam BigDecimal orderAmount) {
        couponService.lockForOrder(couponId, userId, orderNo, orderAmount);
        return R.ok();
    }

    /** Payment succeeded: the hold becomes a permanent use. */
    @PostMapping("/consume")
    public R<Void> consume(@RequestParam Long couponId, @RequestParam String orderNo) {
        couponService.consumeForOrder(couponId, orderNo);
        return R.ok();
    }

    /**
     * Gives the coupon back, on cancellation or refund.
     *
     * <p>Guarded on {@code order_no}, so a stale compensation from an older
     * order cannot free a coupon a newer order now holds.
     */
    @PostMapping("/release")
    public R<Void> release(@RequestParam Long couponId, @RequestParam String orderNo) {
        couponService.releaseForOrder(couponId, orderNo);
        return R.ok();
    }
}
