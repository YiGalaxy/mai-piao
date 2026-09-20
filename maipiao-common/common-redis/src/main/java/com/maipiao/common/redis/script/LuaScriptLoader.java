package com.maipiao.common.redis.script;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 classpath 加载 Lua 脚本，并缓存生成的 {@link RedisScript} 对象。
 *
 * <p>为什么要用 Lua：Redis 把一个脚本当作一个整体来执行 —— 中间不会插进其他命令。
 * 对选座来说，这就是胜负手。用分开的 GETBIT/SETBIT 调用来实现"检查所有座位都空着，
 * 再把它们全占掉"，两个阶段之间有一道窗口，两个用户在这道窗口里选了同一个座位会
 * 双双成功。一个脚本，一个原子单元，没有窗口。
 *
 * <p>缓存说明：{@link DefaultRedisScript#setLocation} 只读一次资源并把文本留在手里，
 * 所以返回同一个实例是安全的，也免了每次调用都重新读文件。
 */
public final class LuaScriptLoader {

    private static final Map<String, RedisScript<?>> CACHE = new ConcurrentHashMap<>();

    private LuaScriptLoader() {
    }

    /**
     * 用于返回单个整数的脚本（一个计数、释放的座位总数，或者当布尔值用的
     * {@code 1}/{@code 0}）。
     */
    public static RedisScript<Long> ofLong(String classpathLocation) {
        return cached(classpathLocation, Long.class);
    }

    /**
     * 用于返回 Lua table 的脚本，Redis 会把它交回成一个 {@code List<Long>}。
     * 座位锁定脚本用它返回 {@code {0, conflictingSeatIndex}} 或
     * {@code {1, lockedCount}}。
     */
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
