package com.stocksage.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 工具调用结果缓存 —— Cache-Aside 模式。
 *
 * 金融数据工具（K线、财务指标、技术指标等）的返回值是 JSON 字符串。
 * 同一对话或不同用户在短时间内查询相同标的时，重复调用 Python 数据服务是浪费。
 * 本组件在 Redis 中缓存工具结果，命中缓存直接返回，未命中则调数据源并写入缓存。
 *
 * 设计要点：
 * - 使用 StringRedisTemplate 而非 RedisTemplate<String,Object>，
 *   因为工具返回值本身就是 JSON 字符串，无需二次序列化
 * - 不同数据类型使用不同 TTL（财务指标变化慢 → 长TTL，行情数据 → 短TTL）
 * - 是否可缓存由调用方传入谓词决定（如错误响应不缓存），缓存组件不内嵌业务协议
 * - 键前缀 "cache:tool:" 与短期记忆的 "memory:conv:" 隔离
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolResultCache {

    private static final String KEY_PREFIX = "cache:tool:";

    private final StringRedisTemplate stringRedisTemplate;
    private final AtomicBoolean redisUnavailableLogged = new AtomicBoolean(false);

    /**
     * 从缓存获取，未命中则调用 supplier 并缓存结果。
     *
     * @param category  数据类别，如 "kline"、"financial"（构成 key 的一部分）
     * @param keyParts  组成 key 的参数列表，如 ["AAPL", "daily", "30"]
     * @param ttl       缓存过期时间
     * @param supplier  缓存未命中时的数据源调用
     * @param cacheable 判断结果是否值得缓存（例如失败响应不缓存，避免错误固化）
     * @return 工具调用结果（JSON 字符串）
     */
    public String getOrFetch(String category, String[] keyParts, Duration ttl, Supplier<String> supplier,
                             Predicate<String> cacheable) {
        String cacheKey = buildKey(category, keyParts);
        boolean cacheAvailable = true;

        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            redisUnavailableLogged.set(false);
            if (cached != null) {
                log.debug("Cache HIT: {}", cacheKey);
                return cached;
            }
        } catch (Exception e) {
            cacheAvailable = false;
            logCacheFailure("read", cacheKey, e);
        }

        log.debug("Cache MISS: {}", cacheKey);
        String result = supplier.get();

        if (cacheAvailable && result != null && cacheable.test(result)) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, result, ttl);
                redisUnavailableLogged.set(false);
            } catch (Exception e) {
                logCacheFailure("write", cacheKey, e);
            }
        }

        return result;
    }

    /**
     * 构造稳定、可读的 Redis 缓存 key。
     *
     * <p>参数会 trim 并转小写，减少大小写或首尾空格导致的重复缓存。</p>
     */
    private String buildKey(String category, String[] parts) {
        StringBuilder sb = new StringBuilder(KEY_PREFIX).append(category);
        for (String part : parts) {
            sb.append(':').append(part == null ? "" : part.trim().toLowerCase());
        }
        return sb.toString();
    }

    private void logCacheFailure(String operation, String cacheKey, Exception e) {
        if (isRedisConnectionFailure(e)) {
            if (redisUnavailableLogged.compareAndSet(false, true)) {
                log.warn(
                        "Redis cache unavailable; continuing without tool cache. First {} failure for {}: {}",
                        operation,
                        cacheKey,
                        rootMessage(e)
                );
            } else {
                log.debug(
                        "Redis cache still unavailable during {} for {}: {}",
                        operation,
                        cacheKey,
                        rootMessage(e)
                );
            }
            log.debug("Redis cache {} failure details for {}", operation, cacheKey, e);
            return;
        }
        log.warn("Cache {} failed, falling through to source: {}", operation, cacheKey, e);
    }

    private boolean isRedisConnectionFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String className = current.getClass().getName();
            if (className.contains("RedisConnection") || className.contains("PoolException")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        Throwable last = error;
        while (current != null && current.getCause() != current) {
            last = current;
            current = current.getCause();
        }
        String message = last == null ? "" : last.getMessage();
        return message == null || message.isBlank() ? String.valueOf(error.getMessage()) : message;
    }
}
