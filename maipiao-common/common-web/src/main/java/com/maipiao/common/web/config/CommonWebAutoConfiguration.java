package com.maipiao.common.web.config;

import com.maipiao.common.core.config.JwtProperties;
import com.maipiao.common.core.util.JwtUtil;
import com.maipiao.common.web.context.UserContextInterceptor;
import com.maipiao.common.web.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the common-web beans into any service that depends on this module.
 *
 * <p>Registered through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * rather than picked up by component scanning. That keeps services free to
 * choose their own base package - if this relied on scanning, every service
 * would have to sit under {@code com.maipiao} for the shared beans to appear.
 *
 * <p>Guarded with {@link ConditionalOnWebApplication} for SERVLET: the gateway
 * is a WebFlux application and must not try to register a Servlet interceptor.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
public class CommonWebAutoConfiguration implements WebMvcConfigurer {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtUtil jwtUtil(JwtProperties jwtProperties) {
        return new JwtUtil(jwtProperties);
    }

    /**
     * BCrypt with the default strength (10). Passwords are never stored or
     * logged in plaintext anywhere in this project.
     */
    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The Feign interceptor is registered by {@link CommonWebFeignConfiguration}
     * instead. Declaring it here with a method-level {@code @ConditionalOnClass}
     * does not work: this class still has to be loaded to evaluate the
     * condition, and loading it resolves the method signature whose return type
     * implements {@code feign.RequestInterceptor} - which is absent on services
     * that do not use OpenFeign.
     */

    /**
     * Reads X-User-Id into {@link com.maipiao.common.web.context.UserContext}
     * for every request.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/**")
                .order(0);
    }
}
