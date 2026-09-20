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
 * 已吊销 token 的登记处，由 Redis 支撑。
 *
 * <p>有两件事让它便宜到值得做：
 * <ul>
 *   <li>只存 {@code jti}，不存整个 token。</li>
 *   <li>TTL 等于 token 剩余的生命，所以条目会自己过期，这个集合不会无限膨胀。</li>
 * </ul>
 *
 * <p>真正执行这件事的是网关 —— user-service 只负责写入那条记录。
 * 写入留在这里、读取放在那边，意味着 token 在登出和下一个请求之间的那几微秒里仍然可用，
 * 这没关系；要紧的是它在那之后立刻失效，而不是继续活满整整两个小时。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtil jwtUtil;

    /**
     * 把 token 加进黑名单，直到它寿命结束。
     *
     * <p>已经过期或解析不了的 token 直接空操作：它本来就不可用了，
     * 写一条记录只会浪费内存。
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

    /** @return token 已被吊销、必须拒绝时为 true。 */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(CommonConstants.TOKEN_BLACKLIST_KEY + jti));
    }
}
