package com.maipiao.pay.controller;

import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import com.maipiao.pay.entity.Payment;
import com.maipiao.pay.service.PaymentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/pay")
@RequiredArgsConstructor
public class PayController {

    private final PaymentService paymentService;

    @Data
    public static class PrecreateRequest {
        private String orderNo;
        private java.math.BigDecimal amount;
        /** MOCK by default; ALIPAY once that channel exists. */
        private String channel = "MOCK";
    }

    /**
     * Creates a payment and returns where to send the user.
     *
     * <p>Idempotent per order: reloading the payment page reuses the existing
     * payment rather than opening a second one that could be paid in parallel.
     */
    @PostMapping("/precreate")
    public R<Map<String, Object>> precreate(@RequestBody PrecreateRequest request) {
        Long userId = UserContext.require();

        Payment payment = paymentService.createForOrder(
                request.getOrderNo(), userId, request.getAmount(), request.getChannel());

        Map<String, Object> body = new HashMap<>();
        body.put("paymentNo", payment.getPaymentNo());
        body.put("channelTradeNo", payment.getChannelTradeNo());
        body.put("amount", payment.getAmount());
        body.put("expireTime", payment.getExpireTime());
        // Where the client opens the cashier. A real provider returns a URL
        // here too; only the destination differs.
        body.put("cashierUrl", "http://127.0.0.1:9007/mock-pay/cashier/" + payment.getChannelTradeNo());
        return R.ok(body);
    }

    /**
     * The provider's callback.
     *
     * <p>Returns the channel's own response body, not the shared envelope:
     * providers parse this literally, and Alipay wants exactly {@code success}
     * with no JSON around it. Wrapping it would make every callback look
     * failed and be retried forever.
     *
     * <p>Answers HTTP 200 even for a rejected callback. A non-2xx would be read
     * as "try again", which is not what "your signature is wrong" means.
     */
    @PostMapping(value = "/notify/{channel}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notify(@PathVariable String channel,
                         @RequestBody String rawBody,
                         @RequestHeader Map<String, String> headers) {
        return paymentService.handleNotify(channel, rawBody, headers);
    }

    /** Payment status, for the client to poll while the provider processes. */
    @GetMapping("/query/{paymentNo}")
    public R<Payment> query(@PathVariable String paymentNo) {
        UserContext.require();
        return R.ok(paymentService.getByPaymentNo(paymentNo));
    }

    /** Payment for an order, so the order page can find it without an id. */
    @GetMapping("/order/{orderNo}")
    public R<Payment> byOrder(@PathVariable String orderNo) {
        UserContext.require();
        return R.ok(paymentService.getByOrderNo(orderNo));
    }
}
