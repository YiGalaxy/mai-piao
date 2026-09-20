package com.maipiao.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * user-service : registration, login, JWT issuing, coupons.
 *
 * <p>Note there is no {@code @ComponentScan} widening the base package: the
 * shared beans from common-web / common-redis arrive through Spring Boot's
 * auto-configuration imports, not through scanning.
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.maipiao.user.mapper")
public class MaipiaoUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoUserApplication.class, args);
    }
}
