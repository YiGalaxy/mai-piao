package com.maipiao.order.feign;

import com.maipiao.common.core.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * Calls pay-service to get money back.
 *
 * <p>One call, and it is deliberately a request rather than a command: the
 * refund is recorded and sent to the provider, and the order is told the
 * outcome separately. A synchronous "the money is back" would be a lie the
 * moment a provider answered slowly, or accepted the request and settled it
 * an hour later.
 */
@FeignClient(name = "maipiao-pay", path = "/inner/pay")
public interface PayClient {

    /**
     * Records and submits a refund for an order.
     *
     * <p>Idempotent per order: a second request returns the existing refund
     * rather than issuing another payment to the customer.
     *
     * @return the refund number, whether this call created it or not
     */
    @PostMapping("/refund")
    R<String> applyRefund(@RequestParam("orderNo") String orderNo,
                          @RequestParam("amount") BigDecimal amount);
}
