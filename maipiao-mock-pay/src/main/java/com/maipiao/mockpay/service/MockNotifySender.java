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
 * Delivers callbacks to pay-service, on demand and in whatever shape the
 * operator asks for.
 *
 * <p>What a real provider will not do on request, and what this one can:
 *
 * <ul>
 *   <li>send the same callback more than once</li>
 *   <li>send a failure and then a success, out of order</li>
 *   <li>send a callback for an order that has already been cancelled</li>
 *   <li>send one with a deliberately wrong signature</li>
 * </ul>
 *
 * <p>Each of those exercises a different guard in pay-service. A test suite
 * that only ever sees well-formed, in-order, exactly-once callbacks proves
 * nothing about any of them.
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
     * Sends one callback.
     *
     * @param status        SUCCESS or FAILED
     * @param amount        the amount the provider claims was paid
     * @param breakSignature send a deliberately invalid signature, to prove the
     *                       verification path rejects it
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
            // Flip the last character. Close enough to a real signature to get
            // past any length check, wrong enough that verification must fail.
            sign = sign.substring(0, sign.length() - 1) + (sign.endsWith("0") ? "1" : "0");
        }
        params.put("sign", sign);

        return post(params);
    }

    /** Sends the same callback {@code times} times, as fast as it can. */
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
