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
 * 服务间接口。不由网关路由 —— 这些路径都在 {@code /inner} 下面，
 * 而网关只转发 {@code /api/**}。
 *
 * <p>{@link #lock} 是 G1 全局事务的分支 ②。优惠券占不住时它会抛异常，
 * 而正是这一点把整笔订单回滚掉 —— 静默放行会造出一笔减了折扣、
 * 而那张券在别处还能接着花的订单。
 *
 * <p>另外两个在事务之后调用，所以它们容忍被调用两次。
 */
@Slf4j
@RestController
@RequestMapping("/inner/coupon")
@RequiredArgsConstructor
public class UserInternalController {

    private final CouponService couponService;

    /** G1 分支：为这笔订单占住优惠券。占不住就抛异常。 */
    @PostMapping("/lock")
    public R<Void> lock(@RequestParam Long couponId,
                        @RequestParam Long userId,
                        @RequestParam String orderNo,
                        @RequestParam BigDecimal orderAmount) {
        couponService.lockForOrder(couponId, userId, orderNo, orderAmount);
        return R.ok();
    }

    /** 支付成功：这次占用变成永久使用。 */
    @PostMapping("/consume")
    public R<Void> consume(@RequestParam Long couponId, @RequestParam String orderNo) {
        couponService.consumeForOrder(couponId, orderNo);
        return R.ok();
    }

    /**
     * 取消或退款时，把优惠券还回去。
     *
     * <p>用 {@code order_no} 做守护，所以一笔更早订单发来的过期补偿，
     * 不能把一张已经被更新的订单占着的券释放掉。
     */
    @PostMapping("/release")
    public R<Void> release(@RequestParam Long couponId, @RequestParam String orderNo) {
        couponService.releaseForOrder(couponId, orderNo);
        return R.ok();
    }
}
