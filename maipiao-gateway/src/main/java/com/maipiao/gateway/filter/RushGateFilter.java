package com.maipiao.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把那些本来就不可能成功的请求挡回去。
 *
 * <p>两千张票、十万人抢，意味着 99.8% 的流量注定要失败。没有这一层，它们会失败得
 * 又慢又贵 —— 每一个都是一次 Redis 调用，而 Redis 调用背后是 seat 服务在为一个
 * 它早就知道的答案干活。有了这一层，它们在这里就失败了，一次 map 查找的事。
 *
 * <p>优化就这么多：不可能成功的请求不再成为请求。剩下的流量小到后面的服务能实打实
 * 地处理。
 *
 * <p>在 {@link JwtAuthFilter} 之后执行，有两个原因。校验准入 token 需要已经解析出
 * 的 {@code X-User-Id}，而这个头要等认证跑完才存在。另外，对马上要因为完全没有
 * token 而被拒的请求，它也不该白干活。
 *
 * <p><b>这是一个泄压阀，不是一道边界。</b>schedule id 来自 query string，由调用方
 * 控制，所以谎报它的客户端能从这个过滤器底下钻过去。拦住它们的是 seat 服务 ——
 * 它拿自己解析出的场次推出 key，再拿准入 token 去对。分层是刻意的：网关吸收流量，
 * 服务负责强制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RushGateFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 售罄和暂停标志，定时刷新。
     *
     * <p>这个过滤器的意义就在于省掉每个请求一次 Redis 往返，所以为了判断要不要短路
     * 而去做一次往返，等于自废武功。每秒百万请求的量级上，一次 map 查找和一次网络
     * 跳转之间的差别，就是一台 Redis 和一个集群之间的差别。
     *
     * <p>代价是某个场次的状态最多会旧一秒。往一个方向是无害的 —— 少量请求还是打到
     * 了服务上被拒，反正本来也是这个结果。往另一个方向，刚刚重新开售的场次会多关
     * 一秒，没人会注意到。
     */
    private volatile Map<String, Integer> rushState = Map.of();

    /** 这些路径会占用一个排队名额，因此必须先有排队名额。 */
    private static final List<String> GATED_PATHS = List.of(
            "/api/seat/lock",
            "/api/seat/assign");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (!GATED_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        String scheduleId = scheduleIdOf(request);
        if (scheduleId == null) {
            // 没有场次信息可供判断。seat 服务会按它自己的规则拒绝这个请求；
            // 在这里瞎猜只会更糟。
            return chain.filter(exchange);
        }

        Map<String, Integer> state = rushState;
        Integer flags = state.get(scheduleId);
        if (flags == null) {
            // 不是抢购场次，也就没有队可排。普通购票走的都是这条路。
            return chain.filter(exchange);
        }

        if ((flags & SOLD_OUT) != 0) {
            return reject(exchange, ErrorCode.SCHEDULE_SOLD_OUT, "该场次已售罄");
        }
        if ((flags & PAUSED) != 0) {
            return reject(exchange, ErrorCode.SCHEDULE_NOT_ON_SALE, "抢购已暂停");
        }

        String userId = request.getHeaders().getFirst(CommonConstants.HEADER_USER_ID);
        if (userId == null) {
            return reject(exchange, ErrorCode.UNAUTHORIZED, "请先登录");
        }

        return redis.hasKey(CommonConstants.QUEUE_TOKEN_KEY + scheduleId + ":" + userId)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(admitted -> admitted
                        ? chain.filter(exchange)
                        : reject(exchange, ErrorCode.SCHEDULE_NOT_ON_SALE, "请先排队等候叫号"));
    }

    private String scheduleIdOf(ServerHttpRequest request) {
        return request.getQueryParams().getFirst("scheduleId");
    }

    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        // 返回 200 加业务码，与系统里其他所有"预期内的拒绝"的报法保持一致。这里给
        // 4xx 的话，在客户端的错误处理看来，它和网关自身故障没有区别。
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(R.fail(code, message));
        } catch (JsonProcessingException e) {
            bytes = "{\"code\":500,\"message\":\"system error\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    // ------------------------------------------------------------

    private static final int SOLD_OUT = 1;
    private static final int PAUSED = 2;

    /**
     * 刷新本地持有的所有抢购场次状态。
     *
     * <p>遍历 queue 服务维护的注册表，而不是去问 movie-service 哪些场次在抢购 ——
     * 整个集合一次 Redis 读，而且这条每秒都要跑（不管有没有事发生）的路径上不会
     * 引入跨服务耦合。
     */
    @Scheduled(fixedDelay = 1000)
    public void refreshRushState() {
        // 这个集合通常极小 —— 每个并发的抢购场次一条，而很少有超过一个的时候 ——
        // 所以这里是每秒发少量并发查询，而不是每个访客发一次。
        Flux.from(redis.opsForSet().members(CommonConstants.RUSH_SCHEDULES_KEY))
                .flatMap(this::flagsOf)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .doOnNext(next -> rushState = next)
                .onErrorResume(e -> {
                    // Redis 抖动：保留上一次已知的状态，而不是断定所有抢购场次
                    // 突然都开放了。状态偏"关"只是挡走几个买家；状态偏"开"会淹没
                    // 这个过滤器本来要保护的那些服务，而后者的严重程度高出一大截。
                    log.warn("could not refresh rush state, keeping the previous view", e);
                    return Mono.empty();
                })
                .subscribe();
    }

    private Mono<Map.Entry<String, Integer>> flagsOf(String scheduleId) {
        return Mono.zip(
                        redis.hasKey(CommonConstants.SOLD_OUT_KEY + scheduleId)
                                .defaultIfEmpty(Boolean.FALSE),
                        redis.hasKey(CommonConstants.RUSH_PAUSED_KEY + scheduleId)
                                .defaultIfEmpty(Boolean.FALSE))
                .map(flags -> Map.entry(scheduleId,
                        (Boolean.TRUE.equals(flags.getT1()) ? SOLD_OUT : 0)
                                | (Boolean.TRUE.equals(flags.getT2()) ? PAUSED : 0)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    /** 暴露出来，供测试和刷新逻辑自己记账用。 */
    Set<String> knownSchedules() {
        return rushState.keySet();
    }
}
