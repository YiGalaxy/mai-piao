package com.maipiao.common.web.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maipiao.common.core.result.R;
import com.maipiao.common.core.util.SnowflakeIdGenerator;
import com.maipiao.common.web.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 将管理员写操作记录到当前服务自己的数据库。
 *
 * <p>审计写入失败只记录告警，不影响已经完成的管理操作。参数先转成 JSON，再递归遮盖 password、token、secret 等字段，避免为了审计把认证材料写进数据库。
 */
@Slf4j
@Aspect
public class AdminAuditLogAspect {

    private static final int MAX_TEXT_LENGTH = 4000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdminAuditLogAspect(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(auditLog)")
    public Object record(ProceedingJoinPoint joinPoint, AdminAuditLog auditLog) throws Throwable {
        long started = System.nanoTime();
        Object result = null;
        Throwable failure = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            writeAudit(
                    joinPoint,
                    auditLog,
                    result,
                    failure,
                    (System.nanoTime() - started) / 1_000_000L);
        }
    }

    private void writeAudit(
            ProceedingJoinPoint joinPoint,
            AdminAuditLog auditLog,
            Object result,
            Throwable failure,
            long durationMs) {
        try {
            HttpServletRequest request = currentRequest();
            Integer resultCode = null;
            String resultMessage = "";
            boolean success = failure == null;
            if (result instanceof R<?> response) {
                success = success && response.isSuccess();
                resultCode = response.getCode();
                resultMessage = response.getMessage();
            }

            String error =
                    failure == null
                            ? ""
                            : truncate(
                                    failure.getClass().getSimpleName()
                                            + ": "
                                            + String.valueOf(failure.getMessage()),
                                    1000);
            jdbcTemplate.update(
                    """
                    INSERT INTO t_admin_audit_log
                      (id, operator_id, action_name, resource_type, http_method, request_uri,
                       target_args, success, result_code, result_message, error_message,
                       client_ip, user_agent, duration_ms, create_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    SnowflakeIdGenerator.next(),
                    UserContext.get(),
                    truncate(auditLog.action(), 64),
                    truncate(auditLog.resource(), 64),
                    request == null ? "" : truncate(request.getMethod(), 16),
                    request == null ? "" : truncate(request.getRequestURI(), 255),
                    truncate(argumentsJson(joinPoint), MAX_TEXT_LENGTH),
                    success ? 1 : 0,
                    resultCode,
                    truncate(resultMessage, 255),
                    error,
                    request == null ? "" : truncate(request.getRemoteAddr(), 64),
                    request == null ? "" : truncate(request.getHeader("User-Agent"), 255),
                    durationMs,
                    LocalDateTime.now());
        } catch (Exception auditFailure) {
            // 审计是旁路能力，不能让审计表故障阻断管理员已经完成的操作。
            log.warn("could not persist administrator audit log", auditFailure);
        }
    }

    private String argumentsJson(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String[] names = signature.getParameterNames();
            Map<String, Object> arguments = new LinkedHashMap<>();
            Object[] values = joinPoint.getArgs();
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value instanceof HttpServletRequest
                        || value instanceof jakarta.servlet.http.HttpServletResponse) {
                    continue;
                }
                arguments.put(
                        names != null && i < names.length && names[i] != null
                                ? names[i]
                                : "arg" + i,
                        value);
            }
            JsonNode node = objectMapper.valueToTree(arguments);
            redact(node);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.fieldNames()
                    .forEachRemaining(
                            name -> {
                                if (isSensitive(name)) {
                                    object.put(name, "***");
                                } else {
                                    redact(object.get(name));
                                }
                            });
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::redact);
        }
    }

    private boolean isSensitive(String name) {
        String normalized = name.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("authorization");
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
