package com.maipiao.common.web.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * 让 {@code selectPage} 真的分页。
 *
 * <p>没有这个拦截器，MyBatis-Plus 会默默无视 page 参数：查询不带 LIMIT 地跑，返回
 * 每一行，然后报一个 0 的总数。什么异常都不抛。分页接口看起来工作正常，却把整张表
 * 返回了 —— 在用户列表上，这是把整张表漏出去的一种很慢的方式。
 *
 * <p>注册在这里而不是每个服务各写一遍，是因为每个要分页的服务都需要同样的那三行，
 * 而漏写它们的失败形式是看不见的。
 *
 * <p>用的是 {@code @AutoConfiguration} 加
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 里的一个条目，而不是普通的 {@code @Configuration}。最初用的就是后者，放在一个没有
 * 任何服务会组件扫描到的包里，所以它从未被加载 —— 而症状恰恰是这个类存在的意义所在：
 * 一个分页接口返回了全部 200,006 个用户，同时报总数为 0。
 */
@AutoConfiguration
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class MybatisPaginationConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // MySQL，因为这里每个服务跑的都是它。若项目跨多种数据库，这个选择就得按
        // 数据源分别来做。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
