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

@Service
public class TraceEventRelay {

    private static final Set<String> TERMINAL_TYPES = Set.of("task-final", "error", "stream-end");

    private final TraceEventStore store;
    private final ToolCallEventBus toolCallEventBus;
    private final ObjectMapper objectMapper;

    public TraceEventRelay(TraceEventStore store, ToolCallEventBus toolCallEventBus, ObjectMapper objectMapper) {
        this.store = store;
        this.toolCallEventBus = toolCallEventBus;
        this.objectMapper = objectMapper;
    }

    public Flux<TraceEventStore.StoredEvent> live(String traceId, String afterEntryId) {
        if (traceId == null || traceId.isBlank()) {
            return Flux.empty();
        }

        Flux<String> localFallback = toolCallEventBus.register(traceId);
        Flux<TraceEventStore.StoredEvent> redisEvents = redisEvents(traceId, afterEntryId);
        Flux<TraceEventStore.StoredEvent> localEvents = localFallback
                .map(chunk -> new TraceEventStore.StoredEvent(null, chunk));

        return Flux.merge(redisEvents, localEvents)
                .takeUntil(event -> isTerminal(event.chunkJson()));
    }

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

    private boolean isTerminal(String chunkJson) {
        try {
            JsonNode root = objectMapper.readTree(chunkJson);
            return root != null && TERMINAL_TYPES.contains(root.path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }
}
