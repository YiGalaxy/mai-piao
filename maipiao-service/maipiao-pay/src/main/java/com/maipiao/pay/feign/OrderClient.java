package com.maipiao.pay.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;

/**
 * 调用 order-service，执行 G2 和 G3 中订单那一侧的动作。
 *
 * <p>接口里的方法分两类，混在一起是因为它们都落在同一个 controller 上：
 *
 * <ul>
 *   <li>{@code markPaid}、{@code issueTickets}、{@code markRefunded} 是<b>事务分支</b>。
 *       它们失败时抛异常 —— 正是这一点让 Seata 能把全局事务回滚。一个什么都没改
 *       却返回成功的分支，会提交出一笔没有票的支付。</li>
 *   <li>{@code confirmSeats}、{@code releaseRefundedSeats} <b>不是</b>分支。它们清的是
 *       Redis 里的占用，而 Redis 回滚不了，所以它们必须在事务提交之后单独调用，
 *       各自的 Javadoc 里写了原因。</li>
 * </ul>
 *
 * <p>（这段原本写的是「这些方法都是事务分支」。接口后来从两个方法长到了五个，
 * 而注释没有跟着改 —— 一句在写下时正确、后来变成错的话，比一开始就没写更糟。）
 */
@FeignClient(name = "maipiao-order", path = "/inner")
public interface OrderClient {

    /** G2 分支：订单转 PAID。 */
    @PostMapping("/{orderNo}/paid")
    R<Void> markPaid(@RequestParam("orderNo") String orderNo,
                     @RequestParam("payTime") LocalDateTime payTime);

    /**
     * G2 分支：出票。
     *
     * <p>与「标记已支付」分开，是因为这两件事可以各自独立地失败 —— 订单更新成功了，
     * 出票却可能失败 —— 把它们拆开，日志里一眼就能看出坏的是哪一半。
     */
    @PostMapping("/{orderNo}/issue-tickets")
    R<Void> issueTickets(@RequestParam("orderNo") String orderNo,
                         @RequestParam("paymentNo") String paymentNo);

    /**
     * 把 Redis 里的座位占用标记为已售。
     *
     * <p>它不是事务分支，而是刻意在全局事务提交之后单独调用的。Redis 参与不了事务，
     * 所以写在事务里的标记会在回滚后残留，把座位钉成已售、后面却没有已支付的订单 ——
     * 而那条拒绝释放标记为 {@code SOLD:} 的座位的释放路径，会让它再也放不出来。
     */
    @PostMapping("/{orderNo}/confirm-seats")
    R<Void> confirmSeats(@RequestParam("orderNo") String orderNo);

    /** G3 分支：订单转 REFUNDED。 */
    @PostMapping("/{orderNo}/refund-success")
    R<Void> markRefunded(@RequestParam("orderNo") String orderNo,
                         @RequestParam("refundAmount") java.math.BigDecimal refundAmount);

    /**
     * 清掉 Redis 里的座位占用，退款成功之后单独调一次。
     *
     * <p>不在事务里。Redis 回滚不了，事务里写下的清除会在回滚后留下来，把座位挂到
     * 市场上，而订单还读作「已支付」—— 那意味着同一个座位被卖两次。
     */
    @PostMapping("/{orderNo}/release-refunded-seats")
    R<Void> releaseRefundedSeats(@RequestParam("orderNo") String orderNo);
}
