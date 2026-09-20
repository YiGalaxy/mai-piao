package com.maoyan.common.redis.script;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads Lua scripts from the classpath and caches the resulting
 * {@link RedisScript} objects.
 *
 * <p>Why Lua at all: Redis executes a script as a single unit - no other
 * command runs in the middle. For seat selection that is the whole ballgame.
 * A "check every seat is free, then occupy them all" sequence implemented with
 * separate GETBIT/SETBIT calls has a window between the two phases, and two
 * users selecting the same seat in that window both succeed. One script, one
 * atomic unit, no window.
 *
 * <p>Cache note: {@link DefaultRedisScript#setLocation} reads the resource once
 * and keeps the text, so returning the same instance is safe and avoids
 * re-reading the file on every call.
 */
public final class LuaScriptLoader {

    private static final Map<String, RedisScript<?>> CACHE = new ConcurrentHashMap<>();

    private LuaScriptLoader() {
    }

    /**
     * For scripts returning a single integer (a count, a released-seat total,
     * or {@code 1}/{@code 0} used as a boolean).
     */
    public static RedisScript<Long> ofLong(String classpathLocation) {
        return cached(classpathLocation, Long.class);
    }

    /**
     * For scripts returning a Lua table, which Redis hands back as a
     * {@code List<Long>}. The seat-lock script uses this to return
     * {@code {0, conflictingSeatIndex}} or {@code {1, lockedCount}}.
     */
    public static RedisScript<List> ofList(String classpathLocation) {
        return cached(classpathLocation, List.class);
    }

    /** For scripts returning a string status. */
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
