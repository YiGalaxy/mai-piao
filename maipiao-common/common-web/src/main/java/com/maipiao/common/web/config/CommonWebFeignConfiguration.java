package com.maipiao.common.web.config;

import com.maipiao.common.web.feign.FeignRequestInterceptor;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 只在 OpenFeign 确实位于 classpath 上时，才注册这个 Feign 拦截器。
 *
 * <p>它单独占一个类是有意为之。把
 * {@code @ConditionalOnClass(RequestInterceptor.class)} 标在主自动配置里的某个
 * {@code @Bean} 方法上，看着像是能行，实际不行：Spring 要判断这个条件，就得先加载
 * 外层的配置类，而加载它就要解析各个方法签名 —— 包括返回类型实现了
 * {@code feign.RequestInterceptor} 的那个方法。这个类不在，于是启动直接死在
 * {@code ClassNotFoundException: feign.RequestInterceptor}，条件压根轮不到被判断。
 *
 * <p>标在类上时，条件是从 ASM 元数据判断出来的，不需要加载任何东西，所以整个类可以
 * 被干净地跳过。
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
