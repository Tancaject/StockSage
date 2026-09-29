package com.stocksage.agent;

import com.stocksage.model.dto.ModelInvocationContext;
import com.stocksage.research.ModelInvocationStore;
import com.stocksage.exception.ResearchBudgetExceededException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelInvocationAdvisorTest {
    private final ModelInvocationStore store = mock(ModelInvocationStore.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ModelInvocationContext context = new ModelInvocationContext(42L, 2, "owner", "trace", "snapshot-test",
            System.currentTimeMillis() + 3_600_000);

    private ChatClient client() {
        when(store.begin(eq(context), eq("bull"), anyMap())).thenReturn("invocation");
        return ChatClient.builder(model).defaultSystem("system")
                .defaultOptions(OpenAiChatOptions.builder().model("alias").temperature(0.4).maxTokens(256).build())
                .defaultAdvisors(new ModelInvocationAdvisor("bull", store)).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void realClientPreservesTextAndRecordsLatestUsageSnapshotBeforeCompletion() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("hello", 3), response(" world", 7)));
        String output = client().prompt().user("question")
                .advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context))
                .stream().content().reduce("", String::concat).block();
        assertThat(output).isEqualTo("hello world");
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(store).begin(eq(context), eq("bull"), request.capture());
        assertThat(request.getValue()).containsEntry("requestedModel", "alias")
                .containsEntry("temperature", 0.4).containsEntry("maxTokens", 256);
        assertThat(request.getValue().get("promptSha256").toString()).matches("[a-f0-9]{64}");
        verify(store).finish(eq("invocation"), eq("SUCCEEDED"), argThat(result ->
                "PROVIDER".equals(result.get("usageSource")) && Integer.valueOf(7).equals(result.get("totalTokens"))
                        && "actual-revision".equals(result.get("actualModel"))));
    }

    @Test
    void cancellationAndFailureDoNotInventUsageAndMissingContextDoesNotCreateRun() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
        var subscription = client().prompt().user("question")
                .advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context)).stream().content().subscribe();
        subscription.dispose();
        verify(store).finish(eq("invocation"), eq("CANCELLED"), argThat(result ->
                "NO_DATA".equals(result.get("usageSource")) && !result.containsKey("totalTokens")));
        reset(store);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider unavailable"));
        assertThatThrownBy(() -> client().prompt().user("q")
                .advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context)).call().content())
                .hasMessageContaining("provider unavailable");
        verify(store).finish(eq("invocation"), eq("FAILED"), argThat(result ->
                "NO_DATA".equals(result.get("usageSource"))));
        clearInvocations(store);
        assertThatThrownBy(() -> client().prompt().user("q").call().content()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }

    @Test
    void failedDurableStartPreventsModelCall() {
        ChatClient client = client();
        when(store.begin(eq(context), anyString(), anyMap())).thenThrow(new IllegalStateException("storage failed"));
        assertThatThrownBy(() -> client.prompt().user("q")
                .advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context)).call().content())
                .hasMessageContaining("storage failed");
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void absoluteRunTimerCancelsEvenAnActiveStreamAndExpiredRequestsNeverCallProvider() throws InterruptedException {
        var scheduler = mock(reactor.core.scheduler.Scheduler.class);
        var timer = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(java.util.concurrent.TimeUnit.NANOSECONDS)))
                .thenAnswer(call -> {
                    timer.set(call.getArgument(0));
                    return reactor.core.Disposables.single();
                });
        var source = reactor.core.publisher.Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(model.stream(any(Prompt.class))).thenReturn(source.asFlux().doOnCancel(() -> cancelled.set(true)));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var text = new StringBuilder();
        var received = new java.util.concurrent.CountDownLatch(2);
        try (var schedulers = mockStatic(reactor.core.scheduler.Schedulers.class, CALLS_REAL_METHODS)) {
            schedulers.when(reactor.core.scheduler.Schedulers::parallel).thenReturn(scheduler);
            client().prompt().user("q").advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context))
                    .stream().content().subscribe(chunk -> { text.append(chunk); received.countDown(); }, failure::set);
            Runnable firstTimer = timer.get();
            source.tryEmitNext(response("hello", 3));
            source.tryEmitNext(response(" world", 7));
            // The model stream publishes on boundedElastic; expire only after both chunks arrived.
            assertThat(received.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(timer.get()).isSameAs(firstTimer);
            firstTimer.run();
        }
        assertThat(text.toString()).isEqualTo("hello world");
        assertThat(cancelled.get()).isTrue();
        assertThat(failure.get()).isInstanceOf(ResearchBudgetExceededException.class);
        verify(store).finish(eq("invocation"), eq("FAILED"), argThat(result -> Integer.valueOf(7).equals(result.get("totalTokens"))));

        clearInvocations(store, model);
        var expired = new ModelInvocationContext(42L, 2, "owner", "trace", "snapshot-test", 1L);
        assertThatThrownBy(() -> client().prompt().user("q")
                .advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, expired)).call().content())
                .isInstanceOf(ResearchBudgetExceededException.class);
        verifyNoInteractions(store);
        verify(model, never()).call(any(Prompt.class));
    }

    private ChatResponse response(String text, int total) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model("actual-revision")
                        .usage(new DefaultUsage(2, total - 2, total,
                                new org.springframework.ai.openai.api.OpenAiApi.Usage(total - 2, 2, total))).build());
    }

    @Test
    void normalizedMissingUsageCannotBecomeProviderZeroOrCalculatedTotal() {
        var nativeUsage = new org.springframework.ai.openai.api.OpenAiApi.Usage(null, 2, null);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(2, null, null, nativeUsage)).build()));
        client().prompt().user("q").advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context)).call().content();
        verify(store).finish(eq("invocation"), eq("SUCCEEDED"), argThat(result ->
                "PROVIDER".equals(result.get("usageSource")) && Integer.valueOf(2).equals(result.get("inputTokens"))
                        && !result.containsKey("outputTokens") && !result.containsKey("totalTokens")));
        clearInvocations(store);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(null, null)).build()));
        client().prompt().user("q").advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context)).call().content();
        verify(store).finish(eq("invocation"), eq("SUCCEEDED"), argThat(result ->
                "SDK_NORMALIZED".equals(result.get("usageSource")) && !result.containsKey("inputTokens")
                        && !result.containsKey("outputTokens") && !result.containsKey("totalTokens")));
    }

    @Test
    void synchronousModelReceivesExplicitRunDeadlineAndScopeIsRestoredOnFailure() {
        var outer = new com.stocksage.tool.ToolCallContext.RunDeadline(77L, System.currentTimeMillis() + 60_000);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            var actual = com.stocksage.tool.ToolCallContext.currentRunDeadline();
            assertThat(actual.runId()).isEqualTo(context.runId());
            assertThat(actual.deadlineEpochMs()).isEqualTo(context.deadlineEpochMs());
            throw new IllegalStateException("provider failure");
        });
        com.stocksage.tool.ToolCallContext.withRunDeadline(outer, () -> {
            assertThatThrownBy(() -> client().prompt().user("q")
                    .advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context)).call().content())
                    .hasMessageContaining("provider failure");
            assertThat(com.stocksage.tool.ToolCallContext.currentRunDeadline()).isSameAs(outer);
            return null;
        });
        assertThat(com.stocksage.tool.ToolCallContext.currentRunDeadline()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void admissionFactsDistinguishTextBytesAndRequestedControlsFromProviderTokenCeilings() {
        when(model.call(any(Prompt.class))).thenReturn(response("ok", 3));
        client().prompt().user("中文")
                .options(OpenAiChatOptions.builder().maxCompletionTokens(1024).N(2)
                        .reasoningEffort("low").extraBody(Map.of("enable_thinking", true,
                                "thinking_budget", 512, "private-setting", "do-not-store")).build())
                .advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, context)).call().content();
        ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass(Map.class);
        verify(store).begin(eq(context), eq("bull"), captured.capture());
        assertThat(captured.getValue()).containsEntry("inputTextUtf8Bytes", 12L)
                .containsEntry("inputMessageCount", 2).containsEntry("inputTokenCountStatus", "NOT_COUNTED")
                .containsEntry("optionsScope", "CHAT_CLIENT_REQUEST_OPTIONS")
                .containsEntry("totalOutputTokenCeilingStatus", "UNVERIFIED")
                .containsEntry("maxCompletionTokens", 1024).containsEntry("n", 2)
                .containsEntry("reasoningEffort", "low").containsEntry("enableThinking", true)
                .containsEntry("thinkingBudget", 512).containsEntry("extraBodyPresent", true)
                .containsEntry("toolsPresent", false);
        assertThat(captured.getValue().toString()).doesNotContain("do-not-store", "private-setting");
    }
}
