package com.maipiao.common.web.context;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;

/**
 * 在一次请求的期间持有已认证的用户 id。
 *
 * <p>网关校验 JWT，并把用户 id 放在 {@code X-User-Id} 里转发下来。下游服务信任这个
 * 头 —— 剥掉客户端自带的那一份是网关的职责，所以一个从外部直接打到服务的请求伪造
 * 不了它。
 *
 * <p>这是一个 ThreadLocal，所以必须在请求结束时清掉，否则被池化的线程会把上一个
 * 调用方的身份泄漏给下一个。{@link UserContextInterceptor} 两头都管了。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    /** @return 当前用户 id；匿名请求返回 {@code null}。 */
    public static Long get() {
        return CURRENT_USER_ID.get();
    }

    /**
     * @return 当前用户 id
     * @throws BizException 没有用户 id 时，携带 {@link ErrorCode#UNAUTHORIZED}
     */
    public static Long require() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * @return 当前用户 id；请求为匿名时返回 {@code fallback}。
     *         供那些"登录后行为不同、但不强制登录"的接口使用（影片详情、座位图）。
     */
    public static Long getOrDefault(Long fallback) {
        Long userId = CURRENT_USER_ID.get();
        return userId != null ? userId : fallback;
    }

    public static boolean isAuthenticated() {
        return CURRENT_USER_ID.get() != null;
    }

    /** 必须在 finally 块里调用。 */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
