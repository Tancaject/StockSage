package com.stocksage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 字符串序列化配置。
 *
 * <p>项目当前只需要存短期记忆文本和工具缓存文本，使用 StringRedisTemplate 可以避免
 * Jackson 默认类型信息带来的反序列化攻击面。</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
