package com.maipiao.common.web.util;

import com.maipiao.common.web.config.JwtProperties;
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
 * JWT issuing and verification.
 *
 * <p>Design note: tokens are stateless, so logging out cannot simply "delete"
 * one. The project keeps a Redis blacklist keyed on the token's {@code jti};
 * the gateway consults it. That is a deliberate trade - a Redis lookup on every
 * authenticated request - to get immediate revocation instead of waiting for the
 * token to expire.
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
    // issuing
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
                // jti gives us a stable handle for the logout blacklist
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
    // parsing
    // ------------------------------------------------------------

    /**
     * @return the token claims, or {@code null} if the token is invalid or expired.
     *         Callers treat null as "not authenticated" and return 401.
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
            // Covers bad signature, malformed token, wrong issuer.
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

    /** Epoch millis at which the token expires, used for the blacklist TTL. */
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
