package com.stocksage.research;

import com.stocksage.service.ToolPrefetchService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 研究任务队列适配器。
 *
 * <p>{@link ToolPrefetchService} 入队 taskId，{@link ResearchTaskWorker} 用 consumer group 消费、ACK、
 * reclaim 长时间 pending 记录；超过尝试上限的任务写入独立 DLQ。Redis 异常统一包装为
 * {@link QueueUnavailableException}，由提交或 worker 路径决定降级。</p>
 */
@Slf4j
@Service
public class ResearchTaskQueue {

    /** Redis Stream 操作门面。 */
    private final StreamOperations<String, String, String> streamOperations;
    /** 主研究任务 Stream 键。 */
    private final String stream;
    /** worker consumer group 名称。 */
    private final String group;
    /** 达到终止条件的任务记录 Stream 键。 */
    private final String dlqStream;
    /** 每次 XREADGROUP 阻塞等待时长。 */
    private final long pollBlockMs;
    /** PEL 记录达到该空闲时长后可被其他 consumer reclaim。 */
    private final long claimMinIdleMs;

    /**
     * 创建队列适配器并收敛阻塞/claim 时间下限。
     *
     * @param redisTemplate Redis 客户端
     * @param stream 主 Stream 键
     * @param group consumer group 名称
     * @param dlqStream DLQ Stream 键
     * @param pollBlockMs poll 阻塞毫秒数
     * @param claimMinIdleMs stale pending 判定毫秒数
     */
    public ResearchTaskQueue(
            StringRedisTemplate redisTemplate,
            @Value("${stocksage.research-task.queue.stream:stream:research-tasks}") String stream,
            @Value("${stocksage.research-task.queue.group:research-workers}") String group,
            @Value("${stocksage.research-task.queue.dlq-stream:stream:research-tasks-dlq}") String dlqStream,
            @Value("${stocksage.research-task.queue.poll-block-ms:2000}") long pollBlockMs,
            @Value("${stocksage.research-task.queue.claim-min-idle-ms:1800000}") long claimMinIdleMs
    ) {
        this.streamOperations = redisTemplate.opsForStream();
        this.stream = stream;
        this.group = group;
        this.dlqStream = dlqStream;
        this.pollBlockMs = Math.max(1, pollBlockMs);
        this.claimMinIdleMs = Math.max(1, claimMinIdleMs);
    }

    /**
     * 把任务 ID 追加到主 Stream。
     *
     * @param taskId 已落库研究任务 ID
     */
    public void enqueue(Long taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId must not be null");
        }
        execute("enqueue research task", () ->
                streamOperations.add(stream, Map.of("taskId", taskId.toString())));
    }

    /**
     * 把不可再重试的任务和原因追加到 DLQ。
     *
     * @param taskId 任务 ID
     * @param reason 终止原因
     */
    public void enqueueToDlq(Long taskId, String reason) {
        execute("enqueue research task DLQ record", () ->
                streamOperations.add(dlqStream, Map.of(
                        "taskId", String.valueOf(taskId),
                        "reason", reason == null ? "" : reason)));
    }

    /** 创建 consumer group；已存在的 BUSYGROUP 被视为成功。 */
    public void ensureConsumerGroup() {
        try {
            streamOperations.createGroup(stream, ReadOffset.from("0-0"), group);
        } catch (RuntimeException error) {
            if (!containsMessage(error, "BUSYGROUP")) {
                throw unavailable("create research task consumer group", error);
            }
        }
    }

    /**
     * 阻塞读取该 consumer 尚未消费的新记录。
     *
     * @param consumerName 当前 worker consumer 名
     * @return 至多一条 Stream 记录
     */
    public List<MapRecord<String, String, String>> poll(String consumerName) {
        try {
            List<MapRecord<String, String, String>> records = streamOperations.read(
                    Consumer.from(group, consumerName),
                    StreamReadOptions.empty()
                            .count(1)
                            .block(Duration.ofMillis(pollBlockMs)),
                    StreamOffset.create(stream, ReadOffset.lastConsumed()));
            return records == null ? List.of() : records;
        } catch (RuntimeException error) {
            throw unavailable("poll research task queue", error);
        }
    }

    /**
     * ACK 并删除已完成的 Stream 记录。
     *
     * @param record 已成功完成或终态任务记录
     */
    public void ack(MapRecord<String, String, String> record) {
        if (record == null) {
            return;
        }
        execute("ack and delete research task queue record", () -> {
            streamOperations.acknowledge(stream, group, record.getId());
            streamOperations.delete(stream, record.getId());
        });
    }

    /**
     * 从 PEL 中认领超过最小空闲时长的记录。
     *
     * @param consumerName 接手 stale 记录的 consumer
     * @param limit 单次扫描上限
     * @return 已转移 ownership 的记录
     */
    public List<MapRecord<String, String, String>> claimStale(String consumerName, int limit) {
        try {
            PendingMessages pending = streamOperations.pending(
                    stream, group, Range.unbounded(), Math.max(1, limit));
            if (pending == null || pending.isEmpty()) {
                return List.of();
            }
            RecordId[] staleIds = pending.stream()
                    .filter(this::isStale)
                    .map(PendingMessage::getId)
                    .toArray(RecordId[]::new);
            if (staleIds.length == 0) {
                return List.of();
            }
            List<MapRecord<String, String, String>> records = streamOperations.claim(
                    stream, group, consumerName, Duration.ofMillis(claimMinIdleMs), staleIds);
            return records == null ? List.of() : records;
        } catch (RuntimeException error) {
            throw unavailable("claim stale research task records", error);
        }
    }

    /** @return 主 Stream 总记录数。 */
    public long queueDepth() {
        return size(stream);
    }

    /** @return DLQ Stream 总记录数。 */
    public long dlqDepth() {
        return size(dlqStream);
    }

    /** @return 当前 consumer group 的 PEL 记录数。 */
    public long pendingDepth() {
        try {
            var summary = streamOperations.pending(stream, group);
            return summary == null ? 0 : summary.getTotalPendingMessages();
        } catch (RuntimeException error) {
            throw unavailable("read research task pending depth", error);
        }
    }

    /** 判断 pending 记录是否已达到可认领空闲时间。 */
    private boolean isStale(PendingMessage message) {
        return message != null
                && message.getElapsedTimeSinceLastDelivery().toMillis() >= claimMinIdleMs;
    }

    /** 容错读取指定 Stream 长度。 */
    private long size(String key) {
        try {
            Long size = streamOperations.size(key);
            return size == null ? 0 : size;
        } catch (RuntimeException error) {
            throw unavailable("read research task queue depth", error);
        }
    }

    /** 执行 Redis 写操作并统一转换异常。 */
    private void execute(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            throw unavailable(operation, error);
        }
    }

    /** 为上层保留操作名和原始 Redis 异常。 */
    private QueueUnavailableException unavailable(String operation, RuntimeException error) {
        return new QueueUnavailableException(operation + " failed: " + error.getMessage(), error);
    }

    /** 沿异常链查找 Redis 状态标记，例如 BUSYGROUP。 */
    private boolean containsMessage(Throwable error, String marker) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(marker)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Redis Stream 操作不可用，调用方可选择内联降级或稍后重试。 */
    public static final class QueueUnavailableException extends RuntimeException {

        /**
         * @param message 失败的队列操作与任务上下文
         * @param cause Redis 客户端抛出的原始异常
         */
        public QueueUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
