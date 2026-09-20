package com.maipiao.mockpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按需把回调投递给 pay-service，形态由操作者说了算。
 *
 * <p>真实服务商不会应要求去做、而本服务可以做的那些事：
 *
 * <ul>
 *   <li>把同一个回调发不止一次</li>
 *   <li>先发一条失败、再发一条成功，顺序颠倒</li>
 *   <li>为一个已经被取消的订单发回调</li>
 *   <li>发一条签名故意写错的回调</li>
 * </ul>
 *
 * <p>上面每一条，考验的都是 pay-service 里不同的一道防线。一个只见过格式正确、顺序
 * 正常、恰好一次的回调的测试集，对其中任何一条都证明不了什么。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockNotifySender {

    private final MockSignature signature;
    private final ObjectMapper objectMapper;

    @Value("${maipiao.mock-pay.notify-url}")
    private String notifyUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public record NotifyOutcome(boolean delivered, int httpStatus, String response, String detail) {
    }

    /**
     * 发送一条回调。
     *
     * @param status         SUCCESS 或 FAILED
     * @param amount         服务商声称收到的金额
     * @param breakSignature 发一个故意无效的签名，用来证明校验路径会拒掉它
     */
    public NotifyOutcome send(String channelTradeNo, String paymentNo, String orderNo,
                              BigDecimal amount, String status,
                              boolean breakSignature) {

        Map<String, String> params = new LinkedHashMap<>();
        params.put("channelTradeNo", channelTradeNo);
        params.put("paymentNo", paymentNo);
        params.put("orderNo", orderNo);
        params.put("amount", amount == null ? "0" : amount.toPlainString());
        params.put("status", status);
        params.put("notifyTime", String.valueOf(System.currentTimeMillis()));

        String sign = signature.sign(params);
        if (breakSignature) {
            // 把最后一个字符翻掉。它像真签名到足以通过任何长度检查，又错到足以让校验
            // 必须失败。
            sign = sign.substring(0, sign.length() - 1) + (sign.endsWith("0") ? "1" : "0");
        }
        params.put("sign", sign);

        return post(params);
    }

    /** 把同一条回调尽可能快地发 {@code times} 次。 */
    public java.util.List<NotifyOutcome> sendRepeated(String channelTradeNo, String paymentNo,
                                                      String orderNo, BigDecimal amount,
                                                      String status, int times) {
        java.util.List<NotifyOutcome> outcomes = new java.util.ArrayList<>();
        for (int i = 0; i < times; i++) {
            outcomes.add(send(channelTradeNo, paymentNo, orderNo, amount, status, false));
        }
        return outcomes;
    }

    public NotifyOutcome sendAfter(String channelTradeNo, String paymentNo, String orderNo,
                                   BigDecimal amount, String status, long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return send(channelTradeNo, paymentNo, orderNo, amount, status, false);
    }

    private NotifyOutcome post(Map<String, String> params) {
        try {
            String body = objectMapper.writeValueAsString(params);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(notifyUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("callback delivered: paymentNo={}, status={}, http={}, response={}",
                    params.get("paymentNo"), params.get("status"),
                    response.statusCode(), truncate(response.body()));

            return new NotifyOutcome(true, response.statusCode(), response.body(), null);

        } catch (Exception e) {
            log.error("callback delivery failed: paymentNo={}", params.get("paymentNo"), e);
            return new NotifyOutcome(false, -1, null, e.getMessage());
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
