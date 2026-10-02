package com.maipiao.movie;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * movie-service：影片、影院、场地、排期，以及座位账本。
 *
 * <p>这个服务持有 {@code maipiao_movie}，里面既有内容库（在上映什么），也有
 * {@code t_event_session_seat}（哪一场的哪个座位被卖掉了）。座位账本放在这里而不是
 * seat-service，因为 seat-service 是无状态的 —— Redis 存的是实时可售状态，而这张表
 * 是它用来对账的持久记录。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.maipiao.movie.mapper")
public class MaipiaoMovieApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoMovieApplication.class, args);
    }
}
