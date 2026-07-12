package com.stocksage.integration;

import com.stocksage.StockSageApplication;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = StockSageApplication.class)
class ResearchTaskTakeoverIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("stocksage_takeover_it")
            .withUsername("stocksage")
            .withPassword("stocksage");

    @Autowired
    private ResearchTaskRepository repository;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Test
    void mysqlCasAllowsOneStaleTakeoverAndFencesTheOldOwner() {
        LocalDateTime now = LocalDateTime.now();
        ResearchTask task = runningTask(now.minusMinutes(1));
        task = repository.saveAndFlush(task);
        entityManager.clear();

        int first = repository.takeOverStaleRunningAttempt(
                task.getId(), "owner-a", "owner-b", now.minusSeconds(10), 3, now);
        int second = repository.takeOverStaleRunningAttempt(
                task.getId(), "owner-a", "owner-c", now.minusSeconds(10), 3, now.plusSeconds(1));
        int oldHeartbeat = repository.heartbeatForOwner(
                task.getId(), "owner-a", now.plusSeconds(2));
        entityManager.flush();
        entityManager.clear();

        ResearchTask persisted = repository.findById(task.getId()).orElseThrow();
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(oldHeartbeat).isZero();
        assertThat(persisted.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(persisted.getLeaseToken()).isEqualTo("owner-b");
        assertThat(persisted.getAttempts()).isEqualTo(2);
    }

    private ResearchTask runningTask(LocalDateTime heartbeatAt) {
        ResearchTask task = new ResearchTask();
        task.setUserId("takeover-it-user");
        task.setIdempotencyKey("takeover-it-" + System.nanoTime());
        task.setTicker("AAPL");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(ResearchTask.Stage.AGENT_DEBATE);
        task.setAttempts(1);
        task.setLeaseToken("owner-a");
        task.setPayloadJson("{}");
        task.setHeartbeatAt(heartbeatAt);
        return task;
    }
}
