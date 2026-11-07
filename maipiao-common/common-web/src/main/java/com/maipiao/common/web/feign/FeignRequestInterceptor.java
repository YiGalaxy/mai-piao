package com.maipiao.common.web.feign;

import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.web.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.util.UUID;
import org.slf4j.MDC;

/** 透传用户身份、内部调用标记和 trace id。 */
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
            // 没有 trace 时生成一个，保证跨服务日志可关联。
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(MDC_TRACE_ID, traceId);
        }
        template.header(CommonConstants.HEADER_TRACE_ID, traceId);
    }
}
