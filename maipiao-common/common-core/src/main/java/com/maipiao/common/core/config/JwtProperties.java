package com.maipiao.common.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置，从 {@code maipiao.jwt.*} 绑定。
 *
 * <p>secret 至少要 32 字节 —— 短于这个长度的 HS256 密钥会被 JWT 规范拒绝，jjwt 用
 * 一个运行时异常来强制这条规则，而这个异常要到第一次操作 token 时才会冒出来。
 *
 * <p>按环境覆盖它：{@code MAIPIAO_JWT_SECRET}。这里留的默认值是为了让项目在本地
 * 开箱即跑。
 */
@ConfigurationProperties(prefix = "maipiao.jwt")
public class JwtProperties {

    /**
     * 共用的 HMAC 密钥。网关不签发任何东西（签发的是 user-service），但它要校验，
     * 所以每个会碰到 token 的服务都需要同一个值。
     */
    private String secret = "maipiao-ticket-local-dev-secret-key-change-me-in-production-2026";

    /** token 有效期，单位分钟。 */
    private long expireMinutes = 120;

    /** 管理后台的 token 有效期，单位分钟。刻意做得更短。 */
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
