package com.maoyan.common.web.config;

import com.maoyan.common.web.context.UserContextInterceptor;
import com.maoyan.common.web.exception.GlobalExceptionHandler;
import com.maoyan.common.web.feign.FeignRequestInterceptor;
import com.maoyan.common.web.util.JwtUtil;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
 * would have to sit under {@code com.maoyan} for the shared beans to appear.
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
     * Only created when OpenFeign is on the classpath. Services that never call
     * another service do not need it and should not be forced to carry Feign.
     */
    @Bean
    @ConditionalOnClass(RequestInterceptor.class)
    @ConditionalOnMissingBean
    public FeignRequestInterceptor feignRequestInterceptor() {
        return new FeignRequestInterceptor();
    }

    /**
     * Reads X-User-Id into {@link com.maoyan.common.web.context.UserContext}
     * for every request.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/**")
                .order(0);
    }
}
