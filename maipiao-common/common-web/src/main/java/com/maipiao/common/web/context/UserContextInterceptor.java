package com.maipiao.common.web.context;

import com.maipiao.common.core.constant.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Populates {@link UserContext} from the {@code X-User-Id} header the gateway
 * sets, and - just as importantly - clears it afterwards.
 *
 * <p>The clear in {@code afterCompletion} is not optional. Servlet containers
 * reuse threads, so without it a request from user A could observe user B's id
 * left behind on the same thread.
 *
 * <p>The header is only accepted when it is a valid number; a malformed value
 * is treated as "anonymous" rather than failing the request, so that public
 * endpoints keep working even if a proxy mangles the header.
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String raw = request.getHeader(CommonConstants.HEADER_USER_ID);
        if (raw != null && !raw.isBlank()) {
            try {
                UserContext.set(Long.valueOf(raw.trim()));
            } catch (NumberFormatException e) {
                // Treat as anonymous; do not blow up a public endpoint over it.
                UserContext.clear();
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
