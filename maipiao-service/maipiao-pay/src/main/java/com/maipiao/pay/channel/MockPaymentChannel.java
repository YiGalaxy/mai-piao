package com.maipiao.pay.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Talks to the local stand-in provider.
 *
 * <p>The signature is real HMAC-SHA256 over parameters sorted by key, which is
 * the shape actual providers use. Having a real one matters: a callback whose
 * signature does not check out must be rejected, and that cannot be
 * demonstrated with a field that is simply echoed back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPaymentChannel implements PaymentChannel {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Value("${maipiao.pay.mock.gateway-url:http://127.0.0.1:9007}")
    private String gatewayUrl;

    @Value("${maipiao.pay.mock.sign-key:maipiao-mock-pay-sign-key-2026}")
    private String signKey;

    @Override
    public ChannelType type() {
        return ChannelType.MOCK;
    }

    @Override
    public PrepayResult prepay(PrepayCommand command) {
        Map<String, Object> body = new HashMap<>();
        body.put("paymentNo", command.paymentNo());
        body.put("orderNo", command.orderNo());
        body.put("amount", command.amount());

        try {
            String json = restTemplate.postForObject(
                    gatewayUrl + "/mock-pay/precreate", jsonEntity(body), String.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(json, Map.class);

            return new PrepayResult(
                    String.valueOf(response.get("channelTradeNo")),
                    String.valueOf(response.get("cashierUrl")),
                    json);
        } catch (Exception e) {
            log.error("could not create payment at the gateway: paymentNo={}", command.paymentNo(), e);
            throw new IllegalStateException("支付渠道暂时不可用", e);
        }
    }

    /**
     * Verifies and parses a callback.
     *
     * <p>An unverifiable callback is returned with {@code signVerified = false}
     * rather than thrown: the caller has to answer the provider either way, and
     * an exception here would turn "bad signature" into "no response", which
     * makes the provider retry a request that will never succeed.
     */
    @Override
    public NotifyResult parseNotify(String rawBody, Map<String, String> headers) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(rawBody, Map.class);

            Map<String, String> params = new TreeMap<>();
            payload.forEach((k, v) -> params.put(k, v == null ? "" : String.valueOf(v)));

            boolean verified = verify(params, params.get("sign"));
            if (!verified) {
                log.warn("callback signature did not verify: paymentNo={}", params.get("paymentNo"));
            }

            return new NotifyResult(
                    verified,
                    params.get("paymentNo"),
                    params.get("orderNo"),
                    params.get("channelTradeNo"),
                    parseAmount(params.get("amount")),
                    params.getOrDefault("status", "FAILED"),
                    rawBody);
        } catch (Exception e) {
            log.error("could not parse callback", e);
            return new NotifyResult(false, null, null, null, null, "FAILED", rawBody);
        }
    }

    @Override
    public QueryResult query(String paymentNo) {
        // The stand-in has no query endpoint; callbacks are reliable enough in
        // a local setup. Returning "not found" makes the caller fall back to
        // its own record, which is the correct behaviour against a provider
        // that cannot answer either.
        return new QueryResult(false, "UNKNOWN", null, null);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        // Refunds settle immediately in the stand-in: there is nobody to
        // dispute them. The caller still goes through the same state machine,
        // so swapping in a real provider changes only this method.
        log.info("mock refund accepted: refundNo={}, amount={}", command.refundNo(), command.amount());
        return new RefundResult(true, "MOCKREFUND" + System.currentTimeMillis(), "accepted");
    }

    @Override
    public String successResponse() {
        return "success";
    }

    @Override
    public String failResponse() {
        return "failure";
    }

    // ------------------------------------------------------------

    private HttpEntity<String> jsonEntity(Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
    }

    private boolean verify(Map<String, String> params, String provided) {
        if (provided == null) {
            return false;
        }
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("sign");

        StringBuilder payload = new StringBuilder();
        sorted.forEach((k, v) -> payload.append(k).append('=').append(v).append('&'));
        if (payload.length() > 0) {
            payload.setLength(payload.length() - 1);
        }

        String expected = hmac(payload.toString());
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("could not compute signature", e);
        }
    }

    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
