package com.maipiao.common.web.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.result.R;
import com.maipiao.common.web.context.UserContext;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminAuditLogAspectTest {

    private final RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    private final AdminAuditLogAspect aspect =
            new AdminAuditLogAspect(jdbcTemplate, new ObjectMapper());

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void recordsSuccessfulResultAndRedactsSensitiveArguments() throws Throwable {
        UserContext.set(7L);
        ProceedingJoinPoint joinPoint =
                joinPoint(
                        new Object[] {Map.of("token", "secret-value", "name", "operator")},
                        R.ok(),
                        null);

        Object result = aspect.record(joinPoint, annotation());

        assertTrue(result instanceof R<?>);
        assertEquals(1, jdbcTemplate.calls);
        assertEquals(7L, jdbcTemplate.arguments[1]);
        assertEquals(1, jdbcTemplate.arguments[7]);
        String targetArgs = (String) jdbcTemplate.arguments[6];
        assertTrue(targetArgs.contains("\"token\":\"***\""));
        assertFalse(targetArgs.contains("secret-value"));
    }

    @Test
    void recordsBusinessExceptionAsFailureAndRethrowsIt() throws Throwable {
        UserContext.set(7L);
        IllegalStateException failure = new IllegalStateException("business rejected");
        ProceedingJoinPoint joinPoint = joinPoint(new Object[] {"request"}, null, failure);

        assertThrows(IllegalStateException.class, () -> aspect.record(joinPoint, annotation()));

        assertEquals(1, jdbcTemplate.calls);
        assertEquals(0, jdbcTemplate.arguments[7]);
        assertTrue(((String) jdbcTemplate.arguments[10]).contains("business rejected"));
    }

    private ProceedingJoinPoint joinPoint(Object[] args, Object result, Throwable failure)
            throws Throwable {
        ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
        MethodSignature signature = Mockito.mock(MethodSignature.class);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getParameterNames()).thenReturn(new String[] {"request"});
        when(joinPoint.proceed())
                .thenAnswer(
                        invocation -> {
                            if (failure != null) {
                                throw failure;
                            }
                            return result;
                        });
        return joinPoint;
    }

    private AdminAuditLog annotation() throws NoSuchMethodException {
        return AdminAuditLogAspectTest.class
                .getDeclaredMethod("annotatedTarget")
                .getAnnotation(AdminAuditLog.class);
    }

    @AdminAuditLog(action = "TEST", resource = "USER")
    private void annotatedTarget() {}

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private int calls;
        private Object[] arguments;

        @Override
        public int update(String sql, Object... args) {
            calls++;
            arguments = args;
            return 1;
        }
    }
}
