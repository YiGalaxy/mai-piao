package com.maipiao.pay;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * pay-service : payment orders, callbacks, idempotency and refunds.
 *
 * <p>Transaction manager for G2 (issue tickets) and G3 (refund and release),
 * for the same reason order-service manages G1: the writes span services and
 * have to succeed or fail together.
 *
 * <p>It is also the only service that talks to a payment provider, and the
 * only one that has to be correct about the same callback arriving twice.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
@MapperScan("com.maipiao.pay.mapper")
public class MaipiaoPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoPayApplication.class, args);
    }
}
