package com.maipiao.common.web.feign;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.web.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 把调用方的身份和 trace id 传递到对外的 Feign 调用上。
 *
 * <p>没有它，从 order-service 打到 movie-service 的调用会不带 {@code X-User-Id}
 * 到达，movie-service 要么拒绝它，要么 —— 更糟 —— 把它当成匿名请求从而跳过权限
 * 检查。
 *
 * <p>{@code X-Internal-Call} 用来标识这是服务间调用。网关会从一切外部来的请求上
 * 删掉这个头，所以外部客户端没法自称是内部调用。
 */
public class FeignRequestInterceptor implements RequestInterceptor {

    private static final String MDC_TRACE_ID = "traceId";

    @Override
    public void apply(RequestTemplate template) {
        Long userId = UserContext.get();
        if (userId != null) {
            template.header(CommonConstants.HEADER_USER_ID, String.valueOf(userId));
        }

        template.header(CommonConstants.HEADER_INTERNAL_CALL, "true");

        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            // 上游没有 trace：起一个，好让下游的日志行仍然串得起来。
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(MDC_TRACE_ID, traceId);
        }
        template.header(CommonConstants.HEADER_TRACE_ID, traceId);
    }
}
