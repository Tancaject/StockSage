package com.stocksage.integration;

import com.stocksage.repository.UserMemoryFactRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = false)
class ResearchMemoryDecayConflictMigrationIT {

    private static final long OLDER_MEMORY_ID = 7_101L;
    private static final long NEWER_MEMORY_ID = 7_102L;
    private static final long CONFLICTING_BUY_MEMORY_ID = 7_201L;
    private static final long CONFLICTING_SELL_MEMORY_ID = 7_202L;
    private static final long REJECTED_MEMORY_ID = 7_301L;
    private static final long NEEDS_RESEARCH_MEMORY_ID = 7_302L;
    private static final long AMBIGUOUS_HORIZON_MEMORY_ID = 7_401L;
    private static final String USER_ID = "memory-v7-it";
    private static final String CONFLICT_KEY = "REPORT_RECOMMENDATION|AAPL|LONG_TERM";
    private static final String UNRESOLVED_CONFLICT_KEY = "REPORT_RECOMMENDATION|MSFT|SHORT_TERM";
    private static final String REJECTED_CONFLICT_KEY = "REPORT_RECOMMENDATION|TSLA|MEDIUM_TERM";
    private static final String AMBIGUOUS_CONFLICT_KEY =
            "REPORT_RECOMMENDATION|AMZN|UNSPECIFIED";
    private static final String PROFILE_USER_ID = "profile-v8-it";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("stocksage_memory_v7_it")
            .withUsername("stocksage")
            .withPassword("stocksage");

