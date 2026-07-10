package com.stocksage.cache;

import com.stocksage.client.DataServicePayloads;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolResultCacheTest {

    @Test
    void fallsThroughWithoutWritingWhenRedisReadIsUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:tool:stock-search-v2:sn:8"))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        ToolResultCache cache = new ToolResultCache(redisTemplate);

        String result = cache.getOrFetch(
                "stock-search-v2",
                new String[]{"sn", "8"},
                Duration.ofMinutes(15),
                () -> "{\"ok\":true}",
                body -> !DataServicePayloads.isFailure(body)
        );

        assertThat(result).isEqualTo("{\"ok\":true}");
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void skipsCachingWhenCacheablePredicateRejectsResult() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ToolResultCache cache = new ToolResultCache(redisTemplate);

        String result = cache.getOrFetch(
                "kline",
                new String[]{"NVDA"},
                Duration.ofMinutes(30),
                () -> "{\"error\":true,\"message\":\"upstream down\"}",
                body -> !DataServicePayloads.isFailure(body)
        );

        assertThat(result).contains("upstream down");
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
