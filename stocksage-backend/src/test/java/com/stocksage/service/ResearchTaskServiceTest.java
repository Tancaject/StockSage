package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchTaskServiceTest {

    private final ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
    private final ResearchTaskLeaseService leaseService = new ResearchTaskLeaseService(Optional.empty(), 30_000);
    private final ResearchTaskService service = new ResearchTaskService(repository, leaseService, new ObjectMapper());

    @Test
    void submissionKeyIsStableAcrossQueryWhitespaceAndCase() {
        String a = service.buildSubmissionKey("u1", "AAPL", "苹果 值不值得投资？");
        String b = service.buildSubmissionKey("u1", "aapl", "  苹果   值不值得投资？ ");
        assertThat(a).isEqualTo(b).startsWith("deep-submit:");
    }

    @Test
    void submissionKeyDiffersByUserAndTicker() {
        String base = service.buildSubmissionKey("u1", "AAPL", "q");
        assertThat(service.buildSubmissionKey("u2", "AAPL", "q")).isNotEqualTo(base);
        assertThat(service.buildSubmissionKey("u1", "MSFT", "q")).isNotEqualTo(base);
    }

    @Test
    void submissionPayloadCarriesTraceContext() throws Exception {
        String payload = service.buildSubmissionPayload("AAPL", "q", "trace-1", 5L);
        JsonNode node = new ObjectMapper().readTree(payload);
        assertThat(node.path("traceId").asText()).isEqualTo("trace-1");
        assertThat(node.path("conversationId").asLong()).isEqualTo(5L);
    }

    @Test
    void countActiveTasksIncludesPendingAndRunningTasks() {
        List<ResearchTask.Status> activeStatuses = List.of(
                ResearchTask.Status.PENDING,
                ResearchTask.Status.RUNNING
        );
        when(repository.countByUserIdAndStatusIn("u1", activeStatuses)).thenReturn(2L);

        assertThat(service.countActiveTasks(" u1 ")).isEqualTo(2);
        verify(repository).countByUserIdAndStatusIn("u1", activeStatuses);
    }

    @Test
    void createIfAbsentReturnsExistingTaskWhenUniqueConstraintRaces() {
        ResearchTask existing = researchTask("rt:nvda:v1", ResearchTask.Status.PENDING, ResearchTask.Stage.CREATED, 0);
        existing.setId(42L);

        when(repository.findByIdempotencyKey("rt:nvda:v1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(repository.saveAndFlush(any(ResearchTask.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));

        ResearchTaskService.TaskCreation creation = service.createIfAbsent(
                "rt:nvda:v1",
                "u_001",
                10L,
                "nvda",
                ResearchTask.Stage.CREATED,
                "{\"ticker\":\"NVDA\"}"
        );

        assertThat(creation.created()).isFalse();
        assertThat(creation.task()).isSameAs(existing);
    }

    @Test
    void startAttemptSeparatesRunningStatusFromPipelineStage() {
        ResearchTask task = researchTask("rt:nvda:v1", ResearchTask.Status.PENDING, ResearchTask.Stage.CREATED, 0);
        when(repository.saveAndFlush(any(ResearchTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResearchTask running = service.startAttempt(task, "lease-001", ResearchTask.Stage.AGENT_DEBATE);

        assertThat(running.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(running.getStage()).isEqualTo(ResearchTask.Stage.AGENT_DEBATE);
        assertThat(running.getAttempts()).isEqualTo(1);
        assertThat(running.getLeaseToken()).isEqualTo("lease-001");
        assertThat(running.getStartedAt()).isNotNull();
        assertThat(running.getHeartbeatAt()).isNotNull();
        assertThat(running.getCompletedAt()).isNull();
    }

    @Test
    void recoverStaleRunningTasksRetriesBeforeAttemptLimitAndFailsAtLimit() {
        ResearchTask retryable = researchTask("rt:nvda:retry", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 1);
        retryable.setHeartbeatAt(LocalDateTime.now().minusMinutes(30));
        ResearchTask exhausted = researchTask("rt:nvda:failed", ResearchTask.Status.RUNNING, ResearchTask.Stage.REPORT_PERSIST, 3);
        exhausted.setHeartbeatAt(LocalDateTime.now().minusMinutes(30));

        when(repository.findByStatusAndHeartbeatAtBefore(eq(ResearchTask.Status.RUNNING), any(LocalDateTime.class)))
                .thenReturn(List.of(retryable, exhausted));
        when(repository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResearchTaskService.RecoveryResult result = service.recoverStaleRunningTasks(Duration.ofMinutes(15), 3);

        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);

        assertThat(retryable.getStatus()).isEqualTo(ResearchTask.Status.PENDING);
        assertThat(retryable.getStage()).isEqualTo(ResearchTask.Stage.CREATED);
        assertThat(retryable.getLeaseToken()).isNull();
        assertThat(retryable.getErrorMessage()).contains("stale");

        assertThat(exhausted.getStatus()).isEqualTo(ResearchTask.Status.FAILED);
        assertThat(exhausted.getStage()).isEqualTo(ResearchTask.Stage.FAILED);
        assertThat(exhausted.getErrorMessage()).contains("attempt limit");
        assertThat(exhausted.getCompletedAt()).isNotNull();
    }

    @Test
    void markStageForOwnerRejectsWhenRecoveredTaskNoLongerHasLeaseToken() {
        ResearchTask task = researchTask("rt:nvda:v1", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 1);
        task.setId(7L);
        task.setLeaseToken("old-lease");

        when(repository.advanceStageForOwner(
                eq(7L),
                eq("old-lease"),
                eq(ResearchTask.Stage.REPORT_PERSIST),
                any(LocalDateTime.class)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.markStageForOwner(
                task,
                "old-lease",
                ResearchTask.Stage.REPORT_PERSIST
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost ownership");
    }

    @Test
    void heartbeatForOwnerUpdatesRunningTaskAndReturnsFalseWhenOwnerWasCleared() {
        ResearchTask task = researchTask("rt:nvda:v1", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 1);
        task.setId(7L);
        task.setLeaseToken("lease-001");

        when(repository.heartbeatForOwner(eq(7L), eq("lease-001"), any(LocalDateTime.class)))
                .thenReturn(1);
        assertThat(service.heartbeatForOwner(task, "lease-001")).isTrue();
        verify(repository).heartbeatForOwner(eq(7L), eq("lease-001"), any(LocalDateTime.class));

        when(repository.heartbeatForOwner(eq(7L), eq("stale-lease"), any(LocalDateTime.class)))
                .thenReturn(0);
        assertThat(service.heartbeatForOwner(task, "stale-lease")).isFalse();
    }

    private ResearchTask researchTask(
            String idempotencyKey,
            ResearchTask.Status status,
            ResearchTask.Stage stage,
            int attempts
    ) {
        ResearchTask task = new ResearchTask();
        task.setUserId("u_001");
        task.setConversationId(10L);
        task.setTicker("NVDA");
        task.setIdempotencyKey(idempotencyKey);
        task.setStatus(status);
        task.setStage(stage);
        task.setAttempts(attempts);
        task.setPayloadJson("{\"ticker\":\"NVDA\"}");
        return task;
    }
}
