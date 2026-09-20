package com.maipiao.common.web.context;

import com.maipiao.common.core.constant.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 从网关设置的 {@code X-User-Id} 头里填充 {@link UserContext}，以及 —— 同样重要的
 * —— 事后把它清掉。
 *
 * <p>{@code afterCompletion} 里那次清理不是可选项。Servlet 容器会复用线程，少了它，
 * 用户 A 的请求就可能读到用户 B 遗留在同一个线程上的 id。
 *
 * <p>只有取值是合法数字时才会接受这个头；格式不对时按"匿名"处理，而不是让请求失败，
 * 这样即使某个代理把这个头弄乱了，公开接口也照常工作。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String raw = request.getHeader(CommonConstants.HEADER_USER_ID);
        if (raw != null && !raw.isBlank()) {
            try {
                UserContext.set(Long.valueOf(raw.trim()));
            } catch (NumberFormatException e) {
                // 当作匿名处理；不要为了这个把公开接口搞崩。
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
