package com.stocksage.rag;

import com.stocksage.repository.ContextualGistCacheRepository;
import com.stocksage.service.KnowledgeIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextualEnricher 单元测试：缓存命中复用、未命中生成并按 hash 落缓存、
 * 各类失败场景 fail-open 返回 null。
 */
class ContextualEnricherTest {

    private ChatClient chatClient;
    private ContextualGistCacheRepository cacheRepository;
    private ContextualEnricher enricher;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        cacheRepository = mock(ContextualGistCacheRepository.class);
        enricher = new ContextualEnricher(chatClient, cacheRepository);
        ReflectionTestUtils.setField(enricher, "enabled", true);
        ReflectionTestUtils.setField(enricher, "maxGistWords", 40);
        ReflectionTestUtils.setField(enricher, "modelName", "test-fast-model");
    }

    private String generate(String childText) {
        return enricher.generateGist(
                "parent context text", childText, "child",
                "AAPL", "Apple Inc.", "10-K", "2023-11-03", "Item 1A Risk Factors");
    }

    @Test
    void disabledReturnsNullWithoutTouchingCacheOrModel() {
        ReflectionTestUtils.setField(enricher, "enabled", false);
        assertNull(generate("child text"));
        verify(cacheRepository, never()).findGist(anyString());
        verify(chatClient, never()).prompt();
    }

    @Test
    void cacheHitReturnsCachedGistWithoutLlmCall() {
        String hash = KnowledgeIngestionService.sha256("child text");
        when(cacheRepository.findGist(hash)).thenReturn(Optional.of("缓存的情境说明"));

        assertEquals("缓存的情境说明", generate("child text"));
        verify(chatClient, never()).prompt();
        verify(cacheRepository, never()).save(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cacheMissCallsLlmNormalizesAndSavesByHash() {
        String hash = KnowledgeIngestionService.sha256("child text");
        when(cacheRepository.findGist(hash)).thenReturn(Optional.empty());
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("  本段量化大中华区营收下滑并归因于汇率逆风。\n");

        assertEquals("本段量化大中华区营收下滑并归因于汇率逆风。", generate("child text"));
        verify(cacheRepository).save(
                eq(hash),
                eq("本段量化大中华区营收下滑并归因于汇率逆风。"),
                eq("test-fast-model"),
                eq("child"));
    }

    @Test
    void secondCallAfterCachePopulationSkipsLlm() {
        String hash = KnowledgeIngestionService.sha256("child text");
        when(cacheRepository.findGist(hash))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("第一次生成的说明"));
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("第一次生成的说明");

        assertEquals("第一次生成的说明", generate("child text"));
        assertEquals("第一次生成的说明", generate("child text"));
        // 同一文本第二次走缓存：只落一次缓存（等价于只付一次 LLM 调用）
        verify(cacheRepository, times(1)).save(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void llmFailureIsFailOpen() {
        when(cacheRepository.findGist(anyString())).thenReturn(Optional.empty());
        when(chatClient.prompt()).thenThrow(new RuntimeException("llm down"));

        assertNull(generate("child text"));
        verify(cacheRepository, never()).save(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void blankOutputReturnsNullAndIsNotCached() {
        when(cacheRepository.findGist(anyString())).thenReturn(Optional.empty());
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("   ");

        assertNull(generate("child text"));
        verify(cacheRepository, never()).save(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void blankTargetReturnsNull() {
        assertNull(generate("  "));
        verify(chatClient, never()).prompt();
    }
}
