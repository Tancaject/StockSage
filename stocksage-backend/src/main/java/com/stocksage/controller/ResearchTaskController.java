package com.stocksage.controller;

import com.stocksage.identity.RequestIdentity;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.research.ResearchTaskObservationService;
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

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台深度研究任务的状态查询与可恢复 SSE 事件接口。
 *
 * <p>任务状态以 MySQL 为准，过程事件由 {@link ResearchTaskObservationService} 衔接 Redis 回放与实时订阅。
 * 客户端断线后可带 {@code Last-Event-ID} 重连；任务已结束但终态事件缺失时，观察服务根据数据库状态
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

    private final ResearchTaskRepository researchTaskRepository;
    private final ResearchTaskObservationService observationService;
    private final RequestIdentity requestIdentity;

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
        return observationService.events(taskId, requestIdentity.currentUserId(), lastEventId);
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
