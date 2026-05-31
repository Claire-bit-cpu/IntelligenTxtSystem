package com.example.intelligentxtsystem.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 配置类
 *
 * 设计说明：
 * 1. 只配置 StringRedisTemplate（Key 和 Value 都是 String）
 * 2. JSON 序列化/反序列化 手动用 ObjectMapper 完成
 * 3. 避免使用 RedisCallback（过时 API）
 * 4. 避免类型转换问题（String → byte[]）
 */
@Configuration
public class RedisConfig {

    /**
     * 配置 StringRedisTemplate
     * Key 和 Value 都使用 String 序列化（无乱码问题）
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(
            org.springframework.data.redis.connection.RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 提供 ObjectMapper Bean（用于 JSON 序列化/反序列化）
     * 解决 LocalDateTime 序列化问题
     */
    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 1. 注册 JavaTimeModule（支持 LocalDateTime 序列化）
        objectMapper.registerModule(new JavaTimeModule());

        // 2. 设置访问权限（否则私有属性无法序列化）
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // 3. 反序列化时忽略未知属性（避免字段增减导致报错）
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return objectMapper;
    }
}