    @Test
    void flywayV1ThroughV8BackfillsLegacyMemoryAndProfileFacts() throws Exception {
        flyway(MigrationVersion.fromVersion("6")).migrate();

        LocalDateTime olderCutoff = LocalDateTime.of(2026, 1, 10, 9, 0);
        LocalDateTime newerCutoff = LocalDateTime.of(2026, 2, 10, 9, 0);
        LocalDateTime tiedCutoff = LocalDateTime.of(2026, 3, 10, 9, 0);
        try (Connection connection = connection()) {
            insertReport(connection, OLDER_MEMORY_ID, "AAPL", "Analyze AAPL for the long term",
                    " buy ", "DRAFT", olderCutoff);
            insertReport(connection, NEWER_MEMORY_ID, "AAPL", "Reassess AAPL long term",
                    "sell", "DRAFT", newerCutoff);
            insertLegacyMemory(connection, OLDER_MEMORY_ID, "aapl", olderCutoff);
            insertLegacyMemory(connection, NEWER_MEMORY_ID, "aapl", newerCutoff);

            insertReport(connection, CONFLICTING_BUY_MEMORY_ID, "MSFT", "MSFT short term outlook",
                    "BUY", "DRAFT", tiedCutoff);
            insertReport(connection, CONFLICTING_SELL_MEMORY_ID, "MSFT", "MSFT short term outlook",
                    "SELL", "DRAFT", tiedCutoff);
            insertLegacyMemory(connection, CONFLICTING_BUY_MEMORY_ID, "MSFT", tiedCutoff);
            insertLegacyMemory(connection, CONFLICTING_SELL_MEMORY_ID, "MSFT", tiedCutoff);

            insertReport(connection, REJECTED_MEMORY_ID, "TSLA", "TSLA medium term outlook",
                    "BUY", "REJECTED", newerCutoff);
            insertLegacyMemory(connection, REJECTED_MEMORY_ID, "TSLA", newerCutoff);
            insertReport(connection, NEEDS_RESEARCH_MEMORY_ID, "TSLA", "TSLA medium term reassessment",
                    "SELL", "NEEDS_RESEARCH", tiedCutoff);
            insertLegacyMemory(connection, NEEDS_RESEARCH_MEMORY_ID, "TSLA", tiedCutoff);

            insertReport(connection, AMBIGUOUS_HORIZON_MEMORY_ID, "AMZN",
                    "Compare AMZN short term and long term", "HOLD", "DRAFT", tiedCutoff);
            insertLegacyMemory(connection, AMBIGUOUS_HORIZON_MEMORY_ID, "AMZN", tiedCutoff);
            insertLegacyProfile(connection, tiedCutoff);
        }

        flyway(null).migrate();

        try (Connection connection = connection()) {
            assertThat(appliedVersions(connection))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");

            assertThat(profileFacts(connection, PROFILE_USER_ID))
                    .containsExactlyInAnyOrder(
                            "HOLDING|AAPL|AAPL",
                            "HOLDING|0700HK|0700.HK",
                            "RISK_PREFERENCE|risk_preference|aggressive",
                            "PROFILE_SUMMARY|profile_summary|Prefers technology leaders"
                    );
            assertThat(unconfirmedProfileFactCount(connection, PROFILE_USER_ID)).isEqualTo(4);
            assertThat(profileFacts(connection, "u_001")).isEmpty();
            assertMonotonicProfileFactWrites(connection);

            MemoryBackfill older = memoryBackfill(connection, OLDER_MEMORY_ID);
            assertThat(older.recommendation()).isEqualTo("BUY");
            assertThat(older.analysisHorizon()).isEqualTo("LONG_TERM");
            assertThat(older.conflictKey()).isEqualTo(CONFLICT_KEY);
            assertThat(older.resolutionStatus()).isEqualTo("SUPERSEDED");
            assertThat(older.supersededById()).isEqualTo(NEWER_MEMORY_ID);
            assertThat(older.resolutionReason()).isEqualTo("MIGRATED_SUPERSEDED");

            MemoryBackfill newer = memoryBackfill(connection, NEWER_MEMORY_ID);
            assertThat(newer.recommendation()).isEqualTo("SELL");
            assertThat(newer.analysisHorizon()).isEqualTo("LONG_TERM");
            assertThat(newer.conflictKey()).isEqualTo(CONFLICT_KEY);
            assertThat(newer.resolutionStatus()).isEqualTo("CURRENT");
            assertThat(newer.supersededById()).isNull();
            assertThat(newer.resolutionReason()).isEqualTo("MIGRATED_CURRENT");

            ConflictGroupBackfill group = conflictGroupBackfill(connection, CONFLICT_KEY);
            assertThat(group.winnerEntryId()).isEqualTo(NEWER_MEMORY_ID);
            assertThat(group.resolutionStatus()).isEqualTo("RESOLVED");

            MemoryBackfill conflictingBuy = memoryBackfill(connection, CONFLICTING_BUY_MEMORY_ID);
            MemoryBackfill conflictingSell = memoryBackfill(connection, CONFLICTING_SELL_MEMORY_ID);
            assertThat(conflictingBuy.conflictKey()).isEqualTo(UNRESOLVED_CONFLICT_KEY);
            assertThat(conflictingSell.conflictKey()).isEqualTo(UNRESOLVED_CONFLICT_KEY);
            assertThat(conflictingBuy.resolutionStatus()).isEqualTo("CONFLICTED");
            assertThat(conflictingSell.resolutionStatus()).isEqualTo("CONFLICTED");
            assertThat(conflictingBuy.resolutionReason()).isEqualTo("MIGRATED_DIRECTION_CONFLICT");
            assertThat(conflictingSell.resolutionReason()).isEqualTo("MIGRATED_DIRECTION_CONFLICT");
            ConflictGroupBackfill unresolvedGroup = conflictGroupBackfill(connection, UNRESOLVED_CONFLICT_KEY);
            assertThat(unresolvedGroup.winnerEntryId()).isNull();
            assertThat(unresolvedGroup.resolutionStatus()).isEqualTo("UNRESOLVED");

            MemoryBackfill rejected = memoryBackfill(connection, REJECTED_MEMORY_ID);
            assertThat(rejected.conflictKey()).isEqualTo(REJECTED_CONFLICT_KEY);
            assertThat(rejected.resolutionStatus()).isEqualTo("SUPERSEDED");
            assertThat(rejected.supersededById()).isNull();
            assertThat(rejected.resolutionReason()).isEqualTo("MIGRATED_NO_ELIGIBLE_CANDIDATE");
            MemoryBackfill needsResearch = memoryBackfill(connection, NEEDS_RESEARCH_MEMORY_ID);
            assertThat(needsResearch.conflictKey()).isEqualTo(REJECTED_CONFLICT_KEY);
            assertThat(needsResearch.resolutionStatus()).isEqualTo("SUPERSEDED");
            assertThat(needsResearch.supersededById()).isNull();
            assertThat(needsResearch.resolutionReason()).isEqualTo("MIGRATED_NO_ELIGIBLE_CANDIDATE");
            ConflictGroupBackfill rejectedGroup = conflictGroupBackfill(connection, REJECTED_CONFLICT_KEY);
            assertThat(rejectedGroup.winnerEntryId()).isNull();
            assertThat(rejectedGroup.resolutionStatus()).isEqualTo("NO_ELIGIBLE_CANDIDATE");

            MemoryBackfill ambiguous = memoryBackfill(connection, AMBIGUOUS_HORIZON_MEMORY_ID);
            assertThat(ambiguous.analysisHorizon()).isEqualTo("UNSPECIFIED");
            assertThat(ambiguous.conflictKey()).isEqualTo(AMBIGUOUS_CONFLICT_KEY);
            assertThat(ambiguous.resolutionStatus()).isEqualTo("CURRENT");
            ConflictGroupBackfill ambiguousGroup = conflictGroupBackfill(
                    connection, AMBIGUOUS_CONFLICT_KEY);
            assertThat(ambiguousGroup.winnerEntryId()).isEqualTo(AMBIGUOUS_HORIZON_MEMORY_ID);
            assertThat(ambiguousGroup.resolutionStatus()).isEqualTo("RESOLVED");
        }
    }

