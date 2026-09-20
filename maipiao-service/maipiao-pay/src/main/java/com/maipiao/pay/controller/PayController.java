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
        /** 默认 MOCK；等那条渠道接进来之后才是 ALIPAY。 */
        private String channel = "MOCK";
    }

    /**
     * 创建一笔支付，并返回该把用户送到哪里去。
     *
     * <p>按订单幂等：刷新收银台页面复用的是已有的那笔支付，而不是又开一笔可以并行付款的。
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
        // 客户端打开收银台的地方。真实渠道方同样会返回一个 URL，
        // 不同的只是它指向哪里。
        body.put("cashierUrl", "http://127.0.0.1:9007/mock-pay/cashier/" + payment.getChannelTradeNo());
        return R.ok(body);
    }

    /**
     * 渠道方的回调入口。
     *
     * <p>返回的是渠道自己的响应体，而不是统一的封装：渠道方是按字面解析它的，支付宝要的
     * 就是干干净净一个 {@code success}，外面不能套 JSON。套上封装会让每一条回调看起来
     * 都是失败的，然后被无限重试下去。
     *
     * <p>即使回调被拒，也照样回 HTTP 200。非 2xx 会被理解成「请重试」，
     * 而「你的签名不对」并不是这个意思。
     */
    @PostMapping(value = "/notify/{channel}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notify(@PathVariable String channel,
                         @RequestBody String rawBody,
                         @RequestHeader Map<String, String> headers) {
        return paymentService.handleNotify(channel, rawBody, headers);
    }

    /** 支付状态，供客户端在渠道方处理期间轮询。 */
    @GetMapping("/query/{paymentNo}")
    public R<Payment> query(@PathVariable String paymentNo) {
        UserContext.require();
        return R.ok(paymentService.getByPaymentNo(paymentNo));
    }

    /** 某个订单的支付单，让订单页不必知道 id 也能找到它。 */
    @GetMapping("/order/{orderNo}")
    public R<Payment> byOrder(@PathVariable String orderNo) {
        UserContext.require();
        return R.ok(paymentService.getByOrderNo(orderNo));
    }
}
