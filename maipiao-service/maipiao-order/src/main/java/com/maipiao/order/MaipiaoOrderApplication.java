package com.maipiao.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order-service：订单创建、状态机，以及超时取消。
 *
 * <p>它是 G1（订单创建）的事务管理者。它开启全局事务，并驱动 movie-service 和
 * user-service 里的各个分支；Redis 里的座位占用靠显式补偿来撤销，
 * 因为 Redis 不是事务性资源。
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
