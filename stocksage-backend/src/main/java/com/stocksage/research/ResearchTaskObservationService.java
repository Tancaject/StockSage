package com.stocksage.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.model.dto.ChatChunk;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceEventStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 用户限定的运行观察入口；数据库决定终态，Redis 只提供过程事件。 */
@Service
@RequiredArgsConstructor
public class ResearchTaskObservationService {
    private static final Set<String> TERMINAL_EVENT_TYPES = Set.of("task-final", "error");
    private final ResearchTaskRepository researchTaskRepository;
    private final InvestmentReportVersionService investmentReportVersionService;
    private final TraceEventStore traceEventStore;
    private final TraceEventRelay traceEventRelay;
    private final ObjectMapper objectMapper;
    @Value("${stocksage.chat.stream.heartbeat-seconds:20}")
    private long heartbeatSeconds = 20;

    public Flux<ServerSentEvent<String>> events(Long taskId, String userId, String lastEventId) {
        ResearchTask task = researchTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Research task not found"));
        String traceId = extractTraceId(task.getPayloadJson());

        if (!isTerminal(task)) {
            // Relay 先补发 lastEventId 之后的记录，再订阅同一 trace 的实时事件。
            Flux<ServerSentEvent<String>> liveEvents = traceEventRelay.live(traceId, lastEventId)
                    .map(this::toServerSentEvent);
            // Redis 终态事件可能因瞬时故障缺失；轮询 MySQL，任务落终态时合成最后一条事件。
            reactor.core.publisher.Mono<ServerSentEvent<String>> databaseTerminal = Flux.interval(
                            Duration.ZERO, Duration.ofSeconds(2))
                    .publishOn(Schedulers.boundedElastic())
                    .map(ignored -> researchTaskRepository.findByIdAndUserId(taskId, userId))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::orElseThrow)
                    .filter(this::isTerminal)
                    .next()
                    .map(completed -> terminalFallback(completed, userId, traceId));
            // 任一路径先产生终态事件即关闭合并流，避免重复发送最终报告。
            return Flux.merge(liveEvents, databaseTerminal)
                    .takeUntil(this::isTerminalEvent);
        }

