package com.maipiao.pay.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.pay.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Service-to-service refund endpoints.
 *
 * <p>Reachable only from inside the cluster: the gateway rejects
 * {@code /api/*&#47;inner/**} before consulting its whitelist. That matters
 * more here than anywhere else - these endpoints move money.
 */
@Slf4j
@RestController
@RequestMapping("/inner/pay")
@RequiredArgsConstructor
public class PayInternalController {

    private final RefundService refundService;

    /**
     * Records a refund and sends it to the provider.
     *
     * <p>Called by order-service after it has moved the order to REFUNDING.
     * That order is not an accident: the intent is written down before the
     * provider is asked, so a provider that is down leaves a retryable refund
     * rather than a customer who pressed a button that did nothing.
     *
     * @return the refund number, existing or newly created
     */
    @PostMapping("/refund")
    public R<String> refund(@RequestParam String orderNo,
                            @RequestParam(required = false) BigDecimal amount) {
        return R.ok(refundService.refund(orderNo, amount));
    }
}
