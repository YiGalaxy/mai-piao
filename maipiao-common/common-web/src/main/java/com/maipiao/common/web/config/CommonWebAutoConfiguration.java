package com.maipiao.common.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.maipiao.common.core.config.JwtProperties;
import com.maipiao.common.core.util.JwtUtil;
import com.maipiao.common.web.audit.AdminAuditLogAspect;
import com.maipiao.common.web.context.UserContextInterceptor;
import com.maipiao.common.web.exception.GlobalExceptionHandler;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** common-web 的 Servlet 自动配置；网关等 WebFlux 服务不加载拦截器。 */
@AutoConfiguration
@AutoConfigureAfter(JdbcTemplateAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
public class CommonWebAutoConfiguration implements WebMvcConfigurer {

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI maipiaoOpenApi(Environment environment) {
        String serviceName = environment.getProperty("spring.application.name", "maipiao-service");
        return new OpenAPI()
                .info(
                        new Info()
                                .title(serviceName + " API")
                                .version("1.0.0")
                                .description("麦票 " + serviceName + " 服务接口"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "使用登录接口返回的 JWT；输入 token 本身，不要添加 Bearer 前缀。")));
    }

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

    /** 使用默认强度的 BCrypt，密码不以明文存储。 */
    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 有数据源时启用管理员写操作审计。 */
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public AdminAuditLogAspect adminAuditLogAspect(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new AdminAuditLogAspect(jdbcTemplate, objectMapper);
    }

    /** Long 统一序列化为字符串，避免 Snowflake id 被 JavaScript 丢精度。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }

    /* Feign 拦截器由 CommonWebFeignConfiguration 单独注册，避免无 Feign 依赖的服务加载失败。 */

    /** 对每个请求，把 X-User-Id 读进 {@link com.maipiao.common.web.context.UserContext}。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor()).addPathPatterns("/**").order(0);
    }
}
