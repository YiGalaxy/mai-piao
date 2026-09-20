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

    /** G3 branch: order to REFUNDED. */
    @PostMapping("/{orderNo}/refund-success")
    R<Void> markRefunded(@RequestParam("orderNo") String orderNo,
                         @RequestParam("refundAmount") java.math.BigDecimal refundAmount);
}
