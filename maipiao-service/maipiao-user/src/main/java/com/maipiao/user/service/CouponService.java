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
 * 优惠券的发放、查询，以及锁定 / 核销 / 释放的循环。
 *
 * <p>lock/consume/release 这三个方法由其他服务经 Feign 调用，并且参与 G1 全局事务，
 * 所以它们是故意做薄的：SQL 已经校验过的东西它们不再校验，而且它们不吞失败。
 * 返回 0 必须以异常的形式往外传，这样 Seata 才能把整笔订单回滚掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponMapper couponMapper;

    // ------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------

    /**
     * 针对这个金额的订单，用户真正用得上的优惠券。
     *
     * <p>在 SQL 里而不是在 Java 里按门槛过滤，能让响应不会随着用户领过的每一张券一起膨胀。
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
    // G1 分支 ② - 下单时锁定
    // ------------------------------------------------------------

    /**
     * 为一笔订单锁住一张优惠券。
     *
     * <p>跑在订单的全局事务里。锁定失败时必须抛异常而不是返回 false ——
     * 这里的静默失败会让订单带着已经减掉的折扣过去，而那张券在别处还能接着花。
     */
    @Transactional(rollbackFor = Exception.class)
    public void lockForOrder(Long couponId, Long userId, String orderNo, BigDecimal orderAmount) {
        if (couponId == null) {
            return;
        }

        int rows = couponMapper.lockCoupon(couponId, userId, orderNo, orderAmount);
        if (rows != 1) {
            // 要么被别人抢走了，要么它在页面渲染和提交之间过期了，
            // 要么金额已经不满足门槛了。
            throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE);
        }
        log.debug("coupon locked: couponId={}, orderNo={}", couponId, orderNo);
    }

    /** 支付成功 —— 把这次锁定变成永久核销。 */
    @Transactional(rollbackFor = Exception.class)
    public void consumeForOrder(Long couponId, String orderNo) {
        if (couponId == null) {
            return;
        }
        int rows = couponMapper.consumeCoupon(couponId, orderNo);
        if (rows != 1) {
            // 单看这件事本身不致命，但它意味着优惠券状态和订单状态对不上，
            // 这值得去查一查。
            log.warn("coupon consume did not take effect: couponId={}, orderNo={}", couponId, orderNo);
        }
    }

    /**
     * 把锁住的优惠券还回去。订单取消、退款，以及 G1 回滚路径上都会调用。
     *
     * <p>构造上就是幂等的：{@code order_no} 这道守护让重复调用找不到可更新的行，
     * 于是返回 0 且没有任何副作用。正是这一点，让补偿处理器重复执行是安全的。
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
    // 发券（演示数据，以及后台触发的活动）
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
