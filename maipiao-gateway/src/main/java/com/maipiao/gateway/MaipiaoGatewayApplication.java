package com.maipiao.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * API gateway：所有客户端的唯一入口。
 *
 * <p>职责，按重要性排序：
 * <ul>
 *   <li>路由之前，把客户端无权自称的东西（{@code X-User-Id}、
 *       {@code X-Internal-Call}）丢掉。</li>
 *   <li>校验 JWT 并把解析出的用户 id 转发下去，让各个服务不必自己解析 token。</li>
 *   <li>把抢购场次服务不了的请求就地短路，让它们根本到不了服务
 *       （{@link com.maipiao.gateway.filter.RushGateFilter}）。</li>
 *   <li>按路径前缀路由。</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} 是为了让抢购状态的快照保持热乎。过滤器要靠它才能
 * 免掉每个请求一次 Redis 查询，而一个从不刷新的快照会永远给出错误的答案。
 *
 * <p>它刻意不是一个业务服务：没有数据库，没有领域逻辑。任何需要了解座位或订单的
 * 东西都归下游。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class MaipiaoGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaipiaoGatewayApplication.class, args);
    }
}
