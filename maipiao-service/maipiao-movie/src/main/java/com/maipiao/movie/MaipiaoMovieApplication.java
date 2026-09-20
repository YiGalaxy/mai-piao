package com.maipiao.movie;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * movie-service : films, cinemas, halls, schedules, and the seat ledger.
 *
 * <p>This service owns {@code maipiao_movie}, which holds both the catalogue
 * (what is showing) and {@code t_movie_schedule_seat} (which seat of which
 * screening was sold). The seat ledger lives here rather than in seat-service
 * because seat-service is stateless - Redis holds live availability, and this
 * table is the durable record it reconciles against.
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
