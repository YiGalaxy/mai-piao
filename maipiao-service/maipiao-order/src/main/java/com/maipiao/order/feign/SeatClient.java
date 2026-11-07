package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Redis 核验在创建执行权保护下调用；确认/释放在数据库事务完成后执行。 */
@FeignClient(name = "maipiao-seat", path = "/inner/seat")
public interface SeatClient {

    /** 把一次占用标记为已售出。订单支付之后调用。 */
    @PostMapping("/confirm")
    R<Void> confirm(@RequestParam Long sessionId, @RequestParam String orderNo);

    /**
     * 把一次占用还回去。在预约关闭或订单取消已确认后调用；创建失败不立即释放。
     *
     * <p>在 seat 一侧是幂等的：只有归属标记仍匹配本订单的座位才会被清掉， 所以跑两次 —— 或者在订单已经被取消之后再跑 —— 不会多释放任何东西。
     */
    @PostMapping("/release")
    R<Integer> release(
            @RequestParam Long sessionId,
            @RequestParam String orderNo,
            @RequestParam boolean includeSold);

    /** 除退款之外，到处都在用的那种占用释放。 */
    default R<Integer> release(Long sessionId, String orderNo) {
        return release(sessionId, orderNo, false);
    }

    /**
     * 本订单是否仍然持有它凭证上写明的那些座位，以及它们值多少钱。
     *
     * <p>在取得预约执行权后调用。凭证是锁座那一刻发出的，并不会随占用一起过期， 所以不加核验就接受它，等于允许在一组早已被别人拿走的座位上把订单下出来。
     *
     * <p>返回的是 map 而不是有类型的响应：{@code {"held": boolean, "amount": decimal, "seats": [{"seatIndex",
     * "tierId", "price"}]}}。 价格由这次调用带回来，因为它不能来自客户端，而座位到票档的映射本来就住在对面。
     */
    @PostMapping("/verify")
    R<Map<String, Object>> verify(
            @RequestParam Long sessionId,
            @RequestParam String orderNo,
            @RequestParam List<Integer> seatIndexes);
}
