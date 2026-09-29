package com.stocksage.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import db.migration.V12__research_run_identity;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchRunIdentityMigrationTest {
    @Test
    void historicalIdsAndKeysRemainWhileSameRequestsAcrossConversationsShareAGroup() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:run_migration;MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE research_tasks (id BIGINT PRIMARY KEY,user_id VARCHAR(32),"
                        + "ticker VARCHAR(32),payload_json VARCHAR(2000),idempotency_key VARCHAR(191))");
                statement.execute("INSERT INTO research_tasks VALUES"
                        + "(10,'u1','aapl','{\"query\":\"  AAPL   Outlook \"}', 'legacy-conversation-1'),"
                        + "(20,'u1','AAPL','{\"query\":\"aapl outlook\"}', 'legacy-conversation-2'),"
                        + "(30,'u1','AAPL','{}', 'legacy-unknown-request')");
            }
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V12__research_run_identity().migrate(context);
            var service = new ResearchTaskService(null,
                    new ResearchTaskLeaseService(Optional.empty(), 30_000), new ObjectMapper(),
                    com.stocksage.config.ModelTokenBudgetProperties.disabled());
            String expected = service.buildRequestFingerprint("u1", "AAPL", "aapl outlook");
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT * FROM research_tasks ORDER BY id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isEqualTo(10L);
                assertThat(rows.getString("idempotency_key")).isEqualTo("legacy-conversation-1");
                assertThat(rows.getString("request_fingerprint")).isEqualTo(expected);
                assertThat(rows.getObject("previous_task_id")).isNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isEqualTo(20L);
                assertThat(rows.getString("idempotency_key")).isEqualTo("legacy-conversation-2");
                assertThat(rows.getString("request_fingerprint")).isEqualTo(expected);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("request_fingerprint")).isEqualTo("legacy-unknown-request");
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
