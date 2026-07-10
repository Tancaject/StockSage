package com.stocksage.service;

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

@Slf4j
@Service
public class ResearchTaskQueue {

    private final StreamOperations<String, String, String> streamOperations;
    private final String stream;
    private final String group;
    private final String dlqStream;
    private final long pollBlockMs;
    private final long claimMinIdleMs;

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

    public void enqueue(Long taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId must not be null");
        }
        execute("enqueue research task", () ->
                streamOperations.add(stream, Map.of("taskId", taskId.toString())));
    }

    public void enqueueToDlq(Long taskId, String reason) {
        execute("enqueue research task DLQ record", () ->
                streamOperations.add(dlqStream, Map.of(
                        "taskId", String.valueOf(taskId),
                        "reason", reason == null ? "" : reason)));
    }

    public void ensureConsumerGroup() {
        try {
            streamOperations.createGroup(stream, ReadOffset.from("0-0"), group);
        } catch (RuntimeException error) {
            if (!containsMessage(error, "BUSYGROUP")) {
                throw unavailable("create research task consumer group", error);
            }
        }
    }

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

    public void ack(MapRecord<String, String, String> record) {
        if (record == null) {
            return;
        }
        execute("ack research task queue record", () ->
                streamOperations.acknowledge(stream, group, record.getId()));
    }

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

    public long queueDepth() {
        return size(stream);
    }

    public long dlqDepth() {
        return size(dlqStream);
    }

    public long pendingDepth() {
        try {
            var summary = streamOperations.pending(stream, group);
            return summary == null ? 0 : summary.getTotalPendingMessages();
        } catch (RuntimeException error) {
            throw unavailable("read research task pending depth", error);
        }
    }

    private boolean isStale(PendingMessage message) {
        return message != null
                && message.getElapsedTimeSinceLastDelivery().toMillis() >= claimMinIdleMs;
    }

    private long size(String key) {
        try {
            Long size = streamOperations.size(key);
            return size == null ? 0 : size;
        } catch (RuntimeException error) {
            throw unavailable("read research task queue depth", error);
        }
    }

    private void execute(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            throw unavailable(operation, error);
        }
    }

    private QueueUnavailableException unavailable(String operation, RuntimeException error) {
        return new QueueUnavailableException(operation + " failed: " + error.getMessage(), error);
    }

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

    public static final class QueueUnavailableException extends RuntimeException {

        public QueueUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
