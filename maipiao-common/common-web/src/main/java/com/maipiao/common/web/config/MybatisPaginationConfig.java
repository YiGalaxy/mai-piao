package com.maipiao.common.web.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Makes {@code selectPage} actually paginate.
 *
 * <p>Without this interceptor MyBatis-Plus silently ignores the page argument:
 * it runs the query with no LIMIT, returns every row, and reports a total of
 * zero. Nothing throws. A paged endpoint looks like it works and returns the
 * whole table, which on a user list is a slow way to leak one.
 *
 * <p>Registered here rather than in each service because every service that
 * pages needs the same three lines, and the failure mode of forgetting them is
 * invisible.
 *
 * <p>{@code @AutoConfiguration} and an entry in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports},
 * not a plain {@code @Configuration}. It was the latter at first, in a package
 * no service component-scans, so it was never loaded - and the symptom was the
 * exact one this class exists to prevent: a paged endpoint returning all
 * 200,006 users and reporting a total of zero.
 */
@AutoConfiguration
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class MybatisPaginationConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // MySQL, because that is what every service here runs on. A project
        // spanning several databases would need this chosen per datasource.
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
