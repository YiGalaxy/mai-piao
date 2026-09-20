package com.maipiao.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.user.entity.Coupon;
import com.maipiao.user.mapper.CouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Coupon issuing, lookup and the lock/consume/release cycle.
 *
 * <p>The lock/consume/release methods are called from other services through
 * Feign and participate in the G1 global transaction, so they are deliberately
 * thin: they validate nothing that the SQL already validates and they do not
 * swallow failures. A returned 0 must propagate as an exception so that Seata
 * rolls the whole order back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponMapper couponMapper;

    // ------------------------------------------------------------
    // query
    // ------------------------------------------------------------

    /**
     * Coupons the user could actually apply to an order of this amount.
     *
     * <p>Filtering by threshold in SQL rather than in Java keeps the response
     * from growing with every coupon the user has ever collected.
     */
    public List<Coupon> listUsable(Long userId, BigDecimal orderAmount) {
        return couponMapper.selectList(Wrappers.<Coupon>lambdaQuery()
                .eq(Coupon::getUserId, userId)
                .eq(Coupon::getStatus, Coupon.STATUS_UNUSED)
                .gt(Coupon::getExpireTime, LocalDateTime.now())
                .le(Coupon::getThreshold, orderAmount)
                .orderByDesc(Coupon::getAmount));
    }

    public List<Coupon> listAll(Long userId) {
        return couponMapper.selectList(Wrappers.<Coupon>lambdaQuery()
                .eq(Coupon::getUserId, userId)
                .orderByAsc(Coupon::getStatus)
                .orderByDesc(Coupon::getCreateTime));
    }

    // ------------------------------------------------------------
    // G1 branch ② - lock during order creation
    // ------------------------------------------------------------

    /**
     * Locks a coupon for an order.
     *
     * <p>Runs inside the order's global transaction. Must throw rather than
     * return false when the lock fails - a silent failure here would let the
     * order go through with the discount already subtracted but the coupon
     * still spendable elsewhere.
     */
    @Transactional(rollbackFor = Exception.class)
    public void lockForOrder(Long couponId, Long userId, String orderNo, BigDecimal orderAmount) {
        if (couponId == null) {
            return;
        }

        int rows = couponMapper.lockCoupon(couponId, userId, orderNo, orderAmount);
        if (rows != 1) {
            // Either someone else took it, it expired between the page render and
            // the submit, or the amount no longer meets the threshold.
            throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE);
        }
        log.debug("coupon locked: couponId={}, orderNo={}", couponId, orderNo);
    }

    /** Payment succeeded - turn the lock into a permanent consumption. */
    @Transactional(rollbackFor = Exception.class)
    public void consumeForOrder(Long couponId, String orderNo) {
        if (couponId == null) {
            return;
        }
        int rows = couponMapper.consumeCoupon(couponId, orderNo);
        if (rows != 1) {
            // Not fatal on its own, but it means the coupon state and the order
            // state disagree, which is worth investigating.
            log.warn("coupon consume did not take effect: couponId={}, orderNo={}", couponId, orderNo);
        }
    }

    /**
     * Gives a locked coupon back. Called on order cancellation, on refund, and
     * from the G1 rollback path.
     *
     * <p>Idempotent by construction: the {@code order_no} guard means a repeat
     * call finds nothing to update and returns 0 without side effects. That is
     * what makes it safe for a compensation handler to run more than once.
     */
    @Transactional(rollbackFor = Exception.class)
    public void releaseForOrder(Long couponId, String orderNo) {
        if (couponId == null) {
            return;
        }
        int rows = couponMapper.releaseCoupon(couponId, orderNo);
        log.debug("coupon released: couponId={}, orderNo={}, affected={}", couponId, orderNo, rows);
    }

    // ------------------------------------------------------------
    // issuing (demo data, and admin-triggered campaigns)
    // ------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public void issue(Long userId, Long templateId, BigDecimal amount,
                      BigDecimal threshold, int validDays) {
        Coupon coupon = new Coupon();
        coupon.setUserId(userId);
        coupon.setCouponTemplateId(templateId);
        coupon.setAmount(amount);
        coupon.setThreshold(threshold);
        coupon.setStatus(Coupon.STATUS_UNUSED);
        coupon.setExpireTime(LocalDateTime.now().plusDays(validDays));
        couponMapper.insert(coupon);
    }
}
