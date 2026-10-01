package com.maipiao.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maipiao.common.core.constant.CommonConstants;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.result.R;
import com.maipiao.common.core.util.JwtUtil;
import com.maipiao.gateway.config.GatewayAuthProperties;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 网关 JWT 过滤器：清理身份头、校验令牌并注入用户身份。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String HEADER_USER_ROLE = "X-User-Role";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayAuthProperties authProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // ---- 1. 剥掉客户端自带的身份头，任何情况下都做 ----
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove(CommonConstants.HEADER_USER_ID);
                    headers.remove(CommonConstants.HEADER_INTERNAL_CALL);
                    headers.remove(HEADER_USER_ROLE);
                })
                .build();

        // 内部接口必须先于公开白名单拦截，服务间调用不经过网关。
        if (isInternal(path)) {
            log.warn("blocked external call to internal endpoint: {} {}", request.getMethod(), path);
            // 返回 404 而不是 403：403 等于承认这个接口存在。
            return notFound(exchange);
        }

        // ---- 3. CORS 预检请求按设计就不带 token ----
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(withRequest(exchange, sanitized));
        }

        // 管理端路径不参与公开白名单匹配。
        if (!isAdminPath(path) && isWhitelisted(path)) {
            return chain.filter(withRequest(exchange, sanitized));
        }

        // ---- 5. 必须有 token ----
        String token = extractToken(sanitized);
        if (token == null) {
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
        }

        Claims claims = jwtUtil.parse(token);
        if (claims == null) {
            // 不向客户端暴露令牌失败原因。
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
        }

        String userId = claims.getSubject();
        if (userId == null) {
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
        }

        // 管理端接口额外校验管理员角色。
        if (isAdminPath(path) && !jwtUtil.isAdmin(claims)) {
            log.warn("non-admin token rejected for admin path: path={}, userId={}", path, userId);
            return forbidden(exchange);
        }

        // ---- 7. 登出黑名单 ----
        if (!authProperties.isCheckBlacklist()) {
            return chain.filter(withIdentity(exchange, sanitized, userId, claims));
        }

        String blacklistKey = CommonConstants.TOKEN_BLACKLIST_KEY + claims.getId();
        return redisTemplate.hasKey(blacklistKey)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(revoked -> {
                    if (Boolean.TRUE.equals(revoked)) {
                        log.debug("rejected revoked token: jti={}", claims.getId());
                        return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
                    }
                    return chain.filter(withIdentity(exchange, sanitized, userId, claims));
                })
                // 黑名单查询失败时拒绝请求。
                .onErrorResume(e -> {
                    log.error("blacklist lookup failed, rejecting request", e);
                    return unauthorized(exchange, ErrorCode.SERVICE_UNAVAILABLE);
                });
    }

    /** 把净化后的请求原样转发下去，不附带任何身份。 */
    private ServerWebExchange withRequest(ServerWebExchange exchange, ServerHttpRequest sanitized) {
        return exchange.mutate().request(sanitized).build();
    }

    /**
     * 把净化后的请求转发下去，并附上已校验的身份。
     * 这里是整个系统里唯一会设置 {@code X-User-Id} 的地方。
     */
    private ServerWebExchange withIdentity(ServerWebExchange exchange,
                                           ServerHttpRequest sanitized,
                                           String userId,
                                           Claims claims) {
        String role = jwtUtil.getRole(claims);

        ServerHttpRequest mutated = sanitized.mutate()
                .header(CommonConstants.HEADER_USER_ID, userId)
                .headers(headers -> {
                    if (role != null) {
                        headers.set(HEADER_USER_ROLE, role);
                    }
                })
                .build();

        return exchange.mutate().request(mutated).build();
    }

    // ------------------------------------------------------------

    private boolean isWhitelisted(String path) {
        for (String pattern : authProperties.getWhitelist()) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 判断是否为服务间调用接口。 */
    private boolean isInternal(String path) {
        return PATH_MATCHER.match("/api/*/inner/**", path)
                || PATH_MATCHER.match("/api/*/inner", path);
    }

    private Mono<Void> notFound(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    /** 判断是否为管理端接口或演示数据接口。 */
    private boolean isAdminPath(String path) {
        // 演示数据接口会修改场次数据，不能随公开电影接口放行。
        return PATH_MATCHER.match("/api/*/admin/**", path)
                || PATH_MATCHER.match("/api/*/admin", path)
                || PATH_MATCHER.match("/api/movie/demo/**", path)
                || PATH_MATCHER.match("/api/movie/demo", path);
    }

    /** 返回管理端权限错误。 */
    private Mono<Void> forbidden(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return writeBody(response, R.fail(ErrorCode.FORBIDDEN));
    }

    private String extractToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return null;
        }
        return jwtUtil.stripBearer(header);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, ErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return writeBody(response, R.fail(errorCode));
    }

    private Mono<Void> writeBody(ServerHttpResponse response, R<?> body) {
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // 连自己的响应封装都序列化不了 —— 吐一个字面量出去，总比让客户端
            // 收到一个空响应强。
            bytes = "{\"code\":500,\"message\":\"system error\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
