package com.maipiao.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Which paths may be called without a token.
 *
 * <p>Configured rather than hard-coded so that opening up a new public endpoint
 * is a config change with a review trail, instead of a code change buried in a
 * filter.
 *
 * <p>Everything not listed here requires a valid token. That is the safer
 * default: forgetting to whitelist a public endpoint produces an obvious 401
 * during development, whereas forgetting to protect a private one produces a
 * silent hole.
 */
@ConfigurationProperties(prefix = "maipiao.gateway.auth")
public class GatewayAuthProperties {

    /**
     * Ant-style path patterns, matched against the request path including the
     * {@code /api} prefix (e.g. {@code /api/user/login}).
     */
    private List<String> whitelist = new ArrayList<>();

    /**
     * Whether to check the Redis blacklist on every authenticated request.
     * Costs one Redis round trip; turning it off means logout no longer takes
     * effect until the token expires.
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
