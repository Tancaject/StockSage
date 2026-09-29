package com.stocksage.repository;

import com.stocksage.model.entity.AgentTrace;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.stocksage.repository.AgentTraceRepositoryTest$SqlCapture"
})
class AgentTraceRepositoryTest {
    @Autowired AgentTraceRepository repository;
    @Autowired EntityManager entityManager;

    @Test
    void readsBoundedRecentSummariesWithoutLoadingTracePayloadOrCountingRows() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (int i = 0; i < 3; i++) {
            AgentTrace trace = new AgentTrace();
            trace.setTraceId("sample-" + i);
            trace.setUserId("user-1");
            trace.setStatus(i == 2 ? "running" : "success");
            trace.setCreatedAt(now.plusSeconds(i));
            trace.setDurationMs(i == 2 ? null : 100L + i);
            trace.setSteps("[]");
            trace.setUserQuery("private question");
            repository.save(trace);
        }
        repository.flush();
        entityManager.clear();
        SqlCapture.statements.clear();

        var samples = repository.findLatencySamples(PageRequest.of(0, 2,
                Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).getStatus()).isEqualTo("running");
        assertThat(samples.get(0).getDurationMs()).isNull();
        assertThat(samples.get(0).getCreatedAt()).isEqualTo(now.plusSeconds(2));
        assertThat(samples.get(1).getDurationMs()).isEqualTo(101L);
        assertThat(SqlCapture.statements).singleElement().satisfies(sql -> {
            assertThat(sql).contains("duration_ms", "created_at", "status");
            assertThat(sql).doesNotContain("steps", "user_query", "count(");
        });
    }

    public static class SqlCapture implements StatementInspector {
        static final List<String> statements = new ArrayList<>();

        @Override
        public String inspect(String sql) {
            statements.add(sql);
            return sql;
        }
    }
}
