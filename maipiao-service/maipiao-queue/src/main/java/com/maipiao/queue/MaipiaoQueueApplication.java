package com.maipiao.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 抢购队列。
 *
 * <p>横在 seat-service 前面，负责发放准入令牌。它之所以存在，是因为另一条路更糟：
 * 两千张票对上十万买家时，99.8% 的请求注定要失败，唯一的问题只是它们以什么方式失败。
 * 没有队列，它们的失败方式是全部压在 seat-service 和 Redis 上，直到把一切都拖慢 ——
 * 包括本来能拿到票的那 0.2%。
 *
 * <p>{@code @EnableScheduling} 是给调度器用的，而调度器不是清理任务，它就是机制本身
 * —— 队列只会因为有什么东西去推它才前进。它在每个实例上都跑，依赖的是一个 Lua 脚本
 * 的原子性而不是选主，所以两个实例同时放人，不可能把同一个位置发给两个人。
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class MaipiaoQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoQueueApplication.class, args);
    }
}
