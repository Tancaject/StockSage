package com.stocksage.conversation;

import com.stocksage.knowledge.ResearchMemoryService;
import com.stocksage.service.TickerResolutionService;
import com.stocksage.service.ToolPrefetchService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.RoutingDecisionObserver;
import com.stocksage.agent.intent.IntentDecision;
import com.stocksage.agent.intent.IntentRecognitionResult;
import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.ShortTermMemory;
import com.stocksage.model.dto.ChatRequest;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.rag.RagService;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.ToolCallContext;
import com.stocksage.tool.ToolCallEventBus;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceEventStore;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise the public stream with controlled model/relay sources and the real prompt assembler. */
class ChatServiceStreamLifecycleTest {
    private static final String TRACE = "chat-stream-test";
    private static final String USER = "stream-owner";
    private static final long CONVERSATION = 81L;
    private static final String QUESTION = "解释市盈率";
    private final ConversationMessageService messages = mock(ConversationMessageService.class);
    private final Coordinator coordinator = mock(Coordinator.class);
    private final ToolPrefetchService prefetch = mock(ToolPrefetchService.class);
    private final TraceService traces = mock(TraceService.class);
    private final ShortTermMemory shortMemory = mock(ShortTermMemory.class);
    private final LongTermMemory longMemory = mock(LongTermMemory.class);
    private final ChatStreamEmitter emitter = mock(ChatStreamEmitter.class);
    private final ToolCallEventBus bus = mock(ToolCallEventBus.class);
    private final Sinks.Many<TraceEventStore.StoredEvent> events = Sinks.many().unicast().onBackpressureBuffer();
    private final CountDownLatch cleaned = new CountDownLatch(1);
    private final ChatRequest request = new ChatRequest();
    private final LocalDateTime observedAt = LocalDateTime.of(2026, 9, 26, 10, 0);
    private ChatService service;

