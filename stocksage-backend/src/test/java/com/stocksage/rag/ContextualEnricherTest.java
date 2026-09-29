package com.stocksage.rag;

import com.stocksage.repository.ContextualGistCacheRepository;
import com.stocksage.knowledge.KnowledgeIngestionService;
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

    @Test
    void expiredModelResultIsNotCachedOrDowngraded() {
        var store = mock(com.stocksage.research.ModelInvocationStore.class);
        var model = mock(org.springframework.ai.chat.model.ChatModel.class);
        when(store.begin(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.anyMap())).thenReturn("gist");
        useRealClient(model, store);
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        var deadline = mock(com.stocksage.tool.ToolCallContext.RunDeadline.class);
        var failure = new com.stocksage.exception.ResearchBudgetExceededException(71L,
                com.stocksage.exception.ResearchBudgetExceededException.Reason.DEADLINE);
        when(deadline.remainingMillis()).thenAnswer(call -> {
            if (expired.get()) throw failure;
            return 1000L;
        });
        when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
            expired.set(true);
            return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                    new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("本段说明经营风险。"))));
        });
        var execution = new com.stocksage.tool.ToolCallContext.RunExecution(71L, 1, "owner", "trace", System.currentTimeMillis() + 60_000);
        org.junit.jupiter.api.Assertions.assertSame(failure,
                org.junit.jupiter.api.Assertions.assertThrows(com.stocksage.exception.ResearchBudgetExceededException.class,
                        () -> com.stocksage.tool.ToolCallContext.withRunExecution(execution,
                                () -> com.stocksage.tool.ToolCallContext.withRunDeadline(deadline, () -> generate("child text")))));
        verify(cacheRepository, never()).save(anyString(), anyString(), anyString(), anyString());
        assertNull(com.stocksage.tool.ToolCallContext.currentRunDeadline());
        assertNull(com.stocksage.tool.ToolCallContext.currentRunExecution());
    }

    private void useRealClient(org.springframework.ai.chat.model.ChatModel model, com.stocksage.research.ModelInvocationStore store) {
        chatClient = ChatClient.builder(model).defaultSystem("gist system")
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder().model("test-fast-model").build()).build();
        enricher = new ContextualEnricher(chatClient, cacheRepository, store);
        ReflectionTestUtils.setField(enricher, "enabled", true);
        ReflectionTestUtils.setField(enricher, "maxGistWords", 40);
        ReflectionTestUtils.setField(enricher, "modelName", "test-fast-model");
    }

    @Test
    void runGistUsesRealInputAttributionOnlyOnCacheMissAndBudgetRejectionStopsBeforeModel() {
        var store = mock(com.stocksage.research.ModelInvocationStore.class);
        var model = mock(org.springframework.ai.chat.model.ChatModel.class);
        useRealClient(model, store);
        when(store.begin(org.mockito.ArgumentMatchers.any(), eq("contextual-gist"), org.mockito.ArgumentMatchers.anyMap())).thenReturn("gist");
        when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                        new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("风险说明")))));
        when(cacheRepository.findGist(KnowledgeIngestionService.sha256("child text")))
                .thenReturn(Optional.empty()).thenReturn(Optional.of("风险说明"));
        var execution = new com.stocksage.tool.ToolCallContext.RunExecution(72L, 2, "owner", "trace", System.currentTimeMillis() + 60_000);
        com.stocksage.tool.ToolCallContext.withRunExecution(execution, () -> {
            assertEquals("风险说明", generate("child text"));
            assertEquals("风险说明", generate("child text"));
            return null;
        });
        var captured = org.mockito.ArgumentCaptor.forClass(com.stocksage.model.dto.ModelInvocationContext.class);
        verify(store).begin(captured.capture(), eq("contextual-gist"), org.mockito.ArgumentMatchers.anyMap());
        var prompt = org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(model).call(prompt.capture());
        assertEquals(execution.runId(), captured.getValue().runId());
        assertEquals(execution.attempt(), captured.getValue().attempt());
        assertEquals(execution.leaseToken(), captured.getValue().leaseToken());
        assertEquals(KnowledgeIngestionService.sha256(prompt.getValue().getUserMessage().getText()), captured.getValue().inputSha256());
        assertNull(captured.getValue().evidenceSnapshotId());
        verify(store).finish(eq("gist"), eq("SUCCEEDED"), org.mockito.ArgumentMatchers.anyMap());
        org.mockito.Mockito.clearInvocations(model, cacheRepository);
        var exhausted = new com.stocksage.exception.ResearchBudgetExceededException(72L,
                com.stocksage.exception.ResearchBudgetExceededException.Reason.TOKEN_LIMIT);
        when(store.begin(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.anyMap())).thenThrow(exhausted);
        org.junit.jupiter.api.Assertions.assertSame(exhausted, org.junit.jupiter.api.Assertions.assertThrows(
                com.stocksage.exception.ResearchBudgetExceededException.class,
                () -> com.stocksage.tool.ToolCallContext.withRunExecution(execution, () -> generate("other text"))));
        verify(model, never()).call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(cacheRepository, never()).save(anyString(), anyString(), anyString(), anyString());
        assertNull(com.stocksage.tool.ToolCallContext.currentRunExecution());
        var missingOwner = org.junit.jupiter.api.Assertions.assertThrows(com.stocksage.exception.ResearchBudgetExceededException.class,
                () -> com.stocksage.tool.ToolCallContext.withRunDeadline(
                        new com.stocksage.tool.ToolCallContext.RunDeadline(72L, execution.deadlineEpochMs()),
                        () -> generate("unattributed text")));
        assertEquals(com.stocksage.exception.ResearchBudgetExceededException.Reason.BUDGET_UNAVAILABLE, missingOwner.reason());
        verify(model, never()).call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class));
        var rejected = new com.stocksage.research.ModelInvocationStore.InvocationRejectedException("owner changed");
        org.mockito.Mockito.doThrow(rejected).when(store).begin(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.anyMap());
        var lostOwner = org.junit.jupiter.api.Assertions.assertThrows(com.stocksage.exception.ResearchBudgetExceededException.class,
                () -> com.stocksage.tool.ToolCallContext.withRunExecution(execution, () -> generate("another text")));
        org.junit.jupiter.api.Assertions.assertSame(rejected, lostOwner.getCause());
        verify(model, never()).call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    private ChatClient chatClient;
    private ContextualGistCacheRepository cacheRepository;
    private ContextualEnricher enricher;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        cacheRepository = mock(ContextualGistCacheRepository.class);
        enricher = new ContextualEnricher(chatClient, cacheRepository,
                mock(com.stocksage.research.ModelInvocationStore.class));
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
