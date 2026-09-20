package com.maipiao.user.service;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Revoked-token registry, backed by Redis.
 *
 * <p>Two things make this cheap enough to be worth it:
 * <ul>
 *   <li>Only the {@code jti} is stored, not the whole token.</li>
 *   <li>The TTL equals the token's remaining lifetime, so entries expire on
 *       their own and the set cannot grow without bound.</li>
 * </ul>
 *
 * <p>The gateway is what actually enforces this - user-service only writes the
 * entry. Keeping the write here and the read there means a token stays usable
 * for the microseconds between logout and the next request, which is fine; what
 * matters is that it stops working immediately afterwards rather than living on
 * for the full two hours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtil jwtUtil;

    /**
     * Adds the token to the blacklist for the rest of its life.
     *
     * <p>An already-expired or unparseable token is a no-op: it is already
     * unusable, so writing an entry would only waste memory.
     */
    public void revoke(String rawToken) {
        Claims claims = jwtUtil.parse(rawToken);
        if (claims == null || claims.getId() == null) {
            return;
        }

        long remainingMillis = jwtUtil.getExpireAtMillis(claims) - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            return;
        }

        stringRedisTemplate.opsForValue().set(
                CommonConstants.TOKEN_BLACKLIST_KEY + claims.getId(),
                "1",
                remainingMillis,
                TimeUnit.MILLISECONDS);

        log.debug("token revoked: jti={}, ttl={}ms", claims.getId(), remainingMillis);
    }

    /** @return true when the token has been revoked and must be rejected. */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(CommonConstants.TOKEN_BLACKLIST_KEY + jti));
    }
}
