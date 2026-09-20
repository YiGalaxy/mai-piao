package com.maoyan.user;

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
@MapperScan("com.maoyan.user.mapper")
public class MaoyanUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaoyanUserApplication.class, args);
    }
}
