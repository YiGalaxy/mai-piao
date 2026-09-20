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
 * 与本地那个替身渠道方通信。
 *
 * <p>签名是按参数名排序后做的真 HMAC-SHA256，也就是真实渠道方采用的形式。用真签名是有
 * 意义的：验签不通过的回调必须被拒绝，而这一点没法用一个原样回显的字段演示出来。
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
     * 校验并解析一条回调。
     *
     * <p>验不过的回调用 {@code signVerified = false} 返回，而不是抛异常：无论哪种情况
     * 调用方都得给渠道方一个答复，而在这里抛异常会把「签名不对」变成「没有响应」，
     * 于是渠道方会去重试一个永远不会成功的请求。
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
        // 替身没有查询接口；在本地环境里回调已经足够可靠。返回「未找到」会让调用方
        // 退回到自己的记录上 —— 面对一个同样答不上来的渠道方，这正是正确的行为。
        return new QueryResult(false, "UNKNOWN", null, null);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        // 替身里的退款立即结清：这里没有人会来争议。调用方照旧走同一套状态机，
        // 所以换成真实渠道方时，只有这个方法会变。
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
