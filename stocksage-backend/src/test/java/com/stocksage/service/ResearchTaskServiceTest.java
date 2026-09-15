package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.repository.ResearchTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchTaskServiceTest {

    private final ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
    private final ResearchTaskCheckpointRepository checkpointRepository =
            mock(ResearchTaskCheckpointRepository.class);
    private final ResearchTaskLeaseService leaseService = new ResearchTaskLeaseService(Optional.empty(), 30_000);
    private final ResearchTaskService service = new ResearchTaskService(
            repository, checkpointRepository, leaseService, new ObjectMapper());

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
    void submissionKeyDiffersByConversation() {
        assertThat(service.buildSubmissionKey("u1", "AAPL", "q", 10L))
                .isNotEqualTo(service.buildSubmissionKey("u1", "AAPL", "q", 11L));
    }

    @Test
    void submissionPayloadCarriesTraceContext() throws Exception {
        String payload = service.buildSubmissionPayload("AAPL", "q", "trace-1", 5L);
        JsonNode node = new ObjectMapper().readTree(payload);
        assertThat(node.path("traceId").asText()).isEqualTo("trace-1");
        assertThat(node.path("conversationId").asLong()).isEqualTo(5L);
    }

    @Test
    void terminalTaskResetUsesDatabaseCasAndMovesTaskToTheNewConversation() {
        ResearchTask task = researchTask(
                "rt:nvda:terminal", ResearchTask.Status.SUCCEEDED, ResearchTask.Stage.COMPLETE, 2);
        task.setId(9L);
        String payload = service.buildSubmissionPayload("NVDA", "q", "trace-2", 20L);
        when(repository.resetTerminalForResubmission(
                eq(9L), eq(20L), eq(payload), any(LocalDateTime.class)))
                .thenReturn(1);

        ResearchTaskService.TaskReset reset = service.resetForResubmission(task, payload);

        assertThat(reset.reset()).isTrue();
        assertThat(reset.task().getConversationId()).isEqualTo(20L);
        assertThat(reset.task().getStatus()).isEqualTo(ResearchTask.Status.PENDING);
        assertThat(reset.task().getAttempts()).isZero();
        verify(checkpointRepository).deleteByTaskId(9L);
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
        task.setId(7L);
        when(repository.startAttemptIfPending(
                eq(7L), eq("lease-001"), eq("AGENT_DEBATE"), any(LocalDateTime.class)))
                .thenReturn(1);

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
    void startAttemptRejectsSecondProcessWhenDatabaseFenceWasAlreadyClaimed() {
        ResearchTask task = researchTask("rt:nvda:v1", ResearchTask.Status.PENDING, ResearchTask.Stage.CREATED, 0);
        task.setId(7L);
        when(repository.startAttemptIfPending(
                eq(7L), eq("lease-b"), eq("DATA_PREFETCH"), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.startAttempt(task, "lease-b", ResearchTask.Stage.DATA_PREFETCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fencing");
    }

    @Test
    void preAttemptFailureClosesTaskOnlyWhenItIsStillPending() {
        ResearchTask task = researchTask(
                "rt:nvda:invalid", ResearchTask.Status.PENDING, ResearchTask.Stage.CREATED, 0);
        task.setId(9L);
        when(repository.failIfPending(
                eq(9L), eq("invalid payload"), any(LocalDateTime.class)))
                .thenReturn(1);

        boolean failed = service.markFailedIfPending(task, "invalid payload");

        assertThat(failed).isTrue();
        assertThat(task.getStatus()).isEqualTo(ResearchTask.Status.FAILED);
        assertThat(task.getStage()).isEqualTo(ResearchTask.Stage.FAILED);
        assertThat(task.getErrorMessage()).isEqualTo("invalid payload");
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(task.getHeartbeatAt()).isNotNull();
        assertThat(task.getLeaseToken()).isNull();
        verify(repository).failIfPending(
                eq(9L), eq("invalid payload"), any(LocalDateTime.class));
    }

    @Test
    void preAttemptFailureDoesNotMutateTaskAfterAnotherOwnerClaimedIt() {
        ResearchTask task = researchTask(
                "rt:nvda:claimed", ResearchTask.Status.PENDING, ResearchTask.Stage.CREATED, 0);
        task.setId(10L);
        when(repository.failIfPending(
                eq(10L), eq("fencing rejected"), any(LocalDateTime.class)))
                .thenReturn(0);

        boolean failed = service.markFailedIfPending(task, "fencing rejected");

        assertThat(failed).isFalse();
        assertThat(task.getStatus()).isEqualTo(ResearchTask.Status.PENDING);
        assertThat(task.getStage()).isEqualTo(ResearchTask.Stage.CREATED);
        assertThat(task.getErrorMessage()).isNull();
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void failedAttemptIsResetForRetryOnlyWhileTheLeaseStillOwnsTheRunningTask() {
        ResearchTask task = researchTask(
                "rt:nvda:retry", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 1);
        task.setId(8L);
        task.setLeaseToken("lease-001");
        task.setCompletedAt(LocalDateTime.now());
        when(repository.resetRunningForRetryForOwner(
                eq(8L), eq("lease-001"), eq("pipeline failed"), any(LocalDateTime.class)))
                .thenReturn(1);

        boolean reset = service.resetRunningForRetryForOwner(task, "lease-001", "pipeline failed");

        assertThat(reset).isTrue();
        assertThat(task.getStatus()).isEqualTo(ResearchTask.Status.PENDING);
        assertThat(task.getStage()).isEqualTo(ResearchTask.Stage.CREATED);
        assertThat(task.getAttempts()).isEqualTo(1);
        assertThat(task.getLeaseToken()).isNull();
        assertThat(task.getErrorMessage()).isEqualTo("pipeline failed");
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getHeartbeatAt()).isNotNull();
    }

    @Test
    void retryResetDoesNotMutateTaskWhenLeaseFenceRejectsTheOldOwner() {
        ResearchTask task = researchTask(
                "rt:nvda:retry", ResearchTask.Status.RUNNING, ResearchTask.Stage.REPORT_PERSIST, 2);
        task.setId(8L);
        task.setLeaseToken("new-owner");
        when(repository.resetRunningForRetryForOwner(
                eq(8L), eq("old-owner"), eq("late failure"), any(LocalDateTime.class)))
                .thenReturn(0);

        boolean reset = service.resetRunningForRetryForOwner(task, "old-owner", "late failure");

        assertThat(reset).isFalse();
        assertThat(task.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(task.getStage()).isEqualTo(ResearchTask.Stage.REPORT_PERSIST);
        assertThat(task.getAttempts()).isEqualTo(2);
        assertThat(task.getLeaseToken()).isEqualTo("new-owner");
        assertThat(task.getErrorMessage()).isNull();
    }

    @Test
    void initialPendingTaskRemainsRecoverableWithoutAnErrorMarker() {
        ResearchTask pending = researchTask("rt:pending:no-message", ResearchTask.Status.PENDING,
                ResearchTask.Stage.CREATED, 0);
        pending.setId(61L);
        when(repository.findByStatusAndUpdatedAtBefore(eq(ResearchTask.Status.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(pending));

        assertThat(service.recoverStaleRunningTasks(Duration.ofMinutes(15), 3).retriedTaskIds())
                .containsExactly(61L);
        assertThat(service.recoverStaleRunningTasks(Duration.ofMinutes(15), 3).retriedTaskIds())
                .containsExactly(61L);
        assertThat(pending.getAttempts()).isZero();
        assertThat(pending.getErrorMessage()).isNull();
    }

    @Test
    void recoverStaleRunningTasksRetriesBeforeAttemptLimitAndFailsAtLimit() {
        ResearchTask retryable = researchTask("rt:nvda:retry", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 1);
        retryable.setId(41L);
        retryable.setLeaseToken("retry-owner");
        retryable.setHeartbeatAt(LocalDateTime.now().minusMinutes(30));
        ResearchTask exhausted = researchTask("rt:nvda:failed", ResearchTask.Status.RUNNING, ResearchTask.Stage.REPORT_PERSIST, 3);
        exhausted.setId(42L);
        exhausted.setLeaseToken("failed-owner");
        exhausted.setHeartbeatAt(LocalDateTime.now().minusMinutes(30));

        when(repository.findByStatusAndHeartbeatAtBefore(eq(ResearchTask.Status.RUNNING), any(LocalDateTime.class)))
                .thenReturn(List.of(retryable, exhausted));
        when(repository.findByStatusAndErrorMessage(
                ResearchTask.Status.PENDING,
                "stale research task recovered for retry"
        )).thenReturn(List.of());
        when(repository.resetStaleRunningForRetry(
                eq(41L), eq("retry-owner"), any(LocalDateTime.class), eq(3),
                eq("stale research task recovered for retry"), any(LocalDateTime.class)))
                .thenReturn(1);
        when(repository.failStaleRunningAtAttemptLimit(
                eq(42L), eq("failed-owner"), any(LocalDateTime.class), eq(3),
                eq("stale research task exceeded attempt limit 3"), any(LocalDateTime.class)))
                .thenReturn(1);

        ResearchTaskService.RecoveryResult result = service.recoverStaleRunningTasks(Duration.ofMinutes(15), 3);

        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.retriedTaskIds()).containsExactly(41L);

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
    void recoveryDoesNotCountOrOverwriteTaskWhenOwnerCasWasRefreshed() {
        ResearchTask raced = researchTask(
                "rt:nvda:raced", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 1);
        raced.setId(43L);
        raced.setLeaseToken("observed-owner");
        raced.setHeartbeatAt(LocalDateTime.now().minusMinutes(30));
        when(repository.findByStatusAndHeartbeatAtBefore(
                eq(ResearchTask.Status.RUNNING), any(LocalDateTime.class)))
                .thenReturn(List.of(raced));
        when(repository.findByStatusAndErrorMessage(
                ResearchTask.Status.PENDING, "stale research task recovered for retry"))
                .thenReturn(List.of());
        when(repository.resetStaleRunningForRetry(
                eq(43L), eq("observed-owner"), any(LocalDateTime.class), eq(3),
                eq("stale research task recovered for retry"), any(LocalDateTime.class)))
                .thenReturn(0);

        ResearchTaskService.RecoveryResult result = service.recoverStaleRunningTasks(
                Duration.ofMinutes(15), 3);

        assertThat(result.retried()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(raced.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(raced.getStage()).isEqualTo(ResearchTask.Stage.AGENT_DEBATE);
        assertThat(raced.getLeaseToken()).isEqualTo("observed-owner");
    }

    @Test
    void recoveredPendingTaskRemainsDiscoverableForReenqueueOnNextRecoveryRound() {
        ResearchTask recoveredPending = researchTask(
                "rt:nvda:recovered",
                ResearchTask.Status.PENDING,
                ResearchTask.Stage.CREATED,
                1
        );
        recoveredPending.setId(51L);
        recoveredPending.setErrorMessage("stale research task recovered for retry");

        when(repository.findByStatusAndHeartbeatAtBefore(eq(ResearchTask.Status.RUNNING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(repository.findByStatusAndErrorMessage(
                ResearchTask.Status.PENDING,
                "stale research task recovered for retry"
        )).thenReturn(List.of(recoveredPending));

        ResearchTaskService.RecoveryResult result = service.recoverStaleRunningTasks(Duration.ofMinutes(15), 3);

        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.retriedTaskIds()).containsExactly(51L);
    }

    @Test
    void redisLeaseTakesOverOnlyThroughObservedRunningOwnerFence() {
        ResearchTask task = researchTask(
                "rt:nvda:takeover", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 1);
        task.setId(61L);
        task.setLeaseToken("owner-a");
        task.setHeartbeatAt(LocalDateTime.now().minusMinutes(1));
        task.setErrorMessage("old attempt interrupted");
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                task.getIdempotencyKey(), "owner-b", ResearchTaskLeaseService.Backend.REDIS);
        when(repository.takeOverStaleRunningAttempt(
                eq(61L), eq("owner-a"), eq("owner-b"), any(LocalDateTime.class), eq(3),
                any(LocalDateTime.class)))
                .thenReturn(1);

        boolean takenOver = service.takeOverRunningAttempt(task, lease, 3);

        assertThat(takenOver).isTrue();
        assertThat(task.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(task.getStage()).isEqualTo(ResearchTask.Stage.AGENT_DEBATE);
        assertThat(task.getAttempts()).isEqualTo(2);
        assertThat(task.getLeaseToken()).isEqualTo("owner-b");
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getHeartbeatAt()).isEqualTo(task.getStartedAt());
        assertThat(task.getErrorMessage()).isNull();
    }

    @Test
    void processFallbackLeaseCannotTakeOverRunningDatabaseOwner() {
        ResearchTask task = researchTask(
                "rt:nvda:takeover", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 1);
        task.setId(62L);
        task.setLeaseToken("owner-a");
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                task.getIdempotencyKey(), "owner-b", ResearchTaskLeaseService.Backend.PROCESS);

        assertThat(service.takeOverRunningAttempt(task, lease, 3)).isFalse();

        verify(repository, never()).takeOverStaleRunningAttempt(
                any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void attemptLimitFailureUsesStaleOwnerCasForRunningTask() {
        ResearchTask task = researchTask(
                "rt:nvda:exhausted", ResearchTask.Status.RUNNING, ResearchTask.Stage.AGENT_DEBATE, 3);
        task.setId(63L);
        task.setLeaseToken("owner-a");
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                task.getIdempotencyKey(), "owner-b", ResearchTaskLeaseService.Backend.REDIS);
        when(repository.failStaleRunningAtAttemptLimit(
                eq(63L), eq("owner-a"), any(LocalDateTime.class), eq(3), eq("attempt limit"),
                any(LocalDateTime.class)))
                .thenReturn(1);

        boolean failed = service.failStaleRunningAtAttemptLimit(task, lease, 3, "attempt limit");

        assertThat(failed).isTrue();
        assertThat(task.getStatus()).isEqualTo(ResearchTask.Status.FAILED);
        assertThat(task.getStage()).isEqualTo(ResearchTask.Stage.FAILED);
        assertThat(task.getLeaseToken()).isNull();
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    void attemptLimitFailureForPendingTaskUsesPendingCas() {
        ResearchTask task = researchTask(
                "rt:nvda:pending-limit", ResearchTask.Status.PENDING, ResearchTask.Stage.CREATED, 3);
        task.setId(64L);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                task.getIdempotencyKey(), "owner-b", ResearchTaskLeaseService.Backend.PROCESS);
        when(repository.failIfPending(
                eq(64L), eq("attempt limit"), any(LocalDateTime.class)))
                .thenReturn(1);

        assertThat(service.failStaleRunningAtAttemptLimit(task, lease, 3, "attempt limit")).isTrue();

        verify(repository).failIfPending(eq(64L), eq("attempt limit"), any(LocalDateTime.class));
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
