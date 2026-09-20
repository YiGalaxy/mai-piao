package com.maipiao.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order-service : order creation, the state machine, and timeout cancellation.
 *
 * <p>This is the transaction manager for G1 (order creation). It opens the
 * global transaction and drives the branches in movie-service and
 * user-service; the seat hold in Redis is compensated explicitly because Redis
 * is not a transactional resource.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
@MapperScan("com.maipiao.order.mapper")
public class MaipiaoOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoOrderApplication.class, args);
    }
}