    @BeforeEach
    void prepareRequest() {
        request.setUserId(USER);
        request.setConversationId(CONVERSATION);
        request.setMessage(QUESTION);
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION);
        conversation.setUserId(USER);
        Message question = new Message();
        question.setId(91L);
        question.setRole("user");
        question.setContent(QUESTION);
        question.setCreatedAt(observedAt);
        when(messages.getConversationForUser(CONVERSATION, USER)).thenReturn(conversation);
        when(messages.appendUserTurn(USER, CONVERSATION, QUESTION, false))
                .thenReturn(new ConversationMessageService.UserTurn(question, List.of(), false, List.of()));
        when(messages.listMessages(USER, CONVERSATION)).thenReturn(List.of(question));
        when(traces.startTrace(USER, CONVERSATION, QUESTION)).thenReturn(TRACE);
        var decision = new IntentDecision(null, null, PlanRoute.DIRECT, 1, null, null,
                Map.of(), QUESTION, Map.of(), false, List.of());
        var intent = new IntentRecognitionResult(decision, "DIRECT", true, "", List.of(), "", 0);
        when(coordinator.recognizeIntent(eq(QUESTION), anyList(), eq(0), eq(false), anyList())).thenReturn(intent);
        when(coordinator.planRecognized(eq(intent), eq(QUESTION), anyInt()))
                .thenReturn(new ExecutionPlan(PlanRoute.DIRECT, "chat", "plan", List.of(), "ready", ModelTier.FAST));
        when(coordinator.selectFinalAnswerModel(ModelTier.FAST, false, false))
                .thenReturn(new Coordinator.SelectedModel(ModelTier.FAST, "test-model"));
        when(prefetch.prefetch(any(), anyString(), anyString(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(new ToolPrefetchService.PreparedToolContext("", "", null, null, null));
        var imageService = mock(ImageAttachmentService.class);
        when(imageService.normalizeUserMessage(QUESTION, false)).thenReturn(QUESTION);
        var researchMemory = mock(ResearchMemoryService.class);
        when(researchMemory.retrieve(any())).thenReturn(ResearchMemoryService.RetrievalResult.empty());
        var ticker = mock(TickerResolutionService.class);
        when(ticker.resolveExplicitTicker(anyString())).thenReturn("");
        var relay = mock(TraceEventRelay.class);
        when(relay.live(TRACE, null)).thenReturn(events.asFlux());
        doAnswer(call -> {
            events.tryEmitNext(new TraceEventStore.StoredEvent("1-0", "{\"type\":\"stream-end\"}"));
            events.tryEmitComplete();
            return null;
        }).when(emitter).emit(TRACE, CONVERSATION, "stream-end", "");
        doAnswer(call -> { cleaned.countDown(); return null; }).when(bus).complete(TRACE);
        var executor = mock(AsyncTaskExecutor.class);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        var assembler = new ChatPromptAssembler();
        ReflectionTestUtils.setField(assembler, "promptMaxTextChars", 24000);
        service = new ChatService(mock(ConversationTitleService.class), new ObjectMapper(), traces,
                mock(RagService.class), bus, relay, emitter, coordinator, shortMemory, longMemory,
                executor, imageService, prefetch, messages, mock(RoutingDecisionObserver.class),
                researchMemory, ticker, assembler,
            mock(com.stocksage.research.ResearchTaskService.class),
            mock(com.stocksage.research.ResearchTaskObservationService.class));
        ReflectionTestUtils.setField(service, "streamHeartbeatSeconds", 20L);
    }

    @AfterEach
    void releaseCallingThreadContext() {
        ToolCallContext.unregister(TRACE);
    }

    @Test
    void backgroundSubmissionRejectionCannotChangeCompletedAnswerOrTrace() throws Exception {
        var rejected = mock(AsyncTaskExecutor.class);
        doThrow(new java.util.concurrent.RejectedExecutionException("background queue full"))
                .when(rejected).execute(any(Runnable.class));
        ReflectionTestUtils.setField(service, "backgroundTaskExecutor", rejected);
        model(Flux.just("已完成回答"));
        List<String> chunks = service.streamChat(request).collectList().block(Duration.ofSeconds(5));
        assertCleaned();
        assertThat(chunks).anyMatch(chunk -> chunk.contains("已完成回答"))
                .anyMatch(chunk -> chunk.contains("stream-end"))
                .noneMatch(chunk -> chunk.contains("\"type\":\"error\""));
        verify(messages).appendAssistantMessage(CONVERSATION, USER, "已完成回答", TRACE, "FAST", "test-model");
        verify(traces).endTrace(eq(TRACE), eq("success"), anyInt(), anyLong(), isNull());
        verify(traces, never()).endTrace(eq(TRACE), eq("error"), anyInt(), anyLong(), any());
        verify(longMemory, never()).extractAndUpdate(USER, "已完成回答", QUESTION, 91L, observedAt);
        ReflectionTestUtils.invokeMethod(service, "maybeGenerateConversationTitle", CONVERSATION, USER, QUESTION, true, "chat");
        verify(messages, never()).updateTitleIfOwned(anyLong(), anyString(), anyString());
        verify(rejected, times(2)).execute(any(Runnable.class));
    }

    @Test
    void fundamentalsSelectionAndFinalAssemblyShareOneActualUserMemorySnapshot() throws Exception {
        when(coordinator.planRecognized(any(), eq(QUESTION), eq(0))).thenReturn(new ExecutionPlan(
                PlanRoute.FUNDAMENTALS, "fundamentals", "", List.of(com.stocksage.agent.PlanAction.FUNDAMENTALS_AGENT), "", ModelTier.STANDARD));
        var selected = new Coordinator.SelectedModel(ModelTier.STANDARD, "test-standard");
        when(coordinator.selectFinalAnswerModel(ModelTier.STANDARD, false, false)).thenReturn(selected);
        var invocation = Map.<String, Object>of("kind", "model-invocation", "scope", "final-answer", "modelName", "test-standard");
        when(coordinator.finalAnswerInvocation(selected)).thenReturn(invocation);
        when(longMemory.buildPromptContext(USER)).thenReturn("original user memory", "changed user memory");
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any())).thenReturn(Flux.just("回答"));
        service.streamChat(request).collectList().block(Duration.ofSeconds(5));
        assertCleaned();
        verify(longMemory, times(1)).buildPromptContext(USER);
        var facts = org.mockito.ArgumentCaptor.forClass(com.stocksage.evolution.FundamentalsRuntimeIdentity.Request.class);
        verify(prefetch).prefetch(any(), eq(QUESTION), eq(TRACE), eq(CONVERSATION), eq(USER), eq(selected), any(), facts.capture());
        assertThat(facts.getValue().originalQuery()).isEqualTo(QUESTION);
        assertThat(facts.getValue().finalInvocation()).isEqualTo(invocation);
        assertThat(facts.getValue().eligibleRouteAndModel()).isTrue();
        assertThat(facts.getValue().promptMaxChars()).isEqualTo(24000);
        assertThat(facts.getValue().memorySnapshotSha256()).isEqualTo(
                com.stocksage.evolution.FundamentalsRuntimeIdentity.memoryHash("", "original user memory", List.of()));
        verify(coordinator).streamAnswer(argThat(prompt -> prompt.stream().anyMatch(message -> message.getText().contains("original user memory"))
                        && prompt.stream().noneMatch(message -> message.getText().contains("changed user memory"))),
                eq(false), eq(ModelTier.STANDARD), eq(false), any());
    }

