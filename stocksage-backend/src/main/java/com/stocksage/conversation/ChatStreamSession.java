package com.stocksage.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.ChatChunk;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.ToolCallContext;
import com.stocksage.tool.ToolCallEventBus;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceEventStore;
import com.stocksage.trace.TraceService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次聊天请求的传输状态。只管理事件转发、心跳和订阅者清理，不执行模型或消息持久化。
 * 后台任务的终态属于 worker；只有观察另一条任务链路的请求才可自行结束 observer trace。
 */
@Slf4j
final class ChatStreamSession {
    private final String traceId;
    private final Long conversationId;
    private final long startTime;
    private final long heartbeatSeconds;
    private final ObjectMapper objectMapper;
    private final TraceService traceService;
    private final TraceEventRelay traceEventRelay;
    private final ChatStreamEmitter chatStreamEmitter;
    private final ToolCallEventBus toolCallEventBus;
    private final AtomicBoolean terminalRecorded = new AtomicBoolean();
    private final AtomicBoolean backgroundTaskSubmitted = new AtomicBoolean();
    private final AtomicBoolean relayFailed = new AtomicBoolean();
    private final AtomicReference<String> eventTraceId;
    private final Sinks.One<String> relayTraceReady = Sinks.one();
    private final Sinks.One<Object> heartbeatStop = Sinks.one();

    ChatStreamSession(String traceId, Long conversationId, long startTime, long heartbeatSeconds,
                      ObjectMapper objectMapper, TraceService traceService, TraceEventRelay traceEventRelay,
                      ChatStreamEmitter chatStreamEmitter, ToolCallEventBus toolCallEventBus) {
        this.traceId = traceId;
        this.conversationId = conversationId;
        this.startTime = startTime;
        this.heartbeatSeconds = heartbeatSeconds;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.traceEventRelay = traceEventRelay;
        this.chatStreamEmitter = chatStreamEmitter;
        this.toolCallEventBus = toolCallEventBus;
        this.eventTraceId = new AtomicReference<>(traceId);
    }

    boolean tryRecordTerminal() {
        return terminalRecorded.compareAndSet(false, true);
    }

    boolean hasBackgroundTask() {
        return backgroundTaskSubmitted.get();
    }

    void relayPrepared(String preparedEventTraceId, boolean backgroundTask) {
        String nextTraceId = preparedEventTraceId == null || preparedEventTraceId.isBlank()
                ? traceId : preparedEventTraceId;
        eventTraceId.set(nextTraceId);
        backgroundTaskSubmitted.set(backgroundTask);
        relayTraceReady.tryEmitValue(nextTraceId);
    }

    void preparationFailed() {
        relayTraceReady.tryEmitValue(traceId);
    }

    void finishForeground() {
        if (hasBackgroundTask()) return;
        ToolCallContext.unregister(traceId);
        chatStreamEmitter.emit(traceId, conversationId, "stream-end", "");
        toolCallEventBus.complete(traceId);
        heartbeatStop.tryEmitValue(Boolean.TRUE);
    }

    Flux<String> mergeWith(Flux<String> answerStream) {
        var switchToDifferentTrace = relayTraceReady.asMono().flatMap(nextTraceId ->
                traceId.equals(nextTraceId) ? Mono.<String>never() : Mono.just(nextTraceId));
        Flux<String> initialTraceEvents = traceEventRelay.live(traceId, null)
                .map(TraceEventStore.StoredEvent::chunkJson)
                .takeUntilOther(switchToDifferentTrace);
        Flux<String> switchedTraceEvents = relayTraceReady.asMono()
                .filter(nextTraceId -> !traceId.equals(nextTraceId))
                .flatMapMany(nextTraceId -> traceEventRelay.live(nextTraceId, null)
                        .map(TraceEventStore.StoredEvent::chunkJson));
        Flux<String> toolEvents = Flux.merge(initialTraceEvents, switchedTraceEvents)
                .timeout(Duration.ofMinutes(30))
                .onErrorResume(error -> {
                    relayFailed.set(true);
                    log.warn("Trace event relay ended with an error, traceId={}, eventTraceId={}: {}",
                            traceId, eventTraceId.get(), error.getMessage());
                    return Flux.just(toJson(ChatChunk.builder()
                            .type("error")
                            .content("深度研究事件流等待超时或暂时不可用，请稍后从任务卡继续查看。")
                            .traceId(eventTraceId.get())
                            .conversationId(conversationId)
                            .build()));
                })
                .doFinally(this::finishRelay);
        // 纯模型阶段也要保持连接；只允许丢心跳，业务事件和回答仍受背压约束。
        Flux<String> heartbeat = Flux.interval(Duration.ofSeconds(heartbeatSeconds))
                .map(tick -> toJson(ChatChunk.builder()
                        .type("heartbeat").content("").traceId(traceId)
                        .conversationId(conversationId).build()))
                .takeUntilOther(heartbeatStop.asMono());
        return mergeSseStreamsWithLossyHeartbeat(toolEvents, heartbeat, answerStream);
    }

    private void finishRelay(SignalType signalType) {
        if (!hasBackgroundTask()) return;
        // 同 trace 表示新任务，由 worker 发布终态；不同 trace 只关闭当前观察请求。
        if (shouldCloseObserverTraceFromSubscriber(traceId, eventTraceId.get()) && tryRecordTerminal()) {
            String status = relayFailed.get() ? "error"
                    : signalType == SignalType.CANCEL ? "cancelled" : "success";
            traceService.endTrace(traceId, status, 0, System.currentTimeMillis() - startTime);
        }
        ToolCallContext.unregister(traceId);
        if (signalType != SignalType.CANCEL) {
            toolCallEventBus.complete(traceId);
        }
        if (signalType != SignalType.CANCEL && traceId.equals(eventTraceId.get())) {
            ToolCallContext.unregister(eventTraceId.get());
            toolCallEventBus.complete(eventTraceId.get());
        }
        heartbeatStop.tryEmitValue(Boolean.TRUE);
    }

    static boolean shouldCloseObserverTraceFromSubscriber(String requestTraceId, String taskEventTraceId) {
        return requestTraceId != null && !requestTraceId.equals(taskEventTraceId);
    }

    static Flux<String> mergeSseStreamsWithLossyHeartbeat(
            Flux<String> toolEvents, Flux<String> heartbeat, Flux<String> answerStream) {
        return Flux.merge(toolEvents, heartbeat.onBackpressureDrop(), answerStream);
    }

    String toJson(ChatChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ChatChunk", e);
            return "{\"type\":\"error\",\"content\":\"Internal serialization error\"}";
        }
    }
}
