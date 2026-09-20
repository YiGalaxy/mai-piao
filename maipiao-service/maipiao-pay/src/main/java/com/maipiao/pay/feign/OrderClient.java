package com.maipiao.pay.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;

/**
 * Calls order-service for the order-side branches of G2 and G3.
 *
 * <p>Both methods are transactional branches and throw on failure, which is
 * what lets Seata roll the global transaction back. A branch that returned
 * success without changing anything would commit a payment with no tickets.
 */
@FeignClient(name = "maipiao-order", path = "/inner")
public interface OrderClient {

    /** G2 branch: order to PAID. */
    @PostMapping("/{orderNo}/paid")
    R<Void> markPaid(@RequestParam("orderNo") String orderNo,
                     @RequestParam("payTime") LocalDateTime payTime);

    /**
     * G2 branch: issue the tickets.
     *
     * <p>Separate from marking paid because the two can fail independently -
     * the order can be updated and ticket generation can fail - and keeping
     * them apart makes the failing half obvious in the logs.
     */
    @PostMapping("/{orderNo}/issue-tickets")
    R<Void> issueTickets(@RequestParam("orderNo") String orderNo,
                         @RequestParam("paymentNo") String paymentNo);

    /**
     * Marks the Redis seat hold as sold.
     *
     * <p>Not a branch, and deliberately called on its own after the global
     * transaction has committed. Redis cannot participate in the transaction,
     * so a marker written inside it would survive a rollback and leave the seat
     * pinned as sold with no paid order behind it - and the release path, which
     * refuses to free a seat marked {@code SOLD:}, would never let it go.
     */
    @PostMapping("/{orderNo}/confirm-seats")
    R<Void> confirmSeats(@RequestParam("orderNo") String orderNo);

    /** G3 branch: order to REFUNDED. */
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
