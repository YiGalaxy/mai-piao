package com.maipiao.common.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings, bound from {@code maipiao.jwt.*}.
 *
 * <p>The secret must be at least 32 bytes - HS256 keys shorter than that are
 * rejected by the JWT spec, and jjwt enforces it with a runtime exception that
 * only shows up on the first token operation.
 *
 * <p>Override it per environment: {@code MAIPIAO_JWT_SECRET}. The default here
 * exists so the project runs out of the box locally.
 */
@ConfigurationProperties(prefix = "maipiao.jwt")
public class JwtProperties {

    /**
     * Shared HMAC secret. The gateway signs nothing (user-service does), but it
     * verifies, so every service that touches a token needs the same value.
     */
    private String secret = "maipiao-ticket-local-dev-secret-key-change-me-in-production-2026";

    /** Token lifetime in minutes. */
    private long expireMinutes = 120;

    /** Token lifetime for the admin console, in minutes. Shorter by design. */
    private long adminExpireMinutes = 60;

    private String issuer = "maipiao-ticket";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }

    public void setExpireMinutes(long expireMinutes) {
        this.expireMinutes = expireMinutes;
    }

    public long getAdminExpireMinutes() {
        return adminExpireMinutes;
    }

    public void setAdminExpireMinutes(long adminExpireMinutes) {
        this.adminExpireMinutes = adminExpireMinutes;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
