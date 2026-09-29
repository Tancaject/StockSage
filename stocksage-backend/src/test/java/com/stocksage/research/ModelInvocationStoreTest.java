package com.stocksage.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stocksage.config.ModelPricingProperties;
import com.stocksage.config.ModelTokenBudgetProperties;
import com.stocksage.model.dto.ModelInvocationContext;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.exception.ResearchBudgetExceededException;
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
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({ModelInvocationStore.class, ModelInvocationStoreTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ModelInvocationStoreTest {
    @Autowired ResearchTaskRepository tasks;
    @Autowired ModelInvocationStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper objectMapper;
    @Autowired ModelPricingProperties pricing;
    @Autowired ModelTokenBudgetProperties tokenBudgetProperties;

    @BeforeEach
    void migrate() {
        when(tokenBudgetProperties.snapshot()).thenReturn(ModelTokenBudgetProperties.disabled().snapshot());
        jdbc.execute("DROP TABLE IF EXISTS research_model_invocations");
        jdbc.execute("DROP TABLE IF EXISTS research_evidence_snapshots");
        jdbc.execute("ALTER TABLE research_tasks DROP COLUMN IF EXISTS final_evidence_snapshot_id");
        jdbc.execute("ALTER TABLE research_tasks DROP COLUMN IF EXISTS budget_deadline_epoch_ms");
        jdbc.execute("ALTER TABLE research_tasks DROP COLUMN IF EXISTS max_model_calls");
        jdbc.execute("ALTER TABLE research_tasks DROP COLUMN IF EXISTS token_budget_json");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V14__research_model_invocations.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V15__research_evidence_snapshots.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V17__research_run_budget.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V18__research_token_budget.sql")).execute(dataSource);
    }

    @Test
    void commitsRequestBeforeReturningEvenWhenCallerRollsBack() throws Exception {
        ResearchTask task = runningTask();
        String id = new TransactionTemplate(transactionManager).execute(transaction -> {
            String invocation = store.begin(context(task, 1, "owner-a"), "bull",
                    Map.of("requestedModel", "model-a", "promptHash", "abc"));
            transaction.setRollbackOnly();
            return invocation;
        });

        var row = jdbc.queryForMap("SELECT * FROM research_model_invocations WHERE id = ?", id);
        assertThat(row.get("STATUS")).isEqualTo("RUNNING");
        assertThat(row.get("RUN_ID")).isEqualTo(task.getId());
        assertThat(row.get("ATTEMPT")).isEqualTo(1);
        assertThat(row.get("EVIDENCE_SNAPSHOT_ID")).isEqualTo("snapshot-" + task.getId());
        assertThat(row.get("RESPONSE_JSON")).isNull();
        assertThat(row.get("COMPLETED_AT")).isNull();
        String json = jdbc.queryForObject("SELECT request_json FROM research_model_invocations WHERE id = ?",
                String.class, id);
        assertThat(objectMapper.readTree(json).path("requestedModel").asText()).isEqualTo("model-a");
    }

    @Test
    void rejectsOldOwnerWrongAttemptAndTerminalRunBeforeInsertion() {
        ResearchTask task = runningTask();
        assertThatThrownBy(() -> store.begin(new ModelInvocationContext(task.getId(), 1, "owner-a",
                "trace", "other-run-snapshot", task.getBudgetDeadlineEpochMs()), "bull", Map.of()))
                .isInstanceOf(ModelInvocationStore.InvocationRejectedException.class);
        for (ModelInvocationContext context : new ModelInvocationContext[]{
                context(task, 1, "old-owner"), context(task, 2, "owner-a")}) {
            assertThatThrownBy(() -> store.begin(context, "bull", Map.of()))
                    .isInstanceOf(ModelInvocationStore.InvocationRejectedException.class);
        }
        jdbc.update("UPDATE research_tasks SET status = 'SUCCEEDED' WHERE id = ?", task.getId());
        assertThatThrownBy(() -> store.begin(context(task, 1, "owner-a"), "bull", Map.of()))
                .isInstanceOf(ModelInvocationStore.InvocationRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_model_invocations", Integer.class)).isZero();
    }

    @Test
    void lateCompletionRetainsOriginalAttemptAndFirstTerminalResultWithoutChangingTask() throws Exception {
        ResearchTask task = runningTask();
        String id = store.begin(context(task, 1, "owner-a"), "manager", Map.of("promptHash", "original"));
        jdbc.update("UPDATE research_tasks SET attempts = 2, lease_token = 'owner-b' WHERE id = ?", task.getId());
        store.finish(id, "SUCCEEDED", Map.of("actualModel", "provider-model", "usageSource", "PROVIDER",
                "inputTokens", 17, "outputTokens", 9));
        String response = jdbc.queryForObject("SELECT response_json FROM research_model_invocations WHERE id = ?",
                String.class, id);
        store.finish(id, "CANCELLED", Map.of("usageSource", "NO_DATA"));
        assertThat(jdbc.queryForObject("SELECT response_json FROM research_model_invocations WHERE id = ?",
                String.class, id)).isEqualTo(response);
        assertThat(objectMapper.readTree(response).path("inputTokens").asInt()).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT status FROM research_model_invocations WHERE id = ?", String.class, id))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT attempt FROM research_model_invocations WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
        var current = tasks.findById(task.getId()).orElseThrow();
        assertThat(current.getAttempts()).isEqualTo(2);
        assertThat(current.getLeaseToken()).isEqualTo("owner-b");
        assertThat(current.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(current.getResultReportVersionId()).isNull();
        assertThatThrownBy(() -> store.finish(id, "RUNNING", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private ResearchTask runningTask() {
        ResearchTask task = new ResearchTask();
        task.setUserId("u_001");
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task.setTicker("AAPL");
        task.setPayloadJson("{}");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(ResearchTask.Stage.AGENT_DEBATE);
        task.setAttempts(1);
        task.setLeaseToken("owner-a");
        task.setBudgetDeadlineEpochMs(System.currentTimeMillis() + 3_600_000);
        task.setMaxModelCalls(32);
        task = tasks.saveAndFlush(task);
        jdbc.update("UPDATE research_tasks SET token_budget_json = ? FORMAT JSON WHERE id = ?",
                "{\"status\":\"DISABLED\",\"basis\":\"PROVIDER_WINDOW_RESERVATION\"}", task.getId());
        jdbc.update("""
                INSERT INTO research_evidence_snapshots
                    (id,run_id,attempt,data_snapshot_hash,context_hash,payload_hash,payload_json)
                VALUES (?, ?, 1, 'data', 'context', 'payload', '{}')
                """, "snapshot-" + task.getId(), task.getId());
        return task;
    }

    @Test
    void parallelReservationsShareOneQuotaAcrossAttemptsAndExpiredOrLegacyBudgetsStop() throws Exception {
        ResearchTask task = runningTask();
        jdbc.update("UPDATE research_tasks SET max_model_calls = 1 WHERE id = ?", task.getId());
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<String> reserve = () -> {
            ready.countDown();
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start gate");
            try {
                return store.begin(context(task, 1, "owner-a"), "bull", Map.of());
            } catch (ResearchBudgetExceededException exhausted) {
                return exhausted.reason().name();
            }
        };
        try {
            var first = executor.submit(reserve);
            var second = executor.submit(reserve);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .filteredOn(result -> result.equals("MODEL_CALL_LIMIT")).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_model_invocations WHERE run_id = ?", Integer.class, task.getId())).isEqualTo(1);
        jdbc.update("UPDATE research_tasks SET attempts = 2 WHERE id = ?", task.getId());
        assertThatThrownBy(() -> store.begin(context(task, 2, "owner-a"), "manager", Map.of()))
                .isInstanceOf(ResearchBudgetExceededException.class).hasMessageContaining("MODEL_CALL_LIMIT");
        jdbc.update("UPDATE research_tasks SET budget_deadline_epoch_ms = 1 WHERE id = ?", task.getId());
        assertThatThrownBy(() -> store.begin(context(task, 2, "owner-a"), "manager", Map.of()))
                .isInstanceOf(ResearchBudgetExceededException.class).hasMessageContaining("DEADLINE");
        jdbc.update("UPDATE research_tasks SET budget_deadline_epoch_ms = NULL WHERE id = ?", task.getId());
        assertThatThrownBy(() -> store.begin(context(task, 2, "owner-a"), "manager", Map.of()))
                .isInstanceOf(ResearchBudgetExceededException.class).hasMessageContaining("BUDGET_UNAVAILABLE");
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        ResearchTask independent = runningTask();
        assertThat(store.begin(context(independent, 1, "owner-a"), "bull", Map.of())).isNotBlank();
    }

    @Test
    void usageIncludesEveryAttemptButNotOtherRunsAndKeepsPartialCostsExplicit() {
        ResearchTask task = runningTask();
        String first = store.begin(context(task, 1, "owner-a"), "bull", Map.of("requestedModel", "alias"));
        store.finish(first, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "actualModel", "provider-model",
                "inputTokens", 11, "outputTokens", 4, "totalTokens", 15));
        ResearchTask other = runningTask();
        String unrelated = store.begin(context(other, 1, "owner-a"), "bull", Map.of());
        store.finish(unrelated, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "totalTokens", 999));

        jdbc.update("UPDATE research_tasks SET attempts = 2, lease_token = 'owner-b' WHERE id = ?", task.getId());
        jdbc.update("""
                INSERT INTO research_evidence_snapshots
                    (id,run_id,attempt,data_snapshot_hash,context_hash,payload_hash,payload_json)
                VALUES ('snapshot-second', ?, 2, 'data', 'context', 'payload', '{}')
                """, task.getId());
        var second = new ModelInvocationContext(task.getId(), 2, "owner-b", "trace", "snapshot-second", task.getBudgetDeadlineEpochMs());
        String cancelled = store.begin(second, "bull", Map.of("requestedModel", "alias"));
        store.finish(cancelled, "CANCELLED", Map.of("usageSource", "PROVIDER", "actualModel", "provider-model",
                "inputTokens", 7, "outputTokens", 3, "totalTokens", 10));
        store.begin(second, "manager", Map.of()); // May be an interrupted process, not zero consumption.
        String noData = store.begin(second, "replan", Map.of());
        store.finish(noData, "FAILED", Map.of("usageSource", "NO_DATA", "totalTokens", 100));

        var usage = store.usage(task.getId(), "u_001");
        assertThat(usage.attemptsStarted()).isEqualTo(2);
        assertThat(usage.taskStatus()).isEqualTo("RUNNING");
        assertThat(usage.monetaryCostStatus()).isEqualTo("UNAVAILABLE");
        assertThat(usage.observedUsage()).isEqualTo(new ModelInvocationStore.UsageTotals(
                4, 1, 3, 18L, 7L, 25L, 2, 2, 2, "PARTIAL",
                new ModelInvocationStore.CostTotals("UNAVAILABLE", 0, 4, 4, List.of(),
                        Map.of("NO_COST_RECORD", 1, "NO_FROZEN_RATE_CARD", 3))));
        assertThat(usage.breakdown()).hasSize(4).anySatisfy(group -> {
            assertThat(group.key()).isEqualTo(new ModelInvocationStore.UsageKey(1, "bull", "alias", "provider-model"));
            assertThat(group.observedUsage().totalTokens()).isEqualTo(15L);
            assertThat(group.observedUsage().completeness()).isEqualTo("COMPLETE_RECORDED_USAGE");
        });
    }

    @Test
    void usageDistinguishesMissingAndZeroAndDoesNotRevealAnotherUsersRun() {
        ResearchTask task = runningTask();
        var empty = store.usage(task.getId(), "u_001").observedUsage();
        assertThat(empty.calls()).isZero();
        assertThat(empty.totalTokens()).isNull();
        assertThat(empty.completeness()).isEqualTo("NO_DATA");
        String id = store.begin(context(task, 1, "owner-a"), "manager", Map.of());
        store.finish(id, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "inputTokens", 0,
                "outputTokens", 0, "totalTokens", 0));
        assertThat(store.usage(task.getId(), "u_001").observedUsage())
                .isEqualTo(new ModelInvocationStore.UsageTotals(1, 0, 0, 0L, 0L, 0L, 1, 1, 1,
                        "COMPLETE_RECORDED_USAGE", new ModelInvocationStore.CostTotals("UNAVAILABLE", 0, 1, 1,
                        List.of(), Map.of("NO_FROZEN_RATE_CARD", 1))));
        for (Long runId : new Long[]{task.getId(), -1L}) {
            assertThatThrownBy(() -> store.usage(runId, "stranger"))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessage("Research task not found");
        }
    }

    private ModelInvocationContext context(ResearchTask task, int attempt, String owner) {
        return new ModelInvocationContext(task.getId(), attempt, owner, "trace-original", "snapshot-" + task.getId(), task.getBudgetDeadlineEpochMs());
    }

    @Test
    void freezesExactRateBeforeCallAndNeverRepricesOnFinishOrRead() throws Exception {
        ResearchTask task = runningTask();
        freezeCard(task);
        assertThat(store.usage(task.getId(), "u_001").providerFingerprint()).isEqualTo(pricing.providerFingerprint());
        String id = store.begin(context(task, 1, "owner-a"), "manager", Map.of("requestedModel", "alias"));
        JsonNode saved = objectMapper.readTree(jdbc.queryForObject(
                "SELECT request_json FROM research_model_invocations WHERE id = ?", String.class, id));
        assertThat(saved.path("priceSnapshot").path("version").asText()).isEqualTo("test-card");
        assertThat(saved.path("priceSnapshot").path("inputPerMillion").asText()).isEqualTo("1.234567890123456789");
        jdbc.update("UPDATE research_tasks SET model_configuration_json = '{}' WHERE id = ?", task.getId());
        store.finish(id, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "inputTokens", 2, "outputTokens", 3));
        store.finish(id, "CANCELLED", Map.of("usageSource", "PROVIDER", "inputTokens", 999, "outputTokens", 999));
        var cost = store.usage(task.getId(), "u_001").observedUsage().estimatedCost();
        assertThat(cost.status()).isEqualTo("COMPLETE_RECORDED_ESTIMATE");
        assertThat(cost.byCurrency()).containsExactly(new ModelInvocationStore.CurrencyEstimate(
                "USD", "0.000009969135780246913578"));
        assertThat(cost.estimatedCalls()).isEqualTo(1);
        // The billing estimate can have both required token fields even when totalTokens is absent.
        assertThat(store.usage(task.getId(), "u_001").observedUsage().completeness()).isEqualTo("PARTIAL");
    }

    @Test
    void preservesUnknownAndPartialPricesAndSeparatesHistoricalCurrencies() throws Exception {
        ResearchTask task = runningTask();
        freezeCard(task);
        for (String currency : List.of("USD", "EUR")) {
            String id = store.begin(context(task, 1, "owner-a"), "bull", Map.of("requestedModel", "alias"));
            // Seed a historical invocation selected under a different card; reads must use its stored currency.
            var request = objectMapper.readTree(jdbc.queryForObject(
                    "SELECT request_json FROM research_model_invocations WHERE id = ?", String.class, id));
            ((com.fasterxml.jackson.databind.node.ObjectNode) request.path("priceSnapshot")).put("currency", currency);
            jdbc.update("UPDATE research_model_invocations SET request_json = ? WHERE id = ?", request.toString(), id);
            store.finish(id, "CANCELLED", Map.of("usageSource", "PROVIDER", "inputTokens", 0, "outputTokens", 2));
        }
        String missing = store.begin(context(task, 1, "owner-a"), "bear", Map.of("requestedModel", "alias"));
        store.finish(missing, "SUCCEEDED", Map.of("usageSource", "NO_DATA"));
        String overLimit = store.begin(context(task, 1, "owner-a"), "bear", Map.of("requestedModel", "alias"));
        store.finish(overLimit, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "inputTokens", 1001, "outputTokens", 2));
        var cost = store.usage(task.getId(), "u_001").observedUsage().estimatedCost();
        assertThat(cost.status()).isEqualTo("PARTIAL_ESTIMATE");
        assertThat(cost.estimatedCalls()).isEqualTo(2);
        assertThat(cost.unpricedCalls()).isEqualTo(2);
        assertThat(cost.unconfirmedCalls()).isEqualTo(4);
        assertThat(cost.unknownReasons()).containsExactlyInAnyOrderEntriesOf(Map.of("USAGE_UNKNOWN", 1, "INPUT_LIMIT_EXCEEDED", 1));
        assertThat(cost.byCurrency()).extracting(ModelInvocationStore.CurrencyEstimate::currency).containsExactly("EUR", "USD");
        cost.byCurrency().forEach(amount -> assertThat(new BigDecimal(amount.amount())).isEqualByComparingTo("0.000005"));
    }

    private Map<String, Object> boundedRequest() {
        return Map.of("requestedModel", "alias", "extraBodyPresent", false, "toolsPresent", false, "n", 1);
    }

    private void tokenBudget(ResearchTask task, long maximum) throws Exception {
        freezeCard(task);
        var contract = new ModelTokenBudgetProperties(maximum, ModelPricingProperties.providerFingerprint(provider()),
                "test-window", "test fixture", Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2100-01-01T00:00:00Z"), Map.of("alias", new ModelTokenBudgetProperties.Limit(60, 30, 10)));
        when(tokenBudgetProperties.snapshot()).thenReturn(contract.snapshot());
        jdbc.update("UPDATE research_tasks SET token_budget_json = ? FORMAT JSON WHERE id = ?",
                objectMapper.writeValueAsString(contract.snapshot()), task.getId());
    }

    private long accounted(String id) {
        return jdbc.queryForObject("SELECT accounted_tokens FROM research_model_invocations WHERE id = ?", Long.class, id);
    }

    @Test
    void enabledFrozenRunCannotContinueAfterRestartDisablesOrChangesContract() throws Exception {
        var task = runningTask();
        tokenBudget(task, 200);
        when(tokenBudgetProperties.snapshot()).thenReturn(ModelTokenBudgetProperties.disabled().snapshot());
        assertThatThrownBy(() -> store.begin(context(task, 1, "owner-a"), "bull", boundedRequest()))
                .hasMessageContaining("BUDGET_UNAVAILABLE");
        assertThat(store.usage(task.getId(), "u_001").budget().tokenBudget().status()).isEqualTo("UNAVAILABLE");
        tokenBudget(task, 200);
        var changed = new java.util.HashMap<>(tokenBudgetProperties.snapshot());
        changed.put("version", "different-contract");
        when(tokenBudgetProperties.snapshot()).thenReturn(changed);
        assertThatThrownBy(() -> store.begin(context(task, 1, "owner-a"), "bull", boundedRequest()))
                .hasMessageContaining("BUDGET_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_model_invocations WHERE run_id = ?",
                Integer.class, task.getId())).isZero();
    }

    @Test
    void concurrentTokenReservationsShareFinalCapacityAndCannotResetOnRetry() throws Exception {
        var task = runningTask();
        tokenBudget(task, 100);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<String> reserve = () -> {
            if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start gate");
            try { return store.begin(context(task, 1, "owner-a"), "bull", boundedRequest()); }
            catch (ResearchBudgetExceededException failure) { return failure.reason().name(); }
        };
        try {
            var first = executor.submit(reserve);
            var second = executor.submit(reserve);
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .filteredOn("TOKEN_LIMIT"::equals).hasSize(1);
        } finally { start.countDown(); executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_model_invocations WHERE run_id = ?", Integer.class, task.getId()))
                .isEqualTo(1);
        assertThat(store.usage(task.getId(), "u_001").budget().tokenBudget())
                .isEqualTo(new ModelInvocationStore.TokenBudget("EXHAUSTED", 100L, 100L, 0L));
        jdbc.update("UPDATE research_tasks SET attempts = 2 WHERE id = ?", task.getId());
        jdbc.update("""
                INSERT INTO research_evidence_snapshots
                    (id,run_id,attempt,data_snapshot_hash,context_hash,payload_hash,payload_json)
                VALUES ('snapshot-token-retry', ?, 2, 'data', 'context', 'payload', '{}')
                """, task.getId());
        var retry = new ModelInvocationContext(task.getId(), 2, "owner-a", "trace-original", "snapshot-token-retry",
                task.getBudgetDeadlineEpochMs());
        assertThatThrownBy(() -> store.begin(retry, "bear", boundedRequest()))
                .isInstanceOf(ResearchBudgetExceededException.class).hasMessageContaining("TOKEN_LIMIT");
    }

    @Test
    void settlementsReleaseOnlyCompleteSuccessKeepUnknownAndNeverClampOverrun() throws Exception {
        var task = runningTask();
        tokenBudget(task, 200);
        String complete = store.begin(context(task, 1, "owner-a"), "bull", boundedRequest());
        store.finish(complete, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "inputTokens", 10,
                "outputTokens", 10, "totalTokens", 20));
        assertThat(accounted(complete)).isEqualTo(20);
        store.finish(complete, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "inputTokens", 0,
                "outputTokens", 0, "totalTokens", 0));
        assertThat(accounted(complete)).isEqualTo(20);
        String unknown = store.begin(context(task, 1, "owner-a"), "bear", boundedRequest());
        store.finish(unknown, "SUCCEEDED", Map.of("usageSource", "NO_DATA"));
        assertThat(accounted(unknown)).isEqualTo(100);
        assertThatThrownBy(() -> store.begin(context(task, 1, "owner-a"), "manager", boundedRequest()))
                .hasMessageContaining("TOKEN_LIMIT");

        var overrunTask = runningTask();
        tokenBudget(overrunTask, 100);
        String overrun = store.begin(context(overrunTask, 1, "owner-a"), "bull", boundedRequest());
        jdbc.update("UPDATE research_tasks SET attempts = 2, lease_token = 'owner-b' WHERE id = ?", overrunTask.getId());
        store.finish(overrun, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "inputTokens", 150,
                "outputTokens", 50, "totalTokens", 200));
        assertThat(accounted(overrun)).isEqualTo(200);
        assertThat(store.usage(overrunTask.getId(), "u_001").budget().tokenBudget())
                .isEqualTo(new ModelInvocationStore.TokenBudget("EXHAUSTED", 100L, 200L, 0L));
    }

    @Test
    void cancelledFailedAndInconsistentUsageKeepReservationAndMissingAccountingBlocksAdmission() throws Exception {
        var task = runningTask();
        tokenBudget(task, 600);
        assertThatThrownBy(() -> store.begin(context(task, 1, "owner-a"), "bull", Map.of("requestedModel", "alias")))
                .hasMessageContaining("BUDGET_UNAVAILABLE");
        var unsafe = new java.util.HashMap<>(boundedRequest());
        unsafe.put("n", 2);
        assertThatThrownBy(() -> store.begin(context(task, 1, "owner-a"), "bull", unsafe))
                .hasMessageContaining("BUDGET_UNAVAILABLE");
        for (String terminal : List.of("FAILED", "CANCELLED", "SUCCEEDED")) {
            String id = store.begin(context(task, 1, "owner-a"), "bull", boundedRequest());
            store.finish(id, terminal, Map.of("usageSource", "PROVIDER", "inputTokens", 5,
                    "outputTokens", 5, "totalTokens", terminal.equals("SUCCEEDED") ? 11 : 10));
            assertThat(accounted(id)).isEqualTo(100);
        }
        String partial = store.begin(context(task, 1, "owner-a"), "bull", boundedRequest());
        store.finish(partial, "CANCELLED", Map.of("usageSource", "PROVIDER", "totalTokens", 120));
        assertThat(accounted(partial)).isEqualTo(120);
        jdbc.update("UPDATE research_model_invocations SET accounted_tokens = NULL WHERE id = ?", partial);
        assertThatThrownBy(() -> store.begin(context(task, 1, "owner-a"), "bull", boundedRequest()))
                .hasMessageContaining("BUDGET_UNAVAILABLE");
        assertThat(store.usage(task.getId(), "u_001").budget().tokenBudget().status()).isEqualTo("UNAVAILABLE");
        jdbc.update("UPDATE research_tasks SET token_budget_json = NULL WHERE id = ?", task.getId());
        assertThatThrownBy(() -> store.begin(context(task, 1, "owner-a"), "bull", boundedRequest()))
                .hasMessageContaining("BUDGET_UNAVAILABLE");
    }

    @Test
    void preEvidenceGistReservesAndSettlesWithoutInventingEvidenceSnapshot() throws Exception {
        var task = runningTask();
        tokenBudget(task, 100);
        jdbc.update("DELETE FROM research_evidence_snapshots WHERE run_id = ?", task.getId());
        String inputHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("actual filing parent and child prompt".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var context = new ModelInvocationContext(task.getId(), 1, "owner-a", "trace", null,
                task.getBudgetDeadlineEpochMs(), inputHash);
        String id = store.begin(context, "contextual-gist", boundedRequest());
        assertThat(jdbc.queryForObject("SELECT evidence_snapshot_id FROM research_model_invocations WHERE id = ?", String.class, id))
                .isNull();
        assertThat(accounted(id)).isEqualTo(100);
        JsonNode request = objectMapper.readTree(jdbc.queryForObject(
                "SELECT request_json FROM research_model_invocations WHERE id = ?", String.class, id));
        assertThat(request.path("inputAttribution").path("kind").asText()).isEqualTo("PRE_EVIDENCE_INPUT");
        assertThat(request.path("inputAttribution").path("sha256").asText()).isEqualTo(inputHash);
        store.finish(id, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "inputTokens", 10,
                "outputTokens", 5, "totalTokens", 15));
        assertThat(accounted(id)).isEqualTo(15);
    }

    @Test
    void preEvidenceGistCannotBypassRoleOwnershipOrBudgetValidation() throws Exception {
        var task = runningTask();
        tokenBudget(task, 100);
        String hash = "a".repeat(64);
        var context = new ModelInvocationContext(task.getId(), 1, "owner-a", "trace", null,
                task.getBudgetDeadlineEpochMs(), hash);
        assertThatThrownBy(() -> store.begin(context, "bull", boundedRequest()))
                .isInstanceOf(ModelInvocationStore.InvocationRejectedException.class);
        assertThatThrownBy(() -> new ModelInvocationContext(task.getId(), 1, "owner-a", "trace",
                "snapshot-" + task.getId(), task.getBudgetDeadlineEpochMs(), hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelInvocationContext(task.getId(), 1, "owner-a", "trace", null,
                task.getBudgetDeadlineEpochMs(), "not-a-hash")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE research_tasks SET lease_token = 'owner-b' WHERE id = ?", task.getId());
        assertThatThrownBy(() -> store.begin(context, "contextual-gist", boundedRequest()))
                .isInstanceOf(ModelInvocationStore.InvocationRejectedException.class);
        jdbc.update("UPDATE research_tasks SET lease_token = 'owner-a', budget_deadline_epoch_ms = 1 WHERE id = ?", task.getId());
        assertThatThrownBy(() -> store.begin(context, "contextual-gist", boundedRequest()))
                .hasMessageContaining("DEADLINE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_model_invocations WHERE run_id = ?", Integer.class, task.getId()))
                .isZero();
    }

    @Test
    void embeddingBatchAndChatShareQuotaAndSettleNativeBatchTotalOnce() throws Exception {
        var task = runningTask();
        embeddingBudget(task, 180);
        store.begin(context(task, 1, "owner-a"), "bull", boundedRequest());
        String batch = store.begin(embeddingContext(task), "ollama-embedding", embeddingRequest(2));
        assertThat(accounted(batch)).isEqualTo(80);
        assertThatThrownBy(() -> store.begin(embeddingContext(task), "ollama-embedding", embeddingRequest(1)))
                .hasMessageContaining("TOKEN_LIMIT");
        store.finish(batch, "SUCCEEDED", Map.of("usageSource", "PROVIDER", "inputTokens", 25,
                "outputTokens", 0, "totalTokens", 25));
        assertThat(accounted(batch)).isEqualTo(25);
        assertThat(store.usage(task.getId(), "u_001").budget().tokenBudget())
                .isEqualTo(new ModelInvocationStore.TokenBudget("ACTIVE", 180L, 125L, 55L));
        var mixedUsage = store.usage(task.getId(), "u_001");
        assertThat(mixedUsage.providerFingerprint()).isEqualTo(ModelPricingProperties.providerFingerprint(provider()));
        assertThat(mixedUsage.breakdown()).hasSize(2).anySatisfy(group ->
                assertThat(group.key()).isEqualTo(new ModelInvocationStore.UsageKey(1, "bull", "alias", null,
                        ModelPricingProperties.providerFingerprint(provider()))))
                .anySatisfy(group -> assertThat(group.key()).isEqualTo(new ModelInvocationStore.UsageKey(
                        1, "ollama-embedding", "fixture-embedding", null,
                        ModelPricingProperties.providerFingerprint(embeddingProvider()))));
        JsonNode request = objectMapper.readTree(jdbc.queryForObject(
                "SELECT request_json FROM research_model_invocations WHERE id = ?", String.class, batch));
        assertThat(request.path("inputAttribution").path("kind").asText()).isEqualTo("EMBEDDING_INPUT");
        assertThat(request.path("priceSnapshot").path("reason").asText()).isEqualTo("EMBEDDING_NOT_PRICED");
        String fallback = store.begin(embeddingContext(task), "ollama-embedding", embeddingRequest(1));
        store.finish(fallback, "FAILED", Map.of("usageSource", "NO_DATA"));
        assertThat(accounted(fallback)).isEqualTo(40);
        assertThat(store.usage(task.getId(), "u_001").budget().tokenBudget().status()).isEqualTo("EXHAUSTED");
    }

    @Test
    void embeddingProviderModelAndContextCannotBypassFrozenManifestEvenWhenQuotaDisabled() throws Exception {
        var task = runningTask();
        embeddingBudget(task, 180);
        for (String field : List.of("provider", "requestedModel", "contextTokens")) {
            var request = new java.util.HashMap<>(embeddingRequest(1));
            request.put(field, switch (field) {
                case "provider" -> provider();
                case "requestedModel" -> "other-model";
                default -> 41;
            });
            assertThatThrownBy(() -> store.begin(embeddingContext(task), "ollama-embedding", request))
                    .hasMessageContaining("BUDGET_UNAVAILABLE");
        }
        jdbc.update("UPDATE research_tasks SET token_budget_json = ? FORMAT JSON WHERE id = ?",
                objectMapper.writeValueAsString(ModelTokenBudgetProperties.disabled().snapshot()), task.getId());
        var wrongProvider = new java.util.HashMap<>(embeddingRequest(1));
        wrongProvider.put("provider", provider());
        assertThatThrownBy(() -> store.begin(embeddingContext(task), "ollama-embedding", wrongProvider))
                .hasMessageContaining("BUDGET_UNAVAILABLE");
        var disabledRequest = new java.util.HashMap<>(embeddingRequest(1));
        disabledRequest.remove("contextTokens");
        String id = store.begin(embeddingContext(task), "ollama-embedding", disabledRequest);
        assertThat(jdbc.queryForObject("SELECT reserved_tokens FROM research_model_invocations WHERE id = ?", Long.class, id))
                .isNull();
    }

    private ModelInvocationContext embeddingContext(ResearchTask task) {
        return new ModelInvocationContext(task.getId(), 1, "owner-a", "trace", null,
                task.getBudgetDeadlineEpochMs(), "b".repeat(64));
    }

    private static JsonNode embeddingProvider() {
        return new ObjectMapper().valueToTree(Map.of("scope", "API_CONSTRUCTION", "protocol", "OLLAMA_EMBED",
                "base", Map.of("host", "ollama.test"), "endpoint", Map.of("path", "/api/embed"),
                "unknownProviderRevision", true));
    }

    private Map<String, Object> embeddingRequest(int count) {
        return Map.of("requestedModel", "fixture-embedding", "invocationKind", "EMBEDDING", "inputCount", count,
                "contextTokens", 40L, "provider", embeddingProvider());
    }

    private void embeddingBudget(ResearchTask task, long maximum) throws Exception {
        tokenBudget(task, maximum);
        var from = Instant.parse("2020-01-01T00:00:00Z");
        var until = Instant.parse("2100-01-01T00:00:00Z");
        var contract = new ModelTokenBudgetProperties(maximum, ModelPricingProperties.providerFingerprint(provider()),
                "test-window", "test fixture", from, until, Map.of("alias", new ModelTokenBudgetProperties.Limit(60, 30, 10)),
                new ModelTokenBudgetProperties.Embedding(ModelPricingProperties.providerFingerprint(embeddingProvider()),
                        "fixture-embedding", 40, "operator-fixture-v1", "test fixture", from, until));
        when(tokenBudgetProperties.snapshot()).thenReturn(contract.snapshot());
        jdbc.update("UPDATE research_tasks SET token_budget_json = ? FORMAT JSON, model_configuration_json = ? WHERE id = ?",
                objectMapper.writeValueAsString(contract.snapshot()), objectMapper.writeValueAsString(Map.of(
                        "pricing", pricing.snapshot(), "chatProvider", provider(), "rag", Map.of("embedding", Map.of(
                                "providerIdentity", embeddingProvider(), "model", "fixture-embedding", "contextTokens", 40L)))), task.getId());
    }

    private void freezeCard(ResearchTask task) throws Exception {
        jdbc.update("UPDATE research_tasks SET model_configuration_json = ? WHERE id = ?",
                objectMapper.writeValueAsString(Map.of("pricing", pricing.snapshot(), "chatProvider", provider())), task.getId());
    }

    private static JsonNode provider() {
        return new ObjectMapper().valueToTree(Map.of("scope", "API_CONSTRUCTION", "base", Map.of("host", "example.test")));
    }

    @TestConfiguration
    static class Config {
        @Bean ModelTokenBudgetProperties tokenBudgetProperties() { return mock(ModelTokenBudgetProperties.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean ModelPricingProperties pricing() {
            return new ModelPricingProperties(ModelPricingProperties.providerFingerprint(provider()), "test-card", "USD",
                    "test fixture, not a provider rate", Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z"),
                    Map.of("alias", new ModelPricingProperties.Rate(new BigDecimal("1.234567890123456789"), new BigDecimal("2.50"), 1000)));
        }
    }
}
