package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 调用 seat-service。
 *
 * <p>注意这里两个操作都在分布式事务<b>之外</b>。座位状态住在 Redis 里，
 * 而 Seata 管不着 Redis，所以 Redis 那一侧靠显式调用加显式补偿来驱动：
 *
 * <ul>
 *   <li>{@link #confirm} 在 G2 提交之后运行。漏掉它，座位就只被标成「已锁定」，
 *       超时清扫最终会把一个已经付过款的座位释放掉。对账任务会重试它。</li>
 *   <li>{@link #release} 从 G1 的失败路径上运行，而不是从 G1 里面。</li>
 * </ul>
 *
 * <p>路径是 {@code /inner/seat}，与实际承接这两个调用的 controller 对得上。
 * 对外的座位 API 在 {@code /seat}，而这个客户端的早期版本用了那个前缀 ——
 * 每一次调用都返回 500，而代码读起来却像是能跑的。
 */
@FeignClient(name = "maipiao-seat", path = "/inner/seat")
public interface SeatClient {

    /** 把一次占用标记为已售出。订单支付之后调用。 */
    @PostMapping("/confirm")
    R<Void> confirm(@RequestParam Long sessionId, @RequestParam String orderNo);

    /**
     * 把一次占用还回去。从 G1 的失败路径上调用，取消订单时也调用。
     *
     * <p>在 seat 一侧是幂等的：只有归属标记仍匹配本订单的座位才会被清掉，
     * 所以跑两次 —— 或者在订单已经被取消之后再跑 —— 不会多释放任何东西。
     */
    @PostMapping("/release")
    R<Integer> release(@RequestParam Long sessionId,
                       @RequestParam String orderNo,
                       @RequestParam boolean includeSold);

    /** 除退款之外，到处都在用的那种占用释放。 */
    default R<Integer> release(Long sessionId, String orderNo) {
        return release(sessionId, orderNo, false);
    }

    /**
     * 本订单是否仍然持有它凭证上写明的那些座位，以及它们值多少钱。
     *
     * <p>在 G1 开启之前调用。凭证是锁座那一刻发出的，并不会随占用一起过期，
     * 所以不加核验就接受它，等于允许在一组早已被别人拿走的座位上把订单下出来。
     *
     * <p>返回的是 map 而不是有类型的响应：{@code {"held": boolean,
     * "amount": decimal, "seats": [{"seatIndex", "tierId", "price"}]}}。
     * 价格由这次调用带回来，因为它不能来自客户端，而座位到票档的映射本来就住在对面。
     */
    @PostMapping("/verify")
    R<Map<String, Object>> verify(@RequestParam Long sessionId,
                                  @RequestParam String orderNo,
                                  @RequestParam List<Integer> seatIndexes);
}
