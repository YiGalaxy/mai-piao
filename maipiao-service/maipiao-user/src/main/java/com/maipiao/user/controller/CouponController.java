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
     * 给定订单金额下可用的优惠券。订单确认页会拿它算出来的金额来调这个接口，
     * 所以列表里只会出现真正能用得上的那些。
     */
    @GetMapping("/available")
    public R<List<Coupon>> available(@RequestParam BigDecimal amount) {
        return R.ok(couponService.listUsable(UserContext.require(), amount));
    }

    /** 用户持有的全部优惠券，包括已使用和已过期的。 */
    @GetMapping("/list")
    public R<List<Coupon>> list() {
        return R.ok(couponService.listAll(UserContext.require()));
    }
}
