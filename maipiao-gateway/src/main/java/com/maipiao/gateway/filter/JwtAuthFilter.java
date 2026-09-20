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

/**
 * Verifies the JWT once, at the edge, and forwards the resolved user id.
 *
 * <p>Every request is sanitized, then classified: the internal surface is
 * refused outright, the public surface is let through, and everything else
 * needs a token - with the admin surface needing one that carries the admin
 * role. The order matters, and each step's placement is the reason it works:
 *
 * <ol>
 *
 * <ol>
 *   <li><b>Sanitize.</b> {@code X-User-Id} and {@code X-Internal-Call} are
 *       stripped from whatever the client sent. Downstream services trust those
 *       headers, so a client able to set them could act as any user, or as
 *       another service. This runs for whitelisted paths too - an anonymous
 *       request must not be able to smuggle in an identity.</li>
 *   <li><b>Authenticate.</b> Non-whitelisted paths need a token whose signature
 *       and expiry check out, and whose {@code jti} is not on the logout
 *       blacklist.</li>
 *   <li><b>Authorise.</b> The admin surface additionally needs the admin role.
 *       The role has been injected downstream since the beginning and read by
 *       nobody, so any logged-in user could reach it.</li>
 * </ol>
 *
 * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE} so sanitizing runs before
 * any other filter can look at those headers.
 */
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

        // ---- 1. strip client-supplied identity headers, always ----
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove(CommonConstants.HEADER_USER_ID);
                    headers.remove(CommonConstants.HEADER_INTERNAL_CALL);
                    headers.remove(HEADER_USER_ROLE);
                })
                .build();

        // ---- 2. internal endpoints are never routable from outside ----
        // Checked before the whitelist, and deliberately so: /api/movie/** is
        // public for browsing, which also matched /api/movie/inner/schedule/
        // occupy - the branch that reserves inventory for the order
        // transaction. Anybody could have called it with no token at all.
        //
        // A whitelist cannot express "public except for these", so the rule is
        // separate and runs first. Service-to-service calls use Feign straight
        // to the target service and never traverse the gateway, so nothing
        // legitimate is blocked here.
        if (isInternal(path)) {
            log.warn("blocked external call to internal endpoint: {} {}", request.getMethod(), path);
            // 404 rather than 403: a 403 confirms the endpoint exists.
            return notFound(exchange);
        }

        // ---- 3. CORS preflight carries no token by design ----
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(withRequest(exchange, sanitized));
        }

        // ---- 4. public endpoints ----
        // Admin paths are never public, whatever the whitelist says, so they
        // skip this rather than relying on no pattern ever covering them.
        if (!isAdminPath(path) && isWhitelisted(path)) {
            return chain.filter(withRequest(exchange, sanitized));
        }

        // ---- 5. token required ----
        String token = extractToken(sanitized);
        if (token == null) {
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
        }

        Claims claims = jwtUtil.parse(token);
        if (claims == null) {
            // parse() logs the reason at debug: expired, bad signature, wrong
            // issuer. The client is told none of it, because the distinction is
            // more useful to an attacker than to a legitimate caller.
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
        }

        String userId = claims.getSubject();
        if (userId == null) {
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
        }

        // ---- 6. admin surface needs the admin role ----
        // The role has been put into X-User-Role since the beginning and read
        // by nobody, so every admin endpoint was reachable by any logged-in
        // user. A token that is merely valid is not authorisation.
        //
        // Checked before the blacklist because it needs no lookup, and a
        // revocation check on a request that was never going to be allowed is
        // work for nothing.
        if (isAdminPath(path) && !jwtUtil.isAdmin(claims)) {
            log.warn("non-admin token rejected for admin path: path={}, userId={}", path, userId);
            return forbidden(exchange);
        }

        // ---- 7. logout blacklist ----
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
                // Redis down: fail closed. Treating an outage as "not revoked"
                // turns a Redis blip into a window where logged-out tokens work.
                .onErrorResume(e -> {
                    log.error("blacklist lookup failed, rejecting request", e);
                    return unauthorized(exchange, ErrorCode.SERVICE_UNAVAILABLE);
                });
    }

    /** Carries the sanitized request forward, with no identity attached. */
    private ServerWebExchange withRequest(ServerWebExchange exchange, ServerHttpRequest sanitized) {
        return exchange.mutate().request(sanitized).build();
    }

    /**
     * Carries the sanitized request forward with the verified identity attached.
     * This is the only place {@code X-User-Id} is ever set.
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

    /**
     * True for the service-to-service surface.
     *
     * <p>Every service mounts its internal endpoints under {@code /inner/...},
     * reached through the gateway as {@code /api/{service}/inner/...}. The
     * pattern matches at any depth so a service that nests its controllers
     * differently is still covered.
     */
    private boolean isInternal(String path) {
        return PATH_MATCHER.match("/api/*/inner/**", path)
                || PATH_MATCHER.match("/api/*/inner", path);
    }

    private Mono<Void> notFound(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }

    /**
     * True for the administration surface.
     *
     * <p>Matched before the public whitelist, like {@code /inner}, so that a
     * whitelist entry covering a whole service cannot open it by accident.
     * That is not hypothetical: {@code /api/movie/**} is public for browsing,
     * and it would have covered {@code /api/movie/admin/**} on its own.
     */
    private boolean isAdminPath(String path) {
        return PATH_MATCHER.match("/api/*/admin/**", path)
                || PATH_MATCHER.match("/api/*/admin", path);
    }

    /**
     * 403, not 404.
     *
     * <p>Unlike {@code /inner}, which does not exist as far as the internet is
     * concerned, the admin surface is meant to be found by the people who use
     * it. Telling a logged-in non-administrator that they are not one is more
     * useful than pretending the page is missing.
     */
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
            // Cannot serialize our own envelope - emit a literal rather than
            // leaving the client with an empty response.
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
