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

@RestController
@RequestMapping("/api/research-tasks")
@RequiredArgsConstructor
public class ResearchTaskController {

    private static final List<ResearchTask.Status> ACTIVE_STATUSES = List.of(
            ResearchTask.Status.PENDING,
            ResearchTask.Status.RUNNING
    );
    private static final Set<String> TERMINAL_EVENT_TYPES = Set.of("task-final", "error");

    private final ResearchTaskRepository researchTaskRepository;
    private final InvestmentReportVersionService investmentReportVersionService;
    private final TraceEventStore traceEventStore;
    private final TraceEventRelay traceEventRelay;
    private final RequestIdentity requestIdentity;
    private final ObjectMapper objectMapper;

    @GetMapping("/active")
    public ResponseEntity<ActiveResearchTaskResponse> activeTask(@RequestParam Long conversationId) {
        String userId = requestIdentity.currentUserId();
        return researchTaskRepository
                .findFirstByUserIdAndConversationIdAndStatusInOrderByCreatedAtDesc(
                        userId, conversationId, ACTIVE_STATUSES)
                .map(task -> ResponseEntity.ok(ActiveResearchTaskResponse.from(task)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

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
            Flux<ServerSentEvent<String>> liveEvents = traceEventRelay.live(traceId, lastEventId)
                    .map(this::toServerSentEvent);
            reactor.core.publisher.Mono<ServerSentEvent<String>> databaseTerminal = Flux.interval(
                            Duration.ZERO, Duration.ofSeconds(2))
                    .publishOn(Schedulers.boundedElastic())
                    .map(ignored -> researchTaskRepository.findByIdAndUserId(taskId, userId))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::orElseThrow)
                    .filter(this::isTerminal)
                    .next()
                    .map(completed -> terminalFallback(completed, userId, traceId));
            return Flux.merge(liveEvents, databaseTerminal)
                    .takeUntil(this::isTerminalEvent);
        }

        List<TraceEventStore.StoredEvent> replay = traceEventStore.replayRange(traceId, lastEventId);
        Flux<ServerSentEvent<String>> replayEvents = Flux.fromIterable(replay)
                .map(this::toServerSentEvent);
        if (replay.stream().anyMatch(this::isTerminalEvent)) {
            return replayEvents;
        }
        return replayEvents.concatWith(Flux.just(terminalFallback(task, userId, traceId)));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleTaskNotFound() {
        // SSE clients only need the status; a JSON error body is not compatible with text/event-stream.
    }

    private ServerSentEvent<String> toServerSentEvent(TraceEventStore.StoredEvent event) {
        ServerSentEvent.Builder<String> builder = ServerSentEvent.builder(event.chunkJson());
        if (event.entryId() != null && !event.entryId().isBlank()) {
            builder.id(event.entryId());
        }
        return builder.build();
    }

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

    private String terminalContent(ResearchTask task, String userId) {
        if (task.getStatus() == ResearchTask.Status.FAILED) {
            return hasText(task.getErrorMessage()) ? task.getErrorMessage().trim() : "深度研究任务执行失败。";
        }
        return investmentReportVersionService.findReportBrief(userId, task.getResultReportVersionId())
                .orElse("深度研究任务已完成。");
    }

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

    private boolean isTerminal(ResearchTask task) {
        return task.getStatus() == ResearchTask.Status.SUCCEEDED
                || task.getStatus() == ResearchTask.Status.FAILED;
    }

    private boolean isTerminalEvent(TraceEventStore.StoredEvent event) {
        try {
            JsonNode chunk = objectMapper.readTree(event.chunkJson());
            return chunk != null && TERMINAL_EVENT_TYPES.contains(chunk.path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isTerminalEvent(ServerSentEvent<String> event) {
        try {
            JsonNode chunk = objectMapper.readTree(event.data());
            return chunk != null && TERMINAL_EVENT_TYPES.contains(chunk.path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ActiveResearchTaskResponse(
            Long taskId,
            ResearchTask.Status status,
            ResearchTask.Stage stage,
            String ticker,
            LocalDateTime createdAt
    ) {
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
