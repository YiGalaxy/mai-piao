package com.maipiao.gateway.config;

import com.maipiao.common.core.config.JwtProperties;
import com.maipiao.common.core.util.JwtUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配网关需要的那些组件。
 *
 * <p>网关没法复用 {@code CommonWebAutoConfiguration}：那个类带
 * {@code @ConditionalOnWebApplication(SERVLET)} 注解，而这里是一个 WebFlux 应用，
 * 所以它根本不会生效。JWT 那几块改在这里手工构建。
 *
 * <p>注意这里没有 PasswordEncoder 这个 bean —— 网关从不碰任何凭据。它只校验 token
 * 和路由；做哈希的地方是 user-service。
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, GatewayAuthProperties.class})
public class GatewayConfig {

    @Bean
    public JwtUtil jwtUtil(JwtProperties jwtProperties) {
        return new JwtUtil(jwtProperties);
    }
}
