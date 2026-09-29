package com.stocksage.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.repository.ResearchTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({ResearchTaskCheckpointService.class, ResearchTaskCheckpointFencingTest.JacksonConfig.class})
class ResearchTaskCheckpointFencingTest {

    @Autowired
    private ResearchTaskRepository taskRepository;

    @Autowired
    private ResearchTaskCheckpointRepository checkpointRepository;

    @Autowired
    private ResearchTaskCheckpointService service;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void takeoverCanShrinkLegacyPlanAndOldOwnerCannotOverwriteOrDeleteCheckpoint() {
        ResearchTask task = taskRepository.saveAndFlush(runningTask("owner-a"));
        checkpointRepository.saveAndFlush(service.toEntity(
                task.getId(),
                state("owner-a-r2"),
                ResearchTask.Stage.AGENT_DEBATE,
                2,
                5
        ));

        ResearchTask takenOver = taskRepository.findById(task.getId()).orElseThrow();
        takenOver.setLeaseToken("owner-b");
        takenOver.setAttempts(2);
        taskRepository.saveAndFlush(takenOver);
        service.saveDebateRound(task.getId(), "owner-b", state("owner-b-r2"), 2, 2);

        assertThatThrownBy(() -> service.saveDebateRound(
                task.getId(), "owner-a", state("stale-owner-r4"), 4, 4))
                .isInstanceOf(ResearchTaskCheckpointService.OwnershipLostException.class)
                .hasMessageContaining("taskId=" + task.getId());
        assertThatThrownBy(() -> service.deleteForTask(task.getId(), "owner-a"))
                .isInstanceOf(ResearchTaskCheckpointService.OwnershipLostException.class)
                .hasMessageContaining("taskId=" + task.getId());
        assertThatThrownBy(() -> service.deleteForCompletedTask(task.getId()))
                .isInstanceOf(ResearchTaskCheckpointService.CheckpointCleanupRejectedException.class)
                .hasMessageContaining("taskId=" + task.getId());

        ResearchTaskCheckpointService.CheckpointState checkpoint = service.load(task.getId()).orElseThrow();
        assertThat(checkpoint.debateRoundsCompleted()).isEqualTo(2);
        assertThat(checkpoint.plannedRounds()).isEqualTo(2);
        assertThat(checkpoint.state().getFundamentalsReport()).isEqualTo("owner-b-r2");
        assertThat(checkpointRepository.findByTaskId(task.getId())).isPresent();

        ResearchTask completed = taskRepository.findById(task.getId()).orElseThrow();
        completed.setStatus(ResearchTask.Status.SUCCEEDED);
        completed.setStage(ResearchTask.Stage.COMPLETE);
        completed.setLeaseToken(null);
        taskRepository.saveAndFlush(completed);

        service.deleteForCompletedTask(task.getId());
        assertThat(checkpointRepository.findByTaskId(task.getId())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateRoundFromCurrentOwnerCannotMoveCheckpointBackwards() {
        ResearchTask task = taskRepository.saveAndFlush(runningTask("owner-a"));
        service.saveDebateRound(task.getId(), "owner-a", state("round-2"), 2, 3);
        assertThatThrownBy(() -> service.saveDebateRound(
                task.getId(), "owner-a", state("late-round-1"), 1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale debate checkpoint");

        ResearchTaskCheckpointService.CheckpointState checkpoint = service.load(task.getId()).orElseThrow();
        assertThat(checkpoint.debateRoundsCompleted()).isEqualTo(2);
        assertThat(checkpoint.state().getFundamentalsReport()).isEqualTo("round-2");
    }

    @Test
    void intentionalRerunCreatesLinkedRunWithoutChangingHistoricalResultOrCheckpoint() {
        ResearchTaskService taskService = new ResearchTaskService(taskRepository,
                new ResearchTaskLeaseService(java.util.Optional.empty(), 30_000), objectMapper,
                com.stocksage.config.ModelTokenBudgetProperties.disabled());
        String oldPayload = taskService.buildSubmissionPayload("AAPL", "q", "trace-old", 20L);
        String oldKey = taskService.buildSubmissionKey("u_001", "AAPL", "q", 20L);
        ResearchTask original = taskService.createIfAbsent(oldKey, "u_001", 20L, "AAPL",
                ResearchTask.Stage.CREATED, oldPayload).task();
        taskService.startAttempt(original, "owner-a", ResearchTask.Stage.AGENT_DEBATE);
        service.saveDebateRound(original.getId(), "owner-a", state("old-attempt"), 2, 3);
        taskService.markSucceededForOwner(original, "owner-a", 789L);
        var retained = checkpointRepository.findByTaskId(original.getId()).orElseThrow();
        String oldSnapshot = retained.getPayloadJson();

        String newKey = taskService.buildRunSubmissionKey("u_001", "rerun-2");
        String newPayload = taskService.buildSubmissionPayload("AAPL", "q", "trace-new", 21L);
        var rerun = taskService.createIfAbsent(newKey, "u_001", 21L, "AAPL",
                ResearchTask.Stage.CREATED, newPayload);

        assertThat(rerun.created()).isTrue();
        assertThat(rerun.task().getId()).isNotEqualTo(original.getId());
        assertThat(rerun.task().getPreviousTaskId()).isEqualTo(original.getId());
        assertThat(rerun.task().getRequestFingerprint()).isEqualTo(original.getRequestFingerprint());
        assertThat(rerun.task().getAttempts()).isZero();
        assertThat(rerun.task().getPayloadJson()).isEqualTo(newPayload);
        assertThat(checkpointRepository.findByTaskId(rerun.task().getId())).isEmpty();
        assertThat(checkpointRepository.findByTaskId(original.getId()).orElseThrow().getPayloadJson())
                .isEqualTo(oldSnapshot);
        ResearchTask historical = taskRepository.findById(original.getId()).orElseThrow();
        assertThat(historical.getStatus()).isEqualTo(ResearchTask.Status.SUCCEEDED);
        assertThat(historical.getResultReportVersionId()).isEqualTo(789L);
        assertThat(historical.getPayloadJson()).isEqualTo(oldPayload);
        assertThat(taskService.createIfAbsent(oldKey, "u_001", 20L, "AAPL",
                ResearchTask.Stage.CREATED, oldPayload).task().getId()).isEqualTo(original.getId());
        assertThat(taskService.createIfAbsent(newKey, "u_001", 21L, "AAPL",
                ResearchTask.Stage.CREATED, newPayload).created()).isFalse();

        taskService.startAttempt(rerun.task(), "owner-b", ResearchTask.Stage.DATA_PREFETCH);
        assertThat(taskService.resetRunningForRetryForOwner(rerun.task(), "owner-b", "temporary failure")).isTrue();
        taskService.startAttempt(rerun.task(), "owner-c", ResearchTask.Stage.DATA_PREFETCH);
        assertThat(rerun.task().getAttempts()).isEqualTo(2);
        assertThat(taskService.findSubmission(newKey).orElseThrow().getId()).isEqualTo(rerun.task().getId());
        assertThat(rerun.task().getPreviousTaskId()).isEqualTo(original.getId());
    }

    private ResearchTask runningTask(String leaseToken) {
        ResearchTask task = new ResearchTask();
        task.setUserId("u_001");
        task.setConversationId(20L);
        task.setIdempotencyKey("deep-submit:" + leaseToken + ":" + UUID.randomUUID());
        task.setTicker("AAPL");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(ResearchTask.Stage.AGENT_DEBATE);
        task.setAttempts(1);
        task.setLeaseToken(leaseToken);
        task.setPayloadJson("{}");
        return task;
    }

    private AnalysisState state(String marker) {
        return AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .fundamentalsReport(marker)
                .build();
    }

    @TestConfiguration
    static class JacksonConfig {

        @Bean
        @Primary
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
