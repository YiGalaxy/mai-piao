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
 * Turns away the requests that were never going to succeed.
 *
 * <p>Two thousand tickets and a hundred thousand buyers means 99.8% of the
 * traffic is going to fail. Without this they fail slowly and expensively -
 * each one a Redis call, and behind the Redis calls a seat service doing work
 * for an answer it already knew. With it they fail here, in a map lookup.
 *
 * <p>That is the whole optimisation: the requests that cannot succeed stop
 * being requests. The remaining traffic is small enough for the services
 * behind to handle honestly.
 *
 * <p>Runs after {@link JwtAuthFilter}, for two reasons. It needs the resolved
 * {@code X-User-Id} to check an admission token, and that header only exists
 * once authentication has run. And it should not be doing work for requests
 * that are about to be rejected for having no token at all.
 *
 * <p><b>This is a pressure valve, not a boundary.</b> The schedule id comes
 * from the query string, which the caller controls, so a client that lies
 * about it gets past this filter. What stops them is the seat service, which
 * checks the admission token against the key derived from the schedule it
 * resolved itself. The layering is deliberate: the gateway absorbs volume, the
 * service enforces.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RushGateFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * Sold-out and paused flags, refreshed on a timer.
     *
     * <p>The point of this filter is to avoid a Redis round trip per request,
     * so doing one to find out whether to short-circuit would defeat it. At a
     * million requests a second the difference between a map lookup and a
     * network hop is the difference between one Redis instance and a cluster.
     *
     * <p>The cost is that a screening can be up to a second stale. In one
     * direction that is harmless - a few requests reach a service that refuses
     * them, which is what would have happened anyway. In the other, a sale
     * that has just reopened stays closed for a second, which nobody notices.
     */
    private volatile Map<String, Integer> rushState = Map.of();

    /** Paths that cost a place in line, and so require one. */
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
            // No schedule to reason about. The seat service will reject the
            // request on its own terms; guessing here would be worse.
            return chain.filter(exchange);
        }

        Map<String, Integer> state = rushState;
        Integer flags = state.get(scheduleId);
        if (flags == null) {
            // Not a rush sale, so there is no line to have waited in. This is
            // the path every ordinary ticket takes.
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
        // 200 with a business code, matching how every other expected refusal
        // in this system is reported. A 4xx here would be indistinguishable
        // from a gateway fault to the client's error handling.
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
     * Refreshes the local view of every rush sale's state.
     *
     * <p>Walks the registry the queue service maintains rather than asking
     * movie-service which screenings are on rush - one Redis read for the
     * whole set, and no cross-service coupling on a path that runs every
     * second whether or not anything is happening.
     */
    @Scheduled(fixedDelay = 1000)
    public void refreshRushState() {
        // The set is normally tiny - one entry per concurrent rush sale, and
        // there is rarely more than one - so this issues a small number of
        // concurrent lookups once a second rather than one per visitor.
        Flux.from(redis.opsForSet().members(CommonConstants.RUSH_SCHEDULES_KEY))
                .flatMap(this::flagsOf)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .doOnNext(next -> rushState = next)
                .onErrorResume(e -> {
                    // Redis blip: keep the last known state rather than
                    // deciding every rush sale is suddenly open. Stale-closed
                    // turns a few buyers away; stale-open floods the services
                    // this filter exists to protect, which is the worse failure
                    // by a wide margin.
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

    /** Exposed for tests and for the refresh path's own bookkeeping. */
    Set<String> knownSchedules() {
        return rushState.keySet();
    }
}
