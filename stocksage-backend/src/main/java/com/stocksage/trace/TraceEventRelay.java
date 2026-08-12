package com.stocksage.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.tool.ToolCallEventBus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 把 Redis Trace 事件和进程内降级事件合并为可订阅的实时流。
 *
 * <p>上游 SSE 控制器调用 {@link #live}；本类先从 {@link TraceEventStore} 按 Last-Event-ID
 * 继续读取，再合并 {@link ToolCallEventBus} 的本地事件。遇到终态块后自动结束订阅。
 * 本地总线只适用于单实例 Redis 故障降级，不提供跨实例回放。</p>
 */
@Service
public class TraceEventRelay {

    /** 收到任一终态类型即停止 Redis 轮询和本地订阅。 */
    private static final Set<String> TERMINAL_TYPES = Set.of("task-final", "error", "stream-end");

    /** 支持跨实例回放的 Redis Stream 存储。 */
    private final TraceEventStore store;
    /** Redis 不可用时的进程内临时事件源。 */
    private final ToolCallEventBus toolCallEventBus;
    /** 只解析 ChatChunk 的 type 字段以识别终态。 */
    private final ObjectMapper objectMapper;

    public TraceEventRelay(TraceEventStore store, ToolCallEventBus toolCallEventBus, ObjectMapper objectMapper) {
        this.store = store;
        this.toolCallEventBus = toolCallEventBus;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回指定链路从游标之后开始的实时事件流。
     *
     * @param traceId 要观察的链路 ID；为空时返回空流
     * @param afterEntryId Redis Stream 游标，通常来自 SSE {@code Last-Event-ID}
     * @return 合并 Redis 和本地降级源、遇终态自动完成的 Flux
     */
    public Flux<TraceEventStore.StoredEvent> live(String traceId, String afterEntryId) {
        if (traceId == null || traceId.isBlank()) {
            return Flux.empty();
        }

        Flux<String> localFallback = toolCallEventBus.register(traceId);
        Flux<TraceEventStore.StoredEvent> redisEvents = redisEvents(traceId, afterEntryId);
        Flux<TraceEventStore.StoredEvent> localEvents = localFallback
                .map(chunk -> new TraceEventStore.StoredEvent(null, chunk));

        // 两个来源可能同时有数据；终态判定保证观察者不会无限等待。
        return Flux.merge(redisEvents, localEvents)
                .takeUntil(event -> isTerminal(event.chunkJson()));
    }

    /** 在 boundedElastic 上执行阻塞式 Redis XREAD，并持续推进游标。 */
    private Flux<TraceEventStore.StoredEvent> redisEvents(String traceId, String afterEntryId) {
        return Flux.<TraceEventStore.StoredEvent>create(sink -> {
            String cursor = afterEntryId == null || afterEntryId.isBlank() ? "0-0" : afterEntryId;
            while (!sink.isCancelled()) {
                long startedAt = System.nanoTime();
                List<TraceEventStore.StoredEvent> batch = store.readAfter(traceId, cursor, store.pollBlockMs());
                for (TraceEventStore.StoredEvent event : batch) {
                    cursor = event.entryId();
                    sink.next(event);
                    if (isTerminal(event.chunkJson())) {
                        sink.complete();
                        return;
                    }
                }
                if (batch.isEmpty() && !sink.isCancelled()) {
                    pauseUntilNextPoll(startedAt);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void pauseUntilNextPoll(long startedAt) {
        long elapsed = System.nanoTime() - startedAt;
        long remaining = TimeUnit.MILLISECONDS.toNanos(store.pollBlockMs()) - elapsed;
        if (remaining > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    /** 容错解析事件类型；损坏块按非终态处理，让后续有效终态仍可到达。 */
    private boolean isTerminal(String chunkJson) {
        try {
            JsonNode root = objectMapper.readTree(chunkJson);
            return root != null && TERMINAL_TYPES.contains(root.path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }
}
