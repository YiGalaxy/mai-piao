package com.maipiao.common.web.context;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;

/** 请求级用户身份上下文；请求结束必须清理 ThreadLocal。 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    /**
     * @return 当前用户 id；匿名请求返回 {@code null}。
     */
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

    /** 匿名请求返回 {@code fallback}。 */
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
