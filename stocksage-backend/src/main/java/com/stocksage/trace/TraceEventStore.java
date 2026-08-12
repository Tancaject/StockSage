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

/**
 * Trace 实时事件的 Redis Stream 存储与本地降级入口。
 *
 * <p>{@link com.stocksage.tool.ChatStreamEmitter} 和工具/能力 Observer 调用 {@link #append}；
 * {@link TraceEventRelay} 使用回放与阻塞读取方法支持 SSE 断线续传。Redis 不可用时写入
 * {@link ToolCallEventBus}，保证当前实例仍可展示事件，但不承诺跨实例恢复。</p>
 */
@Slf4j
@Service
public class TraceEventStore {

    /** Redis Stream 每条记录中保存 ChatChunk JSON 的字段名。 */
    private static final String PAYLOAD_FIELD = "payload";

    /** Redis 连接可选；测试或故障时允许为空。 */
    private final StringRedisTemplate redisTemplate;
    /** Redis 写失败后的进程内降级总线。 */
    private final ToolCallEventBus toolCallEventBus;
    /** 每个 traceId 对应 Stream key 的前缀。 */
    private final String streamPrefix;
    /** 每条链路最多保留的近似事件数。 */
    private final long maxlen;
    /** 链路 Stream 的过期秒数。 */
    private final long ttlSeconds;
    /** SSE 轮询单次阻塞等待时长。 */
    private final long pollBlockMs;
    /** 保证同一 traceId 的 Redis 故障警告只打印一次。 */
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

    /**
     * 追加一条 ChatChunk JSON，并刷新长度与 TTL。
     *
     * <p>Redis 写入、trim 或 expire 任一失败都会降级到本地总线，不影响主业务输出。</p>
     *
     * @param traceId 链路 ID；为空时忽略
     * @param chunkJson 已序列化的 ChatChunk
     */
    public void append(String traceId, String chunkJson) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        if (redisTemplate != null) {
            try {
                String key = streamKey(traceId);
                // XADD 后限制历史长度并续期，使 Last-Event-ID 可在有限窗口内跨实例回放。
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

    /**
     * 非阻塞读取游标之后的全部现存事件，适合建立 SSE 连接时补历史。
     *
     * @param traceId 链路 ID
     * @param afterEntryId 开区间左端；空值表示从头读取
     * @return 按 Redis Stream 顺序排列的事件；Redis 不可用时为空
     */
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

    /**
     * 使用 XREAD 阻塞读取游标之后的一批事件。
     *
     * @param traceId 链路 ID
     * @param afterEntryId 上一条已交付的 Stream entry ID
     * @param blockMs 最长阻塞毫秒数
     * @return 最多 64 条有序事件；故障或超时时为空
     */
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

    /**
     * Relay 对外传递的事件。
     *
     * @param entryId Redis Stream ID；本地降级事件没有 ID
     * @param chunkJson ChatChunk JSON
     */
    public record StoredEvent(String entryId, String chunkJson) {
    }
}
