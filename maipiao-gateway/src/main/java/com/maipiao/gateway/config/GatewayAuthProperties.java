package com.maipiao.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 哪些路径可以不带 token 调用。
 *
 * <p>做成配置而不是写死在代码里，这样放开一个新的公开接口就是一次有评审记录的配置
 * 变更，而不是一次埋在 filter 里的代码改动。
 *
 * <p>没列在这里的一律需要有效 token。这是更安全的默认值：忘了把某个公开接口加进
 * 白名单，开发阶段会撞上一个显眼的 401；而忘了保护一个私有接口，留下的是一道
 * 无声的缺口。
 */
@ConfigurationProperties(prefix = "maipiao.gateway.auth")
public class GatewayAuthProperties {

    /**
     * Ant 风格的路径模式，拿包含 {@code /api} 前缀的请求路径去匹配
     * （例如 {@code /api/user/login}）。
     */
    private List<String> whitelist = new ArrayList<>();

    /**
     * 是否对每个已认证请求都查一次 Redis 黑名单。代价是一次 Redis 往返；关掉它
     * 意味着登出要等到 token 自然过期才生效。
     */
    private boolean checkBlacklist = true;

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }

    public boolean isCheckBlacklist() {
        return checkBlacklist;
    }

    public void setCheckBlacklist(boolean checkBlacklist) {
        this.checkBlacklist = checkBlacklist;
    }
}
