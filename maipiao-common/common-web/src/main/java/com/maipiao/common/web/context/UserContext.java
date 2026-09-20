package com.maipiao.common.web.context;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;

/**
 * Holds the authenticated user id for the duration of one request.
 *
 * <p>The gateway verifies the JWT and forwards the user id in
 * {@code X-User-Id}. Downstream services trust that header - it is the
 * gateway's job to strip any client-supplied copy, so a request that reached
 * a service directly from outside cannot forge it.
 *
 * <p>This is a ThreadLocal, so it must be cleared at the end of the request or
 * a pooled thread will leak the previous caller's identity into the next one.
 * {@link UserContextInterceptor} handles both ends.
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    /** @return the current user id, or {@code null} for anonymous requests. */
    public static Long get() {
        return CURRENT_USER_ID.get();
    }

    /**
     * @return the current user id
     * @throws BizException with {@link ErrorCode#UNAUTHORIZED} when there is none
     */
    public static Long require() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * @return the current user id, or {@code fallback} when the request is anonymous.
     *         Used by endpoints that behave differently when signed in but do not
     *         require it (film detail, seat map).
     */
    public static Long getOrDefault(Long fallback) {
        Long userId = CURRENT_USER_ID.get();
        return userId != null ? userId : fallback;
    }

    public static boolean isAuthenticated() {
        return CURRENT_USER_ID.get() != null;
    }

    /** Must be called in a finally block. */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