        // 已结束任务不再订阅实时通道，只回放断点之后的持久化事件。
        List<TraceEventStore.StoredEvent> replay = traceEventStore.replayRange(traceId, lastEventId);
        Flux<ServerSentEvent<String>> replayEvents = Flux.fromIterable(replay)
                .map(this::toServerSentEvent);
        if (replay.stream().anyMatch(this::isTerminalEvent)) {
            return replayEvents;
        }
        return replayEvents.concatWith(Flux.just(terminalFallback(task, userId, traceId)));
    }

    /** 重发只观察原运行，不创建会话、消息或新 Trace。 */
    public Flux<String> observeChat(ResearchTask task, String userId) {
        if (!userId.equals(task.getUserId())) throw new ResourceNotFoundException("Research task not found");
        String traceId = extractTraceId(task.getPayloadJson());
        Flux<String> identity = Flux.just(encode(ChatChunk.builder().type("meta").content("")
                .traceId(traceId).conversationId(task.getConversationId())
                .metadata(Map.of("taskId", task.getId(), "status", task.getStatus().name())).build()));
        Flux<String> accepted = isTerminal(task) ? Flux.empty() : Flux.just(encode(ChatChunk.builder()
                .type("answer").content("正在查看已提交的深度研究任务 #" + task.getId() + "，完成后将返回原运行结果。")
                .traceId(traceId).conversationId(task.getConversationId()).build()));
        Flux<String> replay = Flux.defer(() -> events(task.getId(), userId, null)
                .map(event -> {
                    if (event.id() == null) return event.data();
                    try {
                        ObjectNode chunk = (ObjectNode) objectMapper.readTree(event.data());
                        chunk.put("entryId", event.id());
                        return objectMapper.writeValueAsString(chunk);
                    } catch (Exception error) {
                        throw new IllegalStateException("Unable to serialize research task replay", error);
                    }
                }));
        return Flux.concat(identity, accepted, replay.publish(shared -> Flux.merge(shared,
                Flux.interval(Duration.ofSeconds(heartbeatSeconds))
                        .map(tick -> encode(ChatChunk.builder().type("heartbeat").content("")
                                .traceId(traceId).conversationId(task.getConversationId()).build()))
                        .onBackpressureDrop()
                        .takeUntilOther(shared.ignoreElements()))));
    }

    private String encode(ChatChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize research task identity", error);
        }
    }

    /**
     * 将内部存储事件转换为 Spring SSE，并保留可重连的事件 ID。
     *
     * @param event Redis Stream 中的事件记录
     * @return 可发送给浏览器的 SSE
     */
    private ServerSentEvent<String> toServerSentEvent(TraceEventStore.StoredEvent event) {
        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(event.chunkJson());
        if (event.entryId() != null && !event.entryId().isBlank()) {
            builder.id(event.entryId());
        }
        return builder.build();
    }

    /**
     * 根据数据库终态合成最终事件，弥补 Redis 终态事件缺失。
     *
     * @param task 已进入成功或失败状态的任务
     * @param userId 当前用户 ID，用于隔离报告读取
     * @param traceId 事件所属追踪 ID
     * @return 序列化后的 {@code task-final} SSE
     */
    private ServerSentEvent<String> terminalFallback(ResearchTask task, String userId, String traceId) {
        ChatChunk chunk = ChatChunk.builder()
                .type("task-final")
                .content(terminalContent(task, userId))
                .traceId(traceId)
                .conversationId(task.getConversationId())
                .build();
        try {
            return ServerSentEvent.builder(objectMapper.writeValueAsString(chunk)).build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize terminal research task event", e);
        }
    }

    /**
     * 生成终态事件正文：失败返回错误，成功优先返回已持久化报告摘要。
     *
     * @param task 终态任务
     * @param userId 当前用户 ID
     * @return 面向前端的最终状态文本
     */
    public String terminalContent(ResearchTask task, String userId) {
        if (!userId.equals(task.getUserId())) throw new ResourceNotFoundException("Research task not found");
        if (task.getStatus() == ResearchTask.Status.FAILED) {
            return hasText(task.getErrorMessage()) ? task.getErrorMessage().trim() : "深度研究任务执行失败。";
        }
        return investmentReportVersionService.findReportBrief(userId, task.getResultReportVersionId())
                .orElse("深度研究任务已完成。");
    }

    /**
     * 从任务创建时保存的 JSON 载荷读取 traceId。
     *
     * @param payloadJson 任务载荷 JSON
     * @return 规范化 traceId；载荷缺失或损坏时返回 {@code null}
     */
    private String extractTraceId(String payloadJson) {
        if (!hasText(payloadJson)) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            String traceId = payload.path("traceId").asText(null);
            return hasText(traceId) ? traceId.trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 判断任务是否已进入成功或失败终态。 */
    private boolean isTerminal(ResearchTask task) {
        return task.getStatus() == ResearchTask.Status.SUCCEEDED
                || task.getStatus() == ResearchTask.Status.FAILED;
    }

    /** 从存储事件 JSON 判断它是否会终止 SSE 流。 */
    private boolean isTerminalEvent(TraceEventStore.StoredEvent event) {
        try {
            JsonNode chunk = objectMapper.readTree(event.chunkJson());
            return chunk != null && TERMINAL_EVENT_TYPES.contains(chunk.path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 从待发送 SSE 数据判断它是否会终止合并流。 */
    private boolean isTerminalEvent(ServerSentEvent<String> event) {
        try {
            JsonNode chunk = objectMapper.readTree(event.data());
            return chunk != null && TERMINAL_EVENT_TYPES.contains(chunk.path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 判断字符串是否包含非空白字符。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
