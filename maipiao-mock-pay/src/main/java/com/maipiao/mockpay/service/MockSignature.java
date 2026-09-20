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
 * Signature over the callback payload.
 *
 * <p>HMAC-SHA256 over the parameters sorted by key, which is the shape real
 * providers use - and it is the sorting that matters. Concatenating parameters
 * in whatever order they happen to be in produces a signature that both sides
 * compute differently, and the resulting failures look exactly like a wrong
 * key.
 *
 * <p>Having a real signature here is what makes the verification path worth
 * testing: a request whose signature does not match must be rejected, and that
 * cannot be demonstrated with a field that is simply echoed back.
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

    /** Signs the parameters, excluding any existing {@code sign} entry. */
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
        // Constant-time comparison: a timing-safe check costs nothing here and
        // is the habit worth keeping, even in a stand-in.
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
