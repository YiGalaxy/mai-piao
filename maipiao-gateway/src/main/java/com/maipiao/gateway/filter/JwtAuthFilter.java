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
 * 在入口处校验一次 JWT，并把解析出的用户 id 转发下去。
 *
 * <p>每个请求先净化，再分级：内部接口直接拒绝，公开接口放行，其余的一律要 token
 * —— 其中管理端接口还要求 token 携带管理员角色。顺序是有讲究的，每一步摆在哪个
 * 位置，正是它能生效的原因：
 *
 * <ol>
 *   <li><b>净化。</b>把客户端自己带上的 {@code X-User-Id} 和 {@code X-Internal-Call}
 *       剥掉。下游服务信任这些头，所以能设置它们的客户端就能冒充任何用户，或者冒充
 *       另一个服务。白名单路径同样要跑这一步 —— 匿名请求不能偷偷夹带一个身份进来。</li>
 *   <li><b>认证。</b>非白名单路径需要一个签名和有效期都过关、且 {@code jti} 不在登出
 *       黑名单上的 token。</li>
 *   <li><b>授权。</b>管理端接口额外还要求管理员角色。这个角色从一开始就往下游注入，
 *       却没有任何人去读它，所以任何已登录用户都能访问到管理端。</li>
 * </ol>
 *
 * <p>排序在 {@link Ordered#HIGHEST_PRECEDENCE}，为的是净化先跑，等其他 filter
 * 看到那些头的时候，它们已经被净化过了。
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

        // ---- 1. 剥掉客户端自带的身份头，任何情况下都做 ----
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove(CommonConstants.HEADER_USER_ID);
                    headers.remove(CommonConstants.HEADER_INTERNAL_CALL);
                    headers.remove(HEADER_USER_ROLE);
                })
                .build();

        // ---- 2. 内部接口永远不能从外部路由到 ----
        // 先于白名单检查，而且是刻意的：/api/movie/** 为了浏览而公开，它同时也
        // 会匹配上 /api/movie/inner/schedule/occupy —— 也就是给订单事务预留库存
        // 的那个分支。任何人不用带 token 就能调它。
        //
        // 白名单表达不了"公开，但这些除外"，所以这条规则单独拎出来，并且先跑。
        // 服务之间的调用走 Feign 直连目标服务，根本不经过网关，所以这里不会挡下
        // 任何合法调用。
        if (isInternal(path)) {
            log.warn("blocked external call to internal endpoint: {} {}", request.getMethod(), path);
            // 返回 404 而不是 403：403 等于承认这个接口存在。
            return notFound(exchange);
        }

        // ---- 3. CORS 预检请求按设计就不带 token ----
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(withRequest(exchange, sanitized));
        }

        // ---- 4. 公开接口 ----
        // 不管白名单怎么写，管理端路径都算不上公开，所以让它们跳过这一步，而不是
        // 指望白名单里永远不会有哪个模式覆盖到它们。
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
            // parse() 会把原因记进 debug 日志：过期、签名不对、issuer 不对。
            // 这些一点都不告诉客户端，因为这个区分对攻击者的用处大于对合法调用方。
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
        }

        String userId = claims.getSubject();
        if (userId == null) {
            return unauthorized(exchange, ErrorCode.UNAUTHORIZED);
        }

        // ---- 6. 管理端接口需要管理员角色 ----
        // 角色从一开始就写进了 X-User-Role，却没有任何人去读，所以每个管理端接口
        // 任何已登录用户都能访问。token 仅仅有效，不等于已获授权。
        //
        // 放在黑名单检查之前，因为它不需要查任何东西；对一个本来就不会被放行的
        // 请求做吊销检查，是白费功夫。
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
                // Redis 挂了：fail closed。把故障当成"未被吊销"，等于把 Redis 的
                // 一次抖动变成一个窗口，窗口期内已登出的 token 照样好使。
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

    /**
     * 判断是否为服务间调用接口。
     *
     * <p>每个服务都把内部接口挂在 {@code /inner/...} 下，经网关访问时是
     * {@code /api/{service}/inner/...}。这个模式匹配任意深度，所以某个服务把
     * controller 嵌套得跟别人不一样，也照样能覆盖到。
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
     * 判断是否为管理端接口。
     *
     * <p>和 {@code /inner} 一样先于公开白名单匹配，这样一条覆盖整个服务的白名单
     * 条目不会顺手把它打开。这不是假设：{@code /api/movie/**} 为浏览而公开，
     * 它自己就会覆盖到 {@code /api/movie/admin/**}。
     */
    private boolean isAdminPath(String path) {
        return PATH_MATCHER.match("/api/*/admin/**", path)
                || PATH_MATCHER.match("/api/*/admin", path);
    }

    /**
     * 返回 403，不是 404。
     *
     * <p>不像 {@code /inner} —— 对互联网来说它压根不存在；管理端接口是要让它的
     * 使用者找到的。告诉一个已登录的非管理员"你不是管理员"，比假装这个页面不存在
     * 更有用。
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
