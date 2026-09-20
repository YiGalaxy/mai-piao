package com.maipiao.gateway.config;

import com.maipiao.common.core.config.JwtProperties;
import com.maipiao.common.core.util.JwtUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the pieces the gateway needs.
 *
 * <p>The gateway cannot reuse {@code CommonWebAutoConfiguration}: that class is
 * annotated {@code @ConditionalOnWebApplication(SERVLET)} and this is a WebFlux
 * application, so it never activates. The JWT pieces are built by hand here
 * instead.
 *
 * <p>Note there is no PasswordEncoder bean - the gateway never touches
 * credentials. It verifies tokens and routes; hashing happens in user-service.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, GatewayAuthProperties.class})
public class GatewayConfig {

    @Bean
    public JwtUtil jwtUtil(JwtProperties jwtProperties) {
        return new JwtUtil(jwtProperties);
    }
}
