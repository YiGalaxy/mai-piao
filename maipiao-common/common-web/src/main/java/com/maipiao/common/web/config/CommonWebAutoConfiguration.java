package com.maipiao.common.web.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.maipiao.common.core.config.JwtProperties;
import com.maipiao.common.core.util.JwtUtil;
import com.maipiao.common.web.context.UserContextInterceptor;
import com.maipiao.common.web.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 common-web 的这些 bean 装配进任何依赖本模块的服务。
 *
 * <p>通过
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册，而不是靠组件扫描捞出来。这样各个服务可以自由选择自己的基础包 —— 如果依赖
 * 扫描的话，每个服务都得待在 {@code com.maipiao} 下面才能让这些共享 bean 出现。
 *
 * <p>用 {@link ConditionalOnWebApplication} 限定 SERVLET：网关是 WebFlux 应用，
 * 绝不能让它去注册一个 Servlet 拦截器。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
public class CommonWebAutoConfiguration implements WebMvcConfigurer {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtUtil jwtUtil(JwtProperties jwtProperties) {
        return new JwtUtil(jwtProperties);
    }

    /**
     * BCrypt，用默认强度（10）。本项目里密码在任何地方都不以明文存储或打印。
     */
    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 把每个 {@code Long} 都序列化成 JSON 字符串。
     *
     * <p>Snowflake id 是 19 位。JavaScript 的数字是 IEEE-754 双精度，所以超过 2^53-1
     * 的值一解析就丢精度 —— {@code 2101643262211633153} 到浏览器里变成
     * {@code 2101643262211633200}。客户端再把这个数发回来，查询就会对一条明明存在的
     * 记录报"找不到"，读起来像路由或权限问题，而不像数字格式问题。
     *
     * <p>序列化成字符串能让这个值原封不动地穿过 JSON。代价是前端里每个 id 都是字符串，
     * 包括座位序号这类小数字 —— 值得，因为另一条路是让那些用来寻址的值被悄悄改坏。
     *
     * <p>另一种做法 —— 给每个 id 字段加
     * {@code @JsonSerialize(using = ToStringSerializer.class)} 注解 —— 下次有人加字段时
     * 太容易忘，而忘了它产生的就是同一种静默损坏。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }

    /*
     * Feign 拦截器改由 {@link CommonWebFeignConfiguration} 注册。在这里用方法级的
     * {@code @ConditionalOnClass} 声明它是行不通的：要判断这个条件，本类仍然必须先
     * 被加载，而加载它就要解析方法签名，其中那个方法的返回类型实现了
     * {@code feign.RequestInterceptor} —— 在不使用 OpenFeign 的服务上这个类并不存在。
     *
     * 写成普通块注释而不是 Javadoc：它讲的是这个类为什么长成这样，不是某个成员自己的
     * 文档，挂在任何成员上都是错的位置。
     */

    /**
     * 对每个请求，把 X-User-Id 读进 {@link com.maipiao.common.web.context.UserContext}。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/**")
                .order(0);
    }
}
