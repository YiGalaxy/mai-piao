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
 * 调用 user-service，做 G1 里的优惠券分支。
 *
 * <p>{@link #lockCoupon} 参与全局事务并在失败时抛异常，
 * 所以一张占不住的优惠券会把整笔订单回滚掉，
 * 而不是让订单带着已经减掉的折扣就这么过去。
 */
@FeignClient(name = "maipiao-user", path = "/inner")
public interface UserClient {

    @PostMapping("/coupon/lock")
    R<Void> lockCoupon(@RequestParam Long couponId,
                       @RequestParam Long userId,
                       @RequestParam String orderNo,
                       @RequestParam BigDecimal orderAmount);

    /** 支付成功之后，把这次占用变成永久使用。 */
    @PostMapping("/coupon/consume")
    R<Void> consumeCoupon(@RequestParam Long couponId,
                          @RequestParam String orderNo);

    /** 取消或退款时把优惠券还回去。 */
    @PostMapping("/coupon/release")
    R<Void> releaseCoupon(@RequestParam Long couponId,
                          @RequestParam String orderNo);

    /**
     * 按手机号查用户，给后台的订单搜索用。
     *
     * <p>订单不存手机号 —— 它是一个会变的值，而订单是一份历史记录。
     * 所以按手机号搜索时在这里换成一个 id，再拿这个 id 去查订单表。
     */
    @GetMapping("/user/find-by-phone")
    R<Map<String, Object>> findByPhone(@RequestParam("phone") String phone);

    /**
     * 一批用户 id 对应的手机号，一次调用取回。
     *
     * <p>做成批量的，因为另一条路是每行查一次：一页二十笔订单就是二十次往返，
     * 只为渲染一个没人细看的列表。
     */
    @GetMapping("/user/phones")
    R<Map<Long, String>> phones(@RequestParam("userIds") List<Long> userIds);
}
