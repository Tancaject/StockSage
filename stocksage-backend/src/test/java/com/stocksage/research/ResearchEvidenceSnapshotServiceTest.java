package com.stocksage.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({ResearchEvidenceSnapshotService.class, ResearchEvidenceSnapshotServiceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResearchEvidenceSnapshotServiceTest {
    @Autowired ResearchTaskRepository tasks;
    @Autowired ResearchEvidenceSnapshotService snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void migrate() {
        jdbc.execute("DROP TABLE IF EXISTS research_evidence_snapshots");
        jdbc.execute("DROP TABLE IF EXISTS research_model_invocations");
        jdbc.execute("ALTER TABLE research_tasks DROP COLUMN final_evidence_snapshot_id");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V14__research_model_invocations.sql"),
                new ClassPathResource("db/migration/V15__research_evidence_snapshots.sql")).execute(dataSource);
    }

    @Test
    void reusesExactInputAndRetainsChangedInputAfterSuccessfulCheckpointCleanup() throws Exception {
        ResearchTask task = runningTask();
        AnalysisState state = state();
        String first = snapshots.capture(task.getId(), 1, "owner-a", state);
        String original = json(first);
        assertThat(snapshots.capture(task.getId(), 1, "owner-a", state)).isEqualTo(first);
        state.setNewsReport("new evidence input");
        String second = snapshots.capture(task.getId(), 1, "owner-a", state);
        assertThat(second).isNotEqualTo(first);
        assertThat(json(first)).isEqualTo(original);
        assertThat(mapper.readTree(json(second)).path("newsReport").asText()).isEqualTo("new evidence input");
        assertThat(mapper.readTree(original).path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(mapper.readTree(original).path("dataSnapshotHash").asText()).hasSize(64);
        assertThat(mapper.readTree(original).path("contextHash").asText()).hasSize(64);
        assertThat(mapper.readTree(original).path("evidenceLedger").isObject()).isTrue();
        var storedEvidence = mapper.readTree(original).path("evidenceLedger").path("evidence").get(0);
        assertThat(storedEvidence.path("sourceRef").asText()).isEqualTo("https://example.com/news/1");
        assertThat(storedEvidence.path("payloadHash").asText()).isEqualTo("provider-payload-sha256");
        assertThat(storedEvidence.path("asOf").isNull()).isTrue();
        assertThat(storedEvidence.path("timing").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_evidence_snapshots", Integer.class))
                .isEqualTo(2);
        jdbc.update("""
                INSERT INTO research_task_checkpoints
                    (task_id, stage_completed, debate_rounds_completed, planned_rounds, payload_json)
                VALUES (?, 'DATA_PREFETCH', 0, 0, '{}')
                """, task.getId());
        jdbc.update("UPDATE research_tasks SET status = 'SUCCEEDED', lease_token = NULL WHERE id = ?", task.getId());
        jdbc.update("DELETE FROM research_task_checkpoints WHERE task_id = ?", task.getId());
        assertThat(tasks.findById(task.getId()).orElseThrow().getFinalEvidenceSnapshotId()).isEqualTo(second);
        assertThat(json(first)).isEqualTo(original);
        assertThat(json(second)).contains("new evidence input");
        assertThatThrownBy(() -> snapshots.capture(task.getId(), 1, "owner-a", state))
                .isInstanceOf(ResearchEvidenceSnapshotService.SnapshotException.class);
    }

    @Test
    void separatesAttemptsAndRejectsStaleOwnersWithoutChangingLatestPointer() {
        ResearchTask task = runningTask();
        AnalysisState state = state();
        String first = snapshots.capture(task.getId(), 1, "owner-a", state);
        jdbc.update("UPDATE research_tasks SET attempts = 2, lease_token = 'owner-b' WHERE id = ?", task.getId());
        assertThatThrownBy(() -> snapshots.capture(task.getId(), 1, "owner-a", state))
                .isInstanceOf(ResearchEvidenceSnapshotService.SnapshotException.class);
        assertThatThrownBy(() -> snapshots.capture(task.getId(), 1, "owner-b", state))
                .isInstanceOf(ResearchEvidenceSnapshotService.SnapshotException.class);
        assertThat(tasks.findById(task.getId()).orElseThrow().getFinalEvidenceSnapshotId()).isEqualTo(first);
        String second = snapshots.capture(task.getId(), 2, "owner-b", state);
        assertThat(second).isNotEqualTo(first);
        assertThat(json(first)).isEqualTo(json(second));
        assertThat(tasks.findById(task.getId()).orElseThrow().getFinalEvidenceSnapshotId()).isEqualTo(second);
    }

    @Test
    void commitsSnapshotBeforeReturningEvenWhenOuterTransactionRollsBackAndWrapsStorageErrors() {
        ResearchTask task = runningTask();
        String id = new TransactionTemplate(transactionManager).execute(transaction -> {
            String snapshot = snapshots.capture(task.getId(), 1, "owner-a", state());
            transaction.setRollbackOnly();
            return snapshot;
        });
        assertThat(json(id)).contains("original news");
        jdbc.execute("DROP TABLE research_evidence_snapshots");
        assertThatThrownBy(() -> snapshots.capture(task.getId(), 1, "owner-a", state()))
                .isInstanceOf(ResearchEvidenceSnapshotService.SnapshotException.class)
                .hasMessageContaining("证据快照操作失败");
        assertThat(tasks.findById(task.getId()).orElseThrow().getFinalEvidenceSnapshotId()).isEqualTo(id);
    }

    private String json(String id) {
        return jdbc.queryForObject("SELECT payload_json FROM research_evidence_snapshots WHERE id = ?",
                String.class, id);
    }

    private AnalysisState state() {
        AnalysisState state = new AnalysisState();
        state.setQuery("Research AAPL");
        state.setPrimaryTicker("AAPL");
        state.setFundamentalsReport("fundamentals input");
        state.setMarketReport("market input");
        state.setNewsReport("original news");
        state.setCitations(List.of("source-a"));
        state.setEvidenceLedger(new com.stocksage.evidence.EvidenceLedger(
                com.stocksage.evidence.EvidenceModels.TargetIdentity.resolved("AAPL"),
                List.of(new com.stocksage.evidence.EvidenceModels.EvidenceEnvelope(
                        "source-a", com.stocksage.evidence.EvidenceModels.EvidenceDimension.NEWS,
                        "local.news.searchNews", "AAPL", com.stocksage.evidence.EvidenceModels.EvidenceStatus.AVAILABLE,
                        "https://example.com/news/1", "news-provider", java.time.Instant.parse("2026-09-27T00:00:00Z"),
                        null, "provider-payload-sha256", true))));
        return state;
    }

    private ResearchTask runningTask() {
        ResearchTask task = new ResearchTask();
        task.setUserId("u_001");
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task.setTicker("AAPL");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setAttempts(1);
        task.setLeaseToken("owner-a");
        return tasks.saveAndFlush(task);
    }

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean InvestmentReportVersionService reportVersions(ObjectMapper mapper) {
            return new InvestmentReportVersionService(null, null, mapper, null, null, null);
        }
    }
}
