package com.maipiao.common.core.util;

import com.maipiao.common.core.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 的签发与校验。
 *
 * <p>设计说明：token 是无状态的，所以登出没法简单地"删掉"它。项目维护一份以 token
 * 的 {@code jti} 为键的 Redis 黑名单，由网关去查。这是一次刻意的取舍 —— 每个已认证
 * 请求多一次 Redis 查询 —— 换来的是立即吊销，而不是干等 token 过期。
 */
@Slf4j
public class JwtUtil {

    public static final String CLAIM_PHONE = "phone";
    public static final String CLAIM_ROLE = "role";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        byte[] secretBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "maipiao.jwt.secret must be at least 32 bytes for HS256; got "
                            + secretBytes.length);
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    // ------------------------------------------------------------
    // 签发
    // ------------------------------------------------------------

    public String generateUserToken(Long userId, String phone) {
        return generate(userId, phone, ROLE_USER, properties.getExpireMinutes());
    }

    public String generateAdminToken(Long userId, String phone) {
        return generate(userId, phone, ROLE_ADMIN, properties.getAdminExpireMinutes());
    }

    public String generate(Long userId, String phone, String role, long expireMinutes) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expireMinutes * 60);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                // jti 给登出黑名单提供了一个稳定的抓手
                .id(UUID.randomUUID().toString().replace("-", ""))
                .claim(CLAIM_PHONE, phone)
                .claim(CLAIM_ROLE, role)
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // ------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------

    /**
     * @return token 的 claims；token 无效或已过期时返回 {@code null}。
     *         调用方把 null 当作"未认证"处理并返回 401。
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String raw = stripBearer(token);
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(raw)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("token expired: {}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            // 涵盖签名不对、token 格式错乱、issuer 不对这几种情况。
            log.debug("token rejected: {}", e.getMessage());
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        if (claims == null || claims.getSubject() == null) {
            return null;
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getRole(Claims claims) {
        if (claims == null) {
            return null;
        }
        return claims.get(CLAIM_ROLE, String.class);
    }

    public boolean isAdmin(Claims claims) {
        return ROLE_ADMIN.equals(getRole(claims));
    }

    /** token 过期的 epoch 毫秒时间戳，用来定黑名单的 TTL。 */
    public long getExpireAtMillis(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration == null ? System.currentTimeMillis() : expiration.getTime();
    }

    public String stripBearer(String token) {
        if (token == null) {
            return null;
        }
        if (token.startsWith("Bearer ")) {
            return token.substring(7).trim();
        }
        return token.trim();
    }
}
