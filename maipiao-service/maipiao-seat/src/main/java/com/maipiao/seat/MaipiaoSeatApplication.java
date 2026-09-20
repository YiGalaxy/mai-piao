package com.maipiao.seat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * seat-service : the seat map, and the atomic locking behind it.
 *
 * <p>This service owns no database schema. Live seat availability lives in
 * Redis as one bitmap per screening; {@code maipiao_movie.t_movie_schedule_seat}
 * is the durable ledger, read to rebuild a bitmap and compared against by the
 * reconciliation job.
 *
 * <p>Everything that must not race is expressed as a single Lua script, so
 * that "check" and "claim" cannot be separated by another request.
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
