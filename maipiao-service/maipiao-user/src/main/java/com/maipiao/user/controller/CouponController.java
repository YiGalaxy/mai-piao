package com.maipiao.user.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.user.entity.Coupon;
import com.maipiao.user.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/user/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    /**
     * Coupons usable for a given order amount. The order confirmation page calls
     * this with the amount it computed, so the list only shows what can actually
     * be applied.
     */
    @GetMapping("/available")
    public R<List<Coupon>> available(@RequestParam BigDecimal amount) {
        return R.ok(couponService.listUsable(UserContext.require(), amount));
    }

    /** Everything the user holds, including used and expired ones. */
    @GetMapping("/list")
    public R<List<Coupon>> list() {
        return R.ok(couponService.listAll(UserContext.require()));
    }
}
