package com.maipiao.common.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置。
 *
 * <p>key 一律是纯字符串 —— 这才让 {@code KEYS seat:map:*} 和那些 Lua 脚本可读，
 * 也正是 bitmap/zset 命令所要求的。
 *
 * <p>value 用 JSON 而不是 JDK 序列化，这样 A 服务写进去的值，B 服务还能看得懂
 * （形状对得上的话也能读）。JDK 序列化还会把类名焊进去，类一改名就崩。
 *
 * <p>{@link StringRedisTemplate} 也一并注册了：座位锁定这条热点路径直接拿它配上
 * 原始字符串参数来用，因为为一个说到底只是 bitmap 偏移量的东西去拼 JSON 纯属多余
 * 开销。
 */
@Configuration
public class RedisConfig {

    /**
     * 给 value 用的 ObjectMapper。
     *
     * <p>{@code activateDefaultTyping} 会把类信息写进 JSON，这样 {@code List<SeatDTO>}
     * 取出来还是原来的类型，而不会变成 {@code List<LinkedHashMap>}。代价是载荷和
     * 类名绑在了一起 —— 这里可以接受，因为这些值都是短命的缓存，不是长期存储。
     */
    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildObjectMapper());

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        // 少了这一句，连接不会被还回连接池。
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }
}
