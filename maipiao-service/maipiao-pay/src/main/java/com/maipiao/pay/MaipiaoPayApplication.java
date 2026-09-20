package com.maipiao.pay;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * pay-service：支付单、回调、幂等与退款。
 *
 * <p>G2（出票）和 G3（退款并释放座位）的事务发起方，理由和 order-service 发起 G1 一样：
 * 这些写入跨越了多个服务，必须一起成功或一起失败。
 *
 * <p>它也是唯一一个与支付渠道方打交道的服务，以及唯一一个必须正确处理
 * 「同一条回调来了两次」的服务。
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
