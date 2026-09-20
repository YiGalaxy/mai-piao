package com.maoyan.common.redis.config;

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
 * Redis serialization setup.
 *
 * <p>Keys are always plain strings - that is what makes {@code KEYS seat:map:*}
 * and the Lua scripts readable, and it is what the bitmap/zset commands expect.
 *
 * <p>Values are JSON rather than JDK serialization so that a value written by
 * one service can still be inspected (and, if the shape matches, read) by
 * another. JDK serialization also bakes in the class name, which breaks the
 * moment you rename a class.
 *
 * <p>{@link StringRedisTemplate} is registered too: the hot seat-lock path uses
 * it directly with raw string arguments, because building JSON for what is
 * ultimately a bitmap offset is pure overhead.
 */
@Configuration
public class RedisConfig {

    /**
     * ObjectMapper used for values.
     *
     * <p>{@code activateDefaultTyping} writes a class hint into the JSON so that
     * a {@code List<SeatDTO>} round-trips as the same type instead of becoming
     * {@code List<LinkedHashMap>}. The trade-off is that the payload is coupled
     * to the class name - acceptable here because these values are short-lived
     * caches, not long-term storage.
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

        // Without this, a connection is not released back to the pool.
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
