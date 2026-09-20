package com.maipiao.common.redis.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 让 {@code @SchedulerLock} 真的加锁。
 *
 * <p>注册在这里而不是每个服务各写一遍，理由和 {@code MybatisPaginationConfig} 一样：
 * 每个要用它的服务都需要的是一模一样的那几行，而漏写的失败形式是看不见的 ——
 * 注解照样能编译、服务照样能启动，只是锁从来没被获取过。
 *
 * <p>{@code @ConditionalOnClass} 是必要的，因为这个模块被所有连 Redis 的服务依赖，
 * 而其中只有 order 和 pay 引入了 shedlock。少了这个条件，其余服务会因为找不到
 * {@code LockProvider} 这个类而启动失败 —— 一个由「有人给别的模块加了依赖」引起的、
 * 看起来毫无关联的崩溃。
 *
 * <h3>锁在 Redis 里，而不是在数据库里</h3>
 *
 * <p>ShedLock 官方最常用的是 JDBC provider，它需要在库里建一张 {@code shedlock} 表。
 * 这里否决了它：这个项目是<b>按服务分库</b>的，order 和 pay 各有一个库，
 * 于是那张表要建两次、迁移要写两份，而它存在的唯一目的只是让两个进程不要同时跑同一段代码。
 *
 * <p>有人会说数据库锁更可靠 —— Redis 重启锁就没了。但这里可靠的是<b>错的东西</b>：
 * 锁丢了的后果是两个实例同时跑一遍超时扫描或退款重试，而这两件事都是幂等的
 * （状态机 CAS、退款单唯一键），跑两遍只是浪费一点资源。
 * 为了防住一个「跑两遍也正确」的场景，去接受一份跨库的 DDL 和它的迁移负担，
 * 是拿真实的复杂度换一个不存在的收益。
 *
 * <h3>加锁是为了省事，不是为了正确</h3>
 *
 * <p>这一点值得说清楚，因为它决定了上面那个取舍成立与否。超时扫描与退款重试都是
 * 幂等的：两个实例同时扫，扫到同一批订单，其中一个的比较并交换会成功、另一个返回
 * {@code false}。结果没错。加锁省下的是重复的数据库往返、重复的行锁争用，
 * 以及两份互相交错的日志。
 *
 * <p>反过来说，如果哪天有个任务不幂等，那它需要的不是这个锁 ——
 * 锁在 Redis 抖动、TTL 到期、时钟漂移时都会失效，一个不幂等的任务不该把正确性
 * 押在一个会失效的东西上。这里唯一的例外是抢购放行器，它的安全性来自 Lua 脚本的
 * 原子性，所以它<b>刻意没有</b>加这个注解。
 */
@AutoConfiguration
@ConditionalOnClass({LockProvider.class, RedisConnectionFactory.class})
// defaultLockAtMostFor 是所有 @SchedulerLock 的兜底值：持锁的实例如果直接死掉，
// 锁最多这么久之后自动放开。没有它，一次 kill -9 会让那个任务从此再也不运行 ——
// 而且是安静的，因为从外面看只是个不再刷新的日志。
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    /**
     * 锁名会变成 {@code <keyPrefix>:<environment>:<锁名>}。
     *
     * <p>environment 用服务名，这样两个服务里同名的任务不会互相挡住 ——
     * 这类撞名不报错，只会让其中一个任务莫名地不执行，很难查。
     */
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory,
                                     @Value("${spring.application.name:maipiao}") String applicationName) {
        // ShedLock 5.x 没有静态的 builder()，Builder 是要 new 出来的。
        return new RedisLockProvider.Builder(connectionFactory)
                .keyPrefix("maipiao:shedlock")
                .environment(applicationName)
                .build();
    }
}
