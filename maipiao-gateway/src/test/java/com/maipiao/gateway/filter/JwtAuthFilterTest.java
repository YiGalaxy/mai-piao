package com.maipiao.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.config.JwtProperties;
import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.util.JwtUtil;
import com.maipiao.gateway.config.GatewayAuthProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class JwtAuthFilterTest {

    private JwtUtil jwtUtil;
    private ReactiveStringRedisTemplate redis;
    private GatewayAuthProperties authProperties;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtUtil = new JwtUtil(jwtProperties);
        redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        authProperties = new GatewayAuthProperties();
        authProperties.setWhitelist(List.of("/api/movie/**"));
        authProperties.setCheckBlacklist(true);
        filter = new JwtAuthFilter(jwtUtil, new ObjectMapper(), redis, authProperties);
    }

    @Test
    void rejectsMissingTokenOnProtectedPath() {
        MockServerWebExchange exchange = exchange("/api/order/list");

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsUserTokenOnAdminPathEvenWhenWhitelistMatches() {
        String token = jwtUtil.generateUserToken(7L, "13800000001");
        ServerWebExchange exchange =
                exchange("/api/movie/admin/users")
                        .mutate()
                        .request(request -> request.header("Authorization", "Bearer " + token))
                        .build();

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void blocksInternalRoutesBeforePublicWhitelist() {
        MockServerWebExchange exchange = exchange("/api/movie/inner/schedule/occupy");

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
    }

    @Test
    void replacesClientIdentityHeadersWithVerifiedIdentity() {
        String token = jwtUtil.generateUserToken(7L, "13800000001");
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/order/list")
                                .header("Authorization", "Bearer " + token)
                                .header(CommonConstants.HEADER_USER_ID, "999")
                                .header("X-User-Role", "ADMIN")
                                .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(
                        exchange,
                        next -> {
                            forwarded.set(next);
                            return Mono.empty();
                        })
                .block();

        assertNotNull(forwarded.get());
        assertEquals(
                "7",
                forwarded.get().getRequest().getHeaders().getFirst(CommonConstants.HEADER_USER_ID));
        assertEquals("USER", forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertNull(
                forwarded
                        .get()
                        .getRequest()
                        .getHeaders()
                        .getFirst(CommonConstants.HEADER_INTERNAL_CALL));
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
