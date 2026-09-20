package com.maipiao.mockpay.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对回调报文签名。
 *
 * <p>对按键名排好序的参数做 HMAC-SHA256，这是真实服务商用的形态 —— 而关键在于排序
 * 这一步。按参数碰巧所在的顺序去拼接，会得出一个两边算出来不一样的签名，由此产生的
 * 失败看起来和密钥写错一模一样。
 *
 * <p>这里有一个真的签名，才让校验路径值得一测：签名对不上的请求必须被拒，而这件事
 * 是没法用一个原样回显的字段来证明的。
 */
@Component
@RequiredArgsConstructor
public class MockSignature {

    private final SecretKeyHolder keyHolder;

    @Component
    public static class SecretKeyHolder {
        @Value("${maipiao.mock-pay.sign-key}")
        private String signKey;

        public String get() {
            return signKey;
        }
    }

    /** 对参数签名，过程中排除掉已有的 {@code sign} 项。 */
    public String sign(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("sign");

        StringBuilder payload = new StringBuilder();
        sorted.forEach((k, v) -> payload.append(k).append('=').append(v).append('&'));
        if (payload.length() > 0) {
            payload.setLength(payload.length() - 1);
        }

        return hmac(payload.toString());
    }

    public boolean verify(Map<String, String> params, String provided) {
        if (provided == null) {
            return false;
        }
        // 常量时间比较：在这里做一次防时序攻击的检查不花什么代价，而这个习惯值得保持，
        // 哪怕只是在一个替身里。
        return java.security.MessageDigest.isEqual(
                sign(params).getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    keyHolder.get().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("could not sign payload", e);
        }
    }
}
