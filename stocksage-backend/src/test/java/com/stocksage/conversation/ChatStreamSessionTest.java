package com.stocksage.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.ChatChunk;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.ToolCallEventBus;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceEventStore;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatStreamSessionTest {
    private static final Long CONVERSATION_ID = 17L;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TraceService traces = mock(TraceService.class);
    private final TraceEventRelay relay = mock(TraceEventRelay.class);
    private final ChatStreamEmitter emitter = mock(ChatStreamEmitter.class);
    private final ToolCallEventBus bus = spy(new ToolCallEventBus());

    @BeforeEach void connectLocalRelay() {
        // Preserve the real relay's terminal-event behavior without starting Redis polling.
        when(relay.live(anyString(), isNull())).thenAnswer(invocation -> bus.register(invocation.getArgument(0))
                .map(chunk -> new TraceEventStore.StoredEvent(null, chunk))
                .takeUntil(event -> terminalEvent(event.chunkJson())));
        doAnswer(invocation -> {
            String traceId = invocation.getArgument(0);
            bus.emit(traceId, chunk(traceId, invocation.getArgument(2), invocation.getArgument(3)));
            return null;
        }).when(emitter).emit(anyString(), eq(CONVERSATION_ID), anyString(), anyString());
    }

    @Test void foregroundFinishEmitsStreamEndAndCompletesTheMergedStream() {
        ChatStreamSession session = session("foreground");
        session.relayPrepared("foreground", false);
        Sinks.Many<String> answer = Sinks.many().unicast().onBackpressureBuffer();
        try (Probe probe = new Probe(session.mergeWith(answer.asFlux().doFinally(signal -> session.finishForeground())))) {
            String token = chunk("foreground", "answer", "answer token");
            assertThat(answer.tryEmitNext(token)).isEqualTo(Sinks.EmitResult.OK);
            assertThat(probe.completed.get()).isFalse();
            assertThat(answer.tryEmitComplete()).isEqualTo(Sinks.EmitResult.OK);

            assertThat(probe.values).contains(token, chunk("foreground", "stream-end", ""));
            assertThat(probe.completed.get()).isTrue();
            assertThat(probe.failure.get()).isNull();
            verify(emitter).emit("foreground", CONVERSATION_ID, "stream-end", "");
            verify(bus).complete("foreground");
            verifyNoInteractions(traces);
        }
    }

    @Test void preparationFailureBeforeReadyReleasesTheSwitchedRelayWait() {
        ChatStreamSession session = session("preparation");
        String failureChunk = chunk("preparation", "error", "preparation failed");
        Flux<String> answer = Flux.<String>error(new IllegalStateException("prefetch failed"))
                .onErrorResume(error -> {
                    session.preparationFailed();
                    session.finishForeground();
                    return Flux.just(failureChunk);
                });
        try (Probe probe = new Probe(session.mergeWith(answer))) {
            assertThat(probe.values).contains(failureChunk, chunk("preparation", "stream-end", ""));
            assertThat(probe.completed.get()).isTrue();
            assertThat(probe.failure.get()).isNull();
            verify(relay).live("preparation", null);
            verify(bus).complete("preparation");
        }
    }

    @Test void cancellingANewTaskObserverLeavesTheTaskBusAvailableToAnotherObserver() {
        ChatStreamSession session = session("task");
        session.relayPrepared("task", true);
        Flux<String> acceptedAnswer = Flux.just(chunk("task", "answer", "task accepted"))
                .doFinally(signal -> session.finishForeground());
        try (Probe first = new Probe(session.mergeWith(acceptedAnswer));
             Probe second = new Probe(relay.live("task", null).map(TraceEventStore.StoredEvent::chunkJson))) {
            first.close();
            verifyNoInteractions(traces);
            verify(bus, never()).complete("task");
            verify(emitter, never()).emit(anyString(), eq(CONVERSATION_ID), eq("stream-end"), anyString());
            assertThat(second.completed.get()).isFalse();

            String progress = chunk("task", "thought", "worker continues");
            String taskFinal = chunk("task", "task-final", "worker report");
            bus.emit("task", progress);
            bus.emit("task", taskFinal);
            assertThat(second.values).containsExactly(progress, taskFinal);
            assertThat(second.completed.get()).isTrue();
            assertThat(second.failure.get()).isNull();
            assertThat(first.values).doesNotContain(progress, taskFinal);
            verify(bus, never()).complete("task");
        }
    }

    @Test void switchingThenCancellingClosesOnlyTheObserverTrace() {
        ChatStreamSession session = session("observer");
        try (Probe probe = new Probe(session.mergeWith(Flux.empty()))) {
            String beforeSwitch = chunk("observer", "thought", "before switch");
            bus.emit("observer", beforeSwitch);
            session.relayPrepared("task", true);
            String oldTraceEvent = chunk("observer", "thought", "after switch");
            String taskEvent = chunk("task", "thought", "worker progress");
            bus.emit("observer", oldTraceEvent);
            bus.emit("task", taskEvent);
            assertThat(probe.values).containsExactly(beforeSwitch, taskEvent).doesNotContain(oldTraceEvent);

            probe.close();
            verify(traces).endTrace(eq("observer"), eq("cancelled"), eq(0), anyLong());
            verify(traces, never()).endTrace(eq("task"), anyString(), eq(0), anyLong());
            verify(bus, never()).complete("task");
            verify(bus, never()).complete("observer");
            verify(emitter, never()).emit(anyString(), eq(CONVERSATION_ID), eq("stream-end"), anyString());
        }
    }

    @Test void resumedRelayFailureCompletesTheSseButRecordsObserverError() {
        Sinks.Many<TraceEventStore.StoredEvent> taskEvents = Sinks.many().unicast().onBackpressureBuffer();
        when(relay.live("task", null)).thenReturn(taskEvents.asFlux());
        ChatStreamSession session = session("observer");
        try (Probe probe = new Probe(session.mergeWith(Flux.empty()))) {
            session.relayPrepared("task", true);
            assertThat(taskEvents.tryEmitError(new IllegalStateException("relay unavailable"))).isEqualTo(Sinks.EmitResult.OK);

            assertThat(probe.completed.get()).isTrue();
            assertThat(probe.failure.get()).isNull();
            assertThat(probe.values).singleElement().satisfies(value -> {
                assertThat(value).contains("\"type\":\"error\"", "\"traceId\":\"task\"");
            });
            verify(traces).endTrace(eq("observer"), eq("error"), eq(0), anyLong());
            verify(traces, never()).endTrace(eq("observer"), eq("success"), eq(0), anyLong());
            verify(traces, never()).endTrace(eq("task"), anyString(), eq(0), anyLong());
            verify(bus).complete("observer");
            verify(bus, never()).complete("task");
        }
    }

    @Test void completedBackgroundDirectAnswerKeepsObservingUntilTheWorkerFinalEvent() {
        ChatStreamSession session = session("observer");
        session.relayPrepared("task", true);
        String accepted = chunk("observer", "answer", "task accepted");
        Flux<String> directAnswer = Flux.just(accepted).doFinally(signal -> session.finishForeground());
        try (Probe probe = new Probe(session.mergeWith(directAnswer))) {
            assertThat(probe.values).containsExactly(accepted);
            assertThat(probe.completed.get()).isFalse();
            assertThat(session.hasBackgroundTask()).isTrue();
            verifyNoInteractions(traces);
            verify(bus, never()).complete(anyString());
            verify(emitter, never()).emit(anyString(), eq(CONVERSATION_ID), eq("stream-end"), anyString());

            String taskFinal = chunk("task", "task-final", "worker report");
            bus.emit("task", taskFinal);
            assertThat(probe.values).containsExactly(accepted, taskFinal);
            assertThat(probe.completed.get()).isTrue();
            assertThat(probe.failure.get()).isNull();
            verify(traces).endTrace(eq("observer"), eq("success"), eq(0), anyLong());
            verify(traces, never()).endTrace(eq("task"), anyString(), eq(0), anyLong());
            verify(bus, never()).complete("task");
        }
    }

    @Test void concurrentTerminalClaimsHaveExactlyOneWinner() throws Exception {
        ChatStreamSession session = session("terminal-gate");
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> { start.await(); return session.tryRecordTerminal(); });
            var second = workers.submit(() -> { start.await(); return session.tryRecordTerminal(); });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(session.tryRecordTerminal()).isFalse();
            verifyNoInteractions(traces, emitter);
        } finally {
            workers.shutdownNow();
        }
    }

    private ChatStreamSession session(String traceId) {
        return new ChatStreamSession(traceId, CONVERSATION_ID, System.currentTimeMillis(), 3600,
                mapper, traces, relay, emitter, bus);
    }

    private String chunk(String traceId, String type, String content) {
        try {
            return mapper.writeValueAsString(ChatChunk.builder().type(type).content(content)
                    .traceId(traceId).conversationId(CONVERSATION_ID).build());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private boolean terminalEvent(String value) {
        try {
            return Set.of("task-final", "error", "stream-end").contains(mapper.readTree(value).path("type").asText());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class Probe implements AutoCloseable {
        private final List<String> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final Disposable subscription;

        private Probe(Flux<String> flux) {
            subscription = flux.subscribe(values::add, failure::set, () -> completed.set(true));
        }

        @Override public void close() { subscription.dispose(); }
    }
}