    private Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private void insertReport(Connection connection,
                              long id,
                              String ticker,
                              String userQuery,
                              String recommendation,
                              String reviewStatus,
                              LocalDateTime generatedAt) throws SQLException {
        String sql = """
                INSERT INTO investment_report_versions (
                    id, user_id, ticker, user_query, recommendation, report_version,
                    data_snapshot_hash, context_hash, model_tier, model_name,
                    report_json, generated_at, created_at, review_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DEEP', 'migration-it', '{}', ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, USER_ID);
            statement.setString(3, ticker);
            statement.setString(4, userQuery);
            statement.setString(5, recommendation);
            statement.setInt(6, Math.toIntExact(id));
            statement.setString(7, "snapshot-" + id);
            statement.setString(8, "context-" + id);
            statement.setTimestamp(9, Timestamp.valueOf(generatedAt));
            statement.setTimestamp(10, Timestamp.valueOf(generatedAt));
            statement.setString(11, reviewStatus);
            statement.executeUpdate();
        }
    }

    private void insertLegacyMemory(Connection connection,
                                    long id,
                                    String ticker,
                                    LocalDateTime dataCutoffAt) throws SQLException {
        String sql = """
                INSERT INTO research_memory_entries (
                    id, user_id, ticker, source_type, source_id,
                    source_citations, data_cutoff_at, snapshot_hash, content_hash,
                    memory_text, vector_status, created_at, updated_at
                ) VALUES (?, ?, ?, 'INVESTMENT_REPORT_VERSION', ?, '[]', ?, ?, ?, ?, 'INDEXED', ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp cutoff = Timestamp.valueOf(dataCutoffAt);
            statement.setLong(1, id);
            statement.setString(2, USER_ID);
            statement.setString(3, ticker);
            statement.setString(4, Long.toString(id));
            statement.setTimestamp(5, cutoff);
            statement.setString(6, "snapshot-" + id);
            statement.setString(7, "content-" + id);
            statement.setString(8, "Legacy report memory " + id);
            statement.setTimestamp(9, cutoff);
            statement.setTimestamp(10, cutoff);
            statement.executeUpdate();
        }
    }

