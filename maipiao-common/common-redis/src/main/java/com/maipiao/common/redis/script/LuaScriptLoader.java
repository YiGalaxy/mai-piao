package com.maipiao.common.redis.script;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 从 classpath 加载并缓存 Lua 脚本。脚本在 Redis 中原子执行，避免并发读写窗口。 */
public final class LuaScriptLoader {

    private static final Map<String, RedisScript<?>> CACHE = new ConcurrentHashMap<>();

    private LuaScriptLoader() {
    }

    /** 返回整数结果的脚本。 */
    public static RedisScript<Long> ofLong(String classpathLocation) {
        return cached(classpathLocation, Long.class);
    }

    /** 返回 Lua table（{@code List<Long>}）的脚本。 */
    public static RedisScript<List> ofList(String classpathLocation) {
        return cached(classpathLocation, List.class);
    }

    /** 用于返回字符串状态值的脚本。 */
    public static RedisScript<String> ofString(String classpathLocation) {
        return cached(classpathLocation, String.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> RedisScript<T> cached(String classpathLocation, Class<T> resultType) {
        String cacheKey = classpathLocation + '#' + resultType.getSimpleName();
        return (RedisScript<T>) CACHE.computeIfAbsent(cacheKey, key -> {
            DefaultRedisScript<T> script = new DefaultRedisScript<>();
            ClassPathResource resource = new ClassPathResource(classpathLocation);
            if (!resource.exists()) {
                throw new IllegalStateException(
                        "Lua script not found on classpath: " + classpathLocation
                                + " - check that it lives under src/main/resources");
            }
            script.setLocation(resource);
            script.setResultType(resultType);
            return script;
        });
    }
}
