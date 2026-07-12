package com.stocksage.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortTermMemoryDegradationTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private ShortTermMemory memory;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        memory = new ShortTermMemory(redisTemplate, mock(ChatClient.class));
        ReflectionTestUtils.setField(memory, "ttlHours", 24);
        ReflectionTestUtils.setField(memory, "maxContextMessages", 20);
    }

    @Test
    void addMessageContinuesWithEmptyContextWhenRedisTimesOut() {
        when(listOperations.rightPush(anyString(), anyString()))
                .thenThrow(new QueryTimeoutException("Redis command timed out"));

        var snapshot = memory.addMessage(42L, "user", "hello");

        assertThat(snapshot.getConversationId()).isEqualTo(42L);
        assertThat(snapshot.getMessages()).isEmpty();
        assertThat(snapshot.getMessageCount()).isZero();
    }

    @Test
    void readsAndMaintenanceAreBestEffortWhenRedisIsUnavailable() {
        when(listOperations.range(anyString(), any(Long.class), any(Long.class)))
                .thenThrow(new QueryTimeoutException("Redis command timed out"));
        when(redisTemplate.delete(anyString()))
                .thenThrow(new QueryTimeoutException("Redis command timed out"));

        assertThat(memory.getContext(42L)).isEmpty();
        assertThatCode(() -> memory.clear(42L)).doesNotThrowAnyException();
        assertThatCode(() -> memory.replaceWithMessages(42L, List.of("user: hello")))
                .doesNotThrowAnyException();
    }
}