    private void insertLegacyProfile(Connection connection, LocalDateTime updatedAt) throws SQLException {
        String sql = """
                INSERT INTO user_profiles (
                    user_id, holdings, watch_list, risk_preference, profile_summary, updated_at
                ) VALUES (?, '["AAPL", "0700.HK"]', '["NVDA"]', 'aggressive', ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PROFILE_USER_ID);
            statement.setString(2, "Prefers technology leaders");
            statement.setTimestamp(3, Timestamp.valueOf(updatedAt));
            statement.executeUpdate();
        }
    }

    private List<String> appliedVersions(Connection connection) throws SQLException {
        String sql = """
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE AND version IS NOT NULL
                ORDER BY installed_rank
                """;
        List<String> versions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                versions.add(resultSet.getString("version"));
            }
        }
        return versions;
    }

    private List<String> profileFacts(Connection connection, String userId) throws SQLException {
        String sql = """
                SELECT CONCAT(fact_type, '|', fact_key, '|', fact_value) AS fact
                FROM user_memory_facts
                WHERE user_id = ?
                ORDER BY id
                """;
        List<String> facts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    facts.add(resultSet.getString("fact"));
                }
            }
        }
        return facts;
    }

    private long unconfirmedProfileFactCount(Connection connection, String userId) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM user_memory_facts
                WHERE user_id = ? AND last_confirmed_at IS NULL
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private void assertMonotonicProfileFactWrites(Connection connection) throws Exception {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(
                new SingleConnectionDataSource(connection, true));
        LocalDateTime first = LocalDateTime.of(2026, 8, 29, 10, 0);
        jdbc.update(repositorySql("confirm", String.class, String.class, String.class,
                        String.class, LocalDateTime.class, Long.class),
                factParameters("HOLDING", "AAPL", "AAPL", first, 100L, "confirmedAt"));
        jdbc.update(repositorySql("revokeHolding", String.class, String.class, String.class,
                        LocalDateTime.class, Long.class),
                factParameters("HOLDING", "AAPL", "AAPL", first.plusMinutes(1), 101L, "revokedAt"));
        jdbc.update(repositorySql("confirm", String.class, String.class, String.class,
                        String.class, LocalDateTime.class, Long.class),
                factParameters("HOLDING", "AAPL", "AAPL", first.plusMinutes(2), 99L, "confirmedAt"));

        assertThat(jdbc.queryForMap("""
                SELECT source_message_id, revoked_at
                FROM user_memory_facts
                WHERE user_id = :userId AND fact_type = 'HOLDING' AND fact_key = 'AAPL'
                """, java.util.Map.of("userId", PROFILE_USER_ID)))
                .satisfies(state -> {
                    assertThat(state.get("source_message_id")).isEqualTo(101L);
                    assertThat(state.get("revoked_at")).isNotNull();
                });
    }

    private MapSqlParameterSource factParameters(
            String factType,
            String factKey,
            String factValue,
            LocalDateTime observedAt,
            Long sourceMessageId,
            String timeParameter
    ) {
        return new MapSqlParameterSource()
                .addValue("userId", PROFILE_USER_ID)
                .addValue("factType", factType)
                .addValue("factKey", factKey)
                .addValue("factValue", factValue)
                .addValue(timeParameter, observedAt)
                .addValue("sourceMessageId", sourceMessageId);
    }

    private String repositorySql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = UserMemoryFactRepository.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(Query.class).value();
    }

    private MemoryBackfill memoryBackfill(Connection connection, long id) throws SQLException {
        String sql = """
                SELECT recommendation, analysis_horizon, conflict_key, resolution_status,
                       superseded_by_id, resolution_reason
                FROM research_memory_entries
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                Long supersededById = resultSet.getObject("superseded_by_id", Long.class);
                return new MemoryBackfill(
                        resultSet.getString("recommendation"),
                        resultSet.getString("analysis_horizon"),
                        resultSet.getString("conflict_key"),
                        resultSet.getString("resolution_status"),
                        supersededById,
                        resultSet.getString("resolution_reason")
                );
            }
        }
    }

    private ConflictGroupBackfill conflictGroupBackfill(Connection connection, String conflictKey) throws SQLException {
        String sql = """
                SELECT winner_entry_id, resolution_status
                FROM research_memory_conflict_groups
                WHERE user_id = ? AND conflict_key = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, USER_ID);
            statement.setString(2, conflictKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return new ConflictGroupBackfill(
                        resultSet.getObject("winner_entry_id", Long.class),
                        resultSet.getString("resolution_status")
                );
            }
        }
    }

    private record MemoryBackfill(String recommendation,
                                  String analysisHorizon,
                                  String conflictKey,
                                  String resolutionStatus,
                                  Long supersededById,
                                  String resolutionReason) {
    }

    private record ConflictGroupBackfill(Long winnerEntryId, String resolutionStatus) {
    }
}
