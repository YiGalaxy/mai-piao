package com.maoyan.common.web.feign;

import com.maoyan.common.core.constant.CommonConstants;
import com.maoyan.common.web.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Propagates the caller's identity and trace id onto outgoing Feign calls.
 *
 * <p>Without this, a call from order-service to movie-service arrives with no
 * {@code X-User-Id}, and movie-service either rejects it or - worse - treats it
 * as an anonymous request and skips a permission check.
 *
 * <p>{@code X-Internal-Call} marks it as service-to-service. The gateway
 * deletes this header from anything arriving from outside, so an external
 * client cannot claim to be internal.
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
            // No upstream trace: start one so the downstream log lines still link up.
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(MDC_TRACE_ID, traceId);
        }
        template.header(CommonConstants.HEADER_TRACE_ID, traceId);
    }
}
