package com.maipiao.common.web.config;

import com.maipiao.common.web.feign.FeignRequestInterceptor;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Feign interceptor only when OpenFeign is actually on the
 * classpath.
 *
 * <p>This lives in its own class on purpose. Putting
 * {@code @ConditionalOnClass(RequestInterceptor.class)} on a {@code @Bean}
 * method inside the main auto-configuration looks like it would work, but it
 * does not: to evaluate the condition Spring must first load the enclosing
 * configuration class, and loading it resolves the method signatures -
 * including the return type that implements {@code feign.RequestInterceptor}.
 * The class is not there, and startup dies with
 * {@code ClassNotFoundException: feign.RequestInterceptor} before the
 * condition is ever consulted.
 *
 * <p>On a class, the condition is evaluated from ASM metadata without loading
 * anything, so the whole class is skipped cleanly.
 */
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
public class CommonWebFeignConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeignRequestInterceptor feignRequestInterceptor() {
        return new FeignRequestInterceptor();
    }
}