    @Test
    void ordinaryCompletionPublishesFullAnswerAndMemoryOnce() throws Exception {
        model(Flux.just("第一段", "第二段"));
        List<String> chunks = service.streamChat(request).collectList().block(Duration.ofSeconds(5));
        assertCleaned();
        assertThat(chunks).anyMatch(s -> s.contains("第一段")).anyMatch(s -> s.contains("第二段"))
                .anyMatch(s -> s.contains("stream-end"));
        verify(messages).appendAssistantMessage(CONVERSATION, USER, "第一段第二段", TRACE, "FAST", "test-model");
        verify(shortMemory).addMessage(CONVERSATION, "assistant", "第一段第二段");
        verify(longMemory).extractAndUpdate(USER, "第一段第二段", QUESTION, 91L, observedAt);
        verify(traces).endTrace(eq(TRACE), eq("success"), anyInt(), anyLong(), isNull());
        verify(traces, never()).endTrace(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void cancellationSavesPartialAnswerWithoutCompletionSideEffects() throws Exception {
        model(Flux.concat(Flux.just("部分回答"), Flux.never()));
        List<String> chunks = service.streamChat(request)
                .takeUntil(s -> s.contains("\"type\":\"answer\""))
                .collectList().block(Duration.ofSeconds(5));
        assertCleaned();
        assertThat(chunks).anyMatch(s -> s.contains("部分回答"));
        verify(messages).appendAssistantMessage(CONVERSATION, USER, "部分回答", TRACE, "FAST", "test-model");
        verify(shortMemory, never()).addMessage(eq(CONVERSATION), eq("assistant"), anyString());
        verify(longMemory, never()).extractAndUpdate(anyString(), anyString(), anyString(), anyLong(), any());
        verify(traces).endTrace(eq(TRACE), eq("cancelled"), eq(0), anyLong());
        verify(traces, never()).endTrace(anyString(), anyString(), anyInt(), anyLong(), any());
    }

    @Test
    void cancellationBeforeFirstTokenDoesNotCreateAnEmptyAssistantMessage() throws Exception {
        CountDownLatch modelSubscribed = new CountDownLatch(1);
        model(Flux.<String>never().doOnSubscribe(ignored -> modelSubscribed.countDown()));
        var subscription = service.streamChat(request).subscribe();
        try {
            assertThat(modelSubscribed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            subscription.dispose();
        }
        assertCleaned();
        verify(messages, never()).appendAssistantMessage(anyLong(), anyString(), anyString(), anyString(), any(), any());
        verify(traces).endTrace(eq(TRACE), eq("cancelled"), eq(0), anyLong());
    }

    @Test
    void wrappedProviderCapacityFailureDuringStreamingHasAnExplicitErrorWithoutSuccessfulPublication() throws Exception {
        model(Flux.error(new IllegalStateException("private wrapper",
                new com.stocksage.exception.ResearchCapacityExceededException("provider-admission", null))));
        assertCapacityRejectedStream();
    }

    @Test
    void wrappedProviderCapacityFailureDuringPreparationHasTheSameExplicitError() throws Exception {
        when(prefetch.prefetch(any(), anyString(), anyString(), anyLong(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("private wrapper",
                        new com.stocksage.exception.ResearchCapacityExceededException("provider-admission", null)));
        assertCapacityRejectedStream();
        verify(coordinator, never()).streamAnswer(anyList(), anyBoolean(), any(), anyBoolean(), any());
    }

    private void assertCapacityRejectedStream() throws Exception {
        List<String> chunks = service.streamChat(request).collectList().block(Duration.ofSeconds(5));
        assertCleaned();
        assertThat(chunks).anyMatch(chunk -> chunk.contains("\"type\":\"error\"") && chunk.contains("执行容量不足")
                        && chunk.contains("请稍后重试"))
                .noneMatch(chunk -> chunk.contains("private wrapper") || chunk.contains("服务暂时出错"));
        verify(messages, never()).appendAssistantMessage(anyLong(), anyString(), anyString(), anyString(), any(), any());
        verify(traces).endTrace(eq(TRACE), eq("error"), eq(0), anyLong());
        verify(traces, never()).endTrace(eq(TRACE), eq("success"), anyInt(), anyLong(), any());
    }

    @Test
    void modelErrorEmitsErrorAndDoesNotPersistPartialAnswer() throws Exception {
        model(Flux.concat(Flux.just("未完成"), Flux.error(new IllegalStateException("model unavailable"))));
        List<String> chunks = service.streamChat(request).collectList().block(Duration.ofSeconds(5));
        assertCleaned();
        assertThat(chunks).anyMatch(s -> s.contains("\"type\":\"error\""));
        verify(messages, never()).appendAssistantMessage(anyLong(), anyString(), anyString(), anyString(), any(), any());
        verify(traces).endTrace(eq(TRACE), eq("error"), eq(0), anyLong());
        verify(traces, never()).endTrace(anyString(), anyString(), anyInt(), anyLong(), any());
    }

    @Test
    void preparationFailureBeforeRelayReadyStillClosesTheWholeStream() throws Exception {
        when(prefetch.prefetch(any(), anyString(), anyString(), anyLong(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("prefetch unavailable"));
        List<String> chunks = service.streamChat(request).collectList().block(Duration.ofSeconds(5));
        assertCleaned();
        assertThat(chunks).anyMatch(s -> s.contains("\"type\":\"error\""));
        verify(coordinator, never()).streamAnswer(anyList(), anyBoolean(), any(), anyBoolean(), any());
        verify(traces).endTrace(eq(TRACE), eq("error"), eq(0), anyLong());
    }

    @Test
    void directAnswerSkipsModelAndRetainsItsExplicitOutcome() throws Exception {
        when(prefetch.prefetch(any(), anyString(), anyString(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(new ToolPrefetchService.PreparedToolContext("", "规则答复", null, TRACE, "BLOCKED"));
        List<String> chunks = service.streamChat(request).collectList().block(Duration.ofSeconds(5));
        assertCleaned();
        assertThat(chunks).anyMatch(s -> s.contains("规则答复"));
        verify(messages).appendAssistantMessage(CONVERSATION, USER, "规则答复", TRACE, null, null);
        verify(shortMemory).addMessage(CONVERSATION, "assistant", "规则答复");
        verify(longMemory).extractAndUpdate(USER, "规则答复", QUESTION, 91L, observedAt);
        verify(coordinator, never()).streamAnswer(anyList(), anyBoolean(), any(), anyBoolean(), any());
        verify(traces).endTrace(eq(TRACE), eq("success"), eq(0), anyLong(), eq("BLOCKED"));
    }

    @Test
    void backgroundAcceptancePersistsReceiptButDisconnectLeavesTaskTraceOpen() throws Exception {
        when(prefetch.prefetch(any(), anyString(), anyString(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(new ToolPrefetchService.PreparedToolContext("", "研究已受理", 123L, TRACE, null));
        CountDownLatch accepted = new CountDownLatch(1);
        doAnswer(call -> {
            var step = call.getArgument(1, com.stocksage.agent.AgentStep.class);
            if ("Accepted DEEP research task for background execution.".equals(step.getThought())) {
                accepted.countDown();
            }
            return null;
        }).when(traces).addStep(eq(TRACE), any());
        var subscription = service.streamChat(request).subscribe();
        try {
            assertThat(accepted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            subscription.dispose();
        }
        verify(messages).appendAssistantMessage(CONVERSATION, USER, "研究已受理", TRACE, null, null);
        verify(shortMemory).addMessage(CONVERSATION, "assistant", "研究已受理");
        verify(longMemory, never()).extractAndUpdate(anyString(), anyString(), anyString(), anyLong(), any());
        verify(traces, never()).endTrace(anyString(), anyString(), anyInt(), anyLong());
        verify(traces, never()).endTrace(anyString(), anyString(), anyInt(), anyLong(), any());
        verifyNoInteractions(emitter, bus);
    }

    private void model(Flux<String> tokens) {
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.FAST), eq(false), any())).thenReturn(tokens);
    }

    private void assertCleaned() throws InterruptedException {
        assertThat(cleaned.await(5, TimeUnit.SECONDS)).isTrue();
        verify(emitter).emit(TRACE, CONVERSATION, "stream-end", "");
        verify(bus).complete(TRACE);
    }
}
