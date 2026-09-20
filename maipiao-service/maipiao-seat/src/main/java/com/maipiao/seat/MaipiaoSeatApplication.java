package com.maipiao.seat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * seat-service：座位图，以及其背后的原子加锁。
 *
 * <p>本服务不拥有任何数据库 schema。实时座位可用性以"每个场次一张 bitmap"的形式存在
 * Redis 里；{@code maipiao_movie.t_event_session_seat} 是持久账本，用来重建 bitmap，
 * 也是对账任务比对的依据。
 *
 * <p>所有不能存在竞争的地方，都表达成一个单独的 Lua 脚本，这样"检查"和"占用"就无法
 * 被另一个请求拆开。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.maipiao.seat.mapper")
public class MaipiaoSeatApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoSeatApplication.class, args);
    }
}
