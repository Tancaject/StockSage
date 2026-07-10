package com.stocksage.trace;

import com.stocksage.tool.ToolCallEventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TraceEventStore {

    private static final String PAYLOAD_FIELD = "payload";

    private final StringRedisTemplate redisTemplate;
    private final ToolCallEventBus toolCallEventBus;
    private final String streamPrefix;
    private final long maxlen;
    private final long ttlSeconds;
    private final long pollBlockMs;
    private final Set<String> warnedTraces = ConcurrentHashMap.newKeySet();

    public TraceEventStore(
            Optional<StringRedisTemplate> redisTemplate,
            ToolCallEventBus toolCallEventBus,
            @Value("${stocksage.trace-events.stream-prefix:stream:trace-events:}") String streamPrefix,
            @Value("${stocksage.trace-events.maxlen:5000}") long maxlen,
            @Value("${stocksage.trace-events.ttl-seconds:3600}") long ttlSeconds,
            @Value("${stocksage.trace-events.poll-block-ms:2000}") long pollBlockMs
    ) {
        this.redisTemplate = redisTemplate.orElse(null);
        this.toolCallEventBus = toolCallEventBus;
        this.streamPrefix = streamPrefix;
        this.maxlen = Math.max(1, maxlen);
        this.ttlSeconds = Math.max(1, ttlSeconds);
        this.pollBlockMs = Math.max(1, pollBlockMs);
    }

    public void append(String traceId, String chunkJson) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        if (redisTemplate != null) {
            try {
                String key = streamKey(traceId);
                redisTemplate.opsForStream().add(
                        StreamRecords.mapBacked(Map.of(PAYLOAD_FIELD, chunkJson)).withStreamKey(key));
                redisTemplate.opsForStream().trim(key, maxlen, true);
                redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
                return;
            } catch (Exception e) {
                warnOncePerTrace(traceId, e);
            }
        }
        toolCallEventBus.emit(traceId, chunkJson);
    }

    public List<StoredEvent> replayRange(String traceId, String afterEntryId) {
        if (redisTemplate == null || traceId == null || traceId.isBlank()) {
            return List.of();
        }
        try {
            Range<String> range = afterEntryId == null || afterEntryId.isBlank()
                    ? Range.unbounded()
                    : Range.leftOpen(afterEntryId, "+");
            return toStoredEvents(redisTemplate.opsForStream().range(streamKey(traceId), range));
        } catch (Exception e) {
            warnOncePerTrace(traceId, e);
            return List.of();
        }
    }

    public List<StoredEvent> readAfter(String traceId, String afterEntryId, long blockMs) {
        if (redisTemplate == null || traceId == null || traceId.isBlank()) {
            return List.of();
        }
        String cursor = afterEntryId == null || afterEntryId.isBlank() ? "0-0" : afterEntryId;
        try {
            StreamReadOptions options = StreamReadOptions.empty()
                    .block(Duration.ofMillis(Math.max(1, blockMs)))
                    .count(64);
            return toStoredEvents(redisTemplate.opsForStream().read(
                    options,
                    StreamOffset.create(streamKey(traceId), ReadOffset.from(cursor))));
        } catch (Exception e) {
            warnOncePerTrace(traceId, e);
            return List.of();
        }
    }

    long pollBlockMs() {
        return pollBlockMs;
    }

    private List<StoredEvent> toStoredEvents(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(record -> new StoredEvent(
                        record.getId().getValue(),
                        String.valueOf(record.getValue().get(PAYLOAD_FIELD))))
                .toList();
    }

    private String streamKey(String traceId) {
        return streamPrefix + traceId;
    }

    private void warnOncePerTrace(String traceId, Exception error) {
        if (warnedTraces.add(traceId)) {
            log.warn("Trace event Redis stream unavailable for traceId={}, using local fallback: {}",
                    traceId, error.getMessage());
        }
    }

    public record StoredEvent(String entryId, String chunkJson) {
    }
}
