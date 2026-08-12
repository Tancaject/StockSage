package com.stocksage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.config.RequestIdentity;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.model.dto.ChatChunk;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.service.InvestmentReportVersionService;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceEventStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 后台深度研究任务的状态查询与可恢复 SSE 事件接口。
 *
 * <p>任务状态以 MySQL 为准，过程事件存入 Redis Stream 并由 {@link TraceEventRelay} 实时转发。
 * 客户端断线后可带 {@code Last-Event-ID} 重连；任务已结束但终态事件缺失时，本类根据数据库状态
 * 合成一个 {@code task-final}，保证前端不会永久等待。</p>
 */
@RestController
@RequestMapping("/api/research-tasks")
@RequiredArgsConstructor
public class ResearchTaskController {

    /** 会话仍可恢复或继续跟踪的任务状态。 */
    private static final List<ResearchTask.Status> ACTIVE_STATUSES = List.of(
            ResearchTask.Status.PENDING,
            ResearchTask.Status.RUNNING
    );

    /** 收到这些事件后，SSE 流可以安全结束。 */
    private static final Set<String> TERMINAL_EVENT_TYPES = Set.of("task-final", "error");

    /** 查询任务归属、状态和最终报告引用。 */
    private final ResearchTaskRepository researchTaskRepository;

    /** 在终态兜底事件中读取报告摘要。 */
    private final InvestmentReportVersionService investmentReportVersionService;

    /** 按 Last-Event-ID 回放 Redis Stream 中的历史事件。 */
    private final TraceEventStore traceEventStore;

    /** 订阅仍在运行的任务事件，并衔接历史回放。 */
    private final TraceEventRelay traceEventRelay;

    /** 从登录 Session 提取任务归属用户。 */
    private final RequestIdentity requestIdentity;

    /** 解析任务载荷和判断 SSE 事件类型。 */
    private final ObjectMapper objectMapper;

    /**
     * 查询会话最近一个仍在执行的研究任务。
     *
     * @param conversationId 当前用户的会话 ID
     * @return 有活动任务时返回 200 和摘要，否则返回 204
     */
    @GetMapping("/active")
    public ResponseEntity<ActiveResearchTaskResponse> activeTask(@RequestParam Long conversationId) {
        String userId = requestIdentity.currentUserId();
        return researchTaskRepository
                .findFirstByUserIdAndConversationIdAndStatusInOrderByCreatedAtDesc(
                        userId, conversationId, ACTIVE_STATUSES)
                .map(task -> ResponseEntity.ok(ActiveResearchTaskResponse.from(task)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 建立研究任务事件流，并按需回放断线期间的事件。
     *
     * @param taskId 当前用户拥有的研究任务 ID
     * @param lastEventId 浏览器重连时提交的最后一个 Redis Stream ID
     * @return 以 Redis 事件 ID 标记的 SSE 流
     */
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> taskEvents(
            @PathVariable Long taskId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        String userId = requestIdentity.currentUserId();
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

    /**
     * 对 SSE 路由只返回 404 状态，不尝试写入不兼容的 JSON 错误体。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleTaskNotFound() {
        // SSE clients only need the status; a JSON error body is not compatible with text/event-stream.
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
    private String terminalContent(ResearchTask task, String userId) {
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

    /**
     * 前端恢复页面时所需的最小活动任务信息。
     *
     * @param taskId 研究任务 ID
     * @param status 当前生命周期状态
     * @param stage 当前执行阶段
     * @param ticker 研究标的代码
     * @param createdAt 任务创建时间
     */
    public record ActiveResearchTaskResponse(
            Long taskId,
            ResearchTask.Status status,
            ResearchTask.Stage stage,
            String ticker,
            LocalDateTime createdAt
    ) {
        /** 将完整任务实体裁剪为不会泄露载荷和租约字段的响应。 */
        private static ActiveResearchTaskResponse from(ResearchTask task) {
            return new ActiveResearchTaskResponse(
                    task.getId(),
                    task.getStatus(),
                    task.getStage(),
                    task.getTicker(),
                    task.getCreatedAt()
            );
        }
    }
}
