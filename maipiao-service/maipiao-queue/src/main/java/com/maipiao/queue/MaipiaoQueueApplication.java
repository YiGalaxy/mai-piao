package com.maipiao.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The rush-sale queue.
 *
 * <p>Sits in front of seat-service and hands out admission tokens. It exists
 * because the alternative is worse: when two thousand tickets meet a hundred
 * thousand buyers, 99.8% of the requests are going to fail, and the only
 * question is what they fail with. Without a queue they fail by piling onto
 * the seat service and Redis until everything slows down for the 0.2% who
 * would have got a ticket.
 *
 * <p>{@code @EnableScheduling} is for the dispatcher, which is not a cleanup
 * job but the mechanism itself - the line only moves because something moves
 * it. It runs on every instance and relies on {@code ZPOPMIN} being atomic
 * rather than on a leader election, so two instances admitting at once cannot
 * hand the same place to two people.
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class MaipiaoQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoQueueApplication.class, args);
    }
}
