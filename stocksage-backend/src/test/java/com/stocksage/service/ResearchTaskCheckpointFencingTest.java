package com.stocksage.service;

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
    void terminalResubmissionInvalidatesPreviousAttemptCheckpointInTheSameServiceTransaction() {
        ResearchTask task = taskRepository.saveAndFlush(runningTask("owner-a"));
        service.saveDebateRound(task.getId(), "owner-a", state("old-attempt"), 2, 3);

        ResearchTask completed = taskRepository.findById(task.getId()).orElseThrow();
        completed.setStatus(ResearchTask.Status.SUCCEEDED);
        completed.setStage(ResearchTask.Stage.COMPLETE);
        completed.setLeaseToken(null);
        taskRepository.saveAndFlush(completed);

        ResearchTaskService taskService = new ResearchTaskService(
                taskRepository,
                checkpointRepository,
                new ResearchTaskLeaseService(java.util.Optional.empty(), 30_000),
                objectMapper
        );
        String payload = taskService.buildSubmissionPayload("AAPL", "new query", "trace-new", 21L);

        ResearchTaskService.TaskReset reset = taskService.resetForResubmission(completed, payload);

        assertThat(reset.reset()).isTrue();
        assertThat(checkpointRepository.findByTaskId(task.getId())).isEmpty();
        ResearchTask persisted = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ResearchTask.Status.PENDING);
        assertThat(persisted.getConversationId()).isEqualTo(21L);
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
