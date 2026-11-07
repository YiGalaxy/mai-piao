package com.maipiao.common.web.context;

import com.maipiao.common.core.constant.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** 读取网关传入的用户 id，并在请求结束时清理 ThreadLocal。 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        String raw = request.getHeader(CommonConstants.HEADER_USER_ID);
        if (raw != null && !raw.isBlank()) {
            try {
                UserContext.set(Long.valueOf(raw.trim()));
            } catch (NumberFormatException e) {
                // 非法头按匿名请求处理。
                UserContext.clear();
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        UserContext.clear();
    }
}
