package com.maipiao.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * user-service：注册、登录、JWT 签发、优惠券。
 *
 * <p>注意这里没有把基础包范围放大的 {@code @ComponentScan}：
 * 来自 common-web / common-redis 的共享 bean 是通过 Spring Boot 的自动配置导入进来的，
 * 不是靠扫描进来的。
 */
@SpringBootApplication
@EnableFeignClients
@EnableDiscoveryClient
@MapperScan("com.maipiao.user.mapper")
public class MaipiaoUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoUserApplication.class, args);
    }
}
