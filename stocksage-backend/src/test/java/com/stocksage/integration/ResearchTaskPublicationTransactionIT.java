package com.stocksage.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.StockSageApplication;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.User;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.InvestmentReportVersionRepository;
import com.stocksage.repository.MessageRepository;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.repository.UserAccountRepository;
import com.stocksage.conversation.ConversationMessageService;
import com.stocksage.research.InvestmentReportPersistedEvent;
import com.stocksage.research.ResearchTaskLeaseService;
import com.stocksage.research.ResearchTaskPublicationTransaction;
import com.stocksage.research.ResearchTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = false)
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.stocksage.config.ModelTokenBudgetProperties.class)
@ContextConfiguration(classes = StockSageApplication.class)
@Import({
        ResearchTaskPublicationTransaction.class,
        ResearchTaskService.class,
        ConversationMessageService.class,
        ResearchTaskPublicationTransactionIT.Dependencies.class
})
class ResearchTaskPublicationTransactionIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("stocksage_publication_it")
            .withUsername("stocksage")
            .withPassword("stocksage");

    @Autowired
    private ResearchTaskPublicationTransaction publicationTransaction;

    @Autowired
    private ResearchTaskService researchTaskService;

    @Autowired
    private ResearchTaskRepository taskRepository;

    @Autowired
    private InvestmentReportVersionRepository reportRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ConversationMessageService conversationMessageService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReportEventProbe eventProbe;

    @DynamicPropertySource
    static void registerMySql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @BeforeEach
    void verifyMySqlAndResetProbe() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        eventProbe.reset();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleOwnerTerminalCasRollsBackReportMessageAndAfterCommitEvent() {
        String userId = "u-rollback";
        userAccountRepository.saveAndFlush(user(userId));
        Conversation conversation = conversationRepository.saveAndFlush(conversation(userId));
        LocalDateTime conversationUpdatedAt = conversationUpdatedAt(conversation.getId());
        ResearchTask task = taskRepository.saveAndFlush(runningTask(
                userId, conversation.getId(), "publication-mysql-rollback", "current-token"));
        long reportsBefore = reportRepository.count();
        long messagesBefore = messageRepository.count();

        assertThatThrownBy(() -> publicationTransaction.executeForUser(userId, () -> {
            InvestmentReportVersion report = reportRepository.saveAndFlush(
                    reportVersion(userId, conversation.getId()));
            conversationMessageService.persistAssistantReport(
                    conversation.getId(),
                    userId,
                    "publication must roll back",
                    "trace-publication"
            );
            messageRepository.flush();
            eventPublisher.publishEvent(new InvestmentReportPersistedEvent(
                    report,
                    InvestmentReport.builder().recommendation("WATCH").build()
            ));
            researchTaskService.markSucceededForOwner(
                    task,
                    "stale-token",
                    report.getId(),
                    ResearchTask.ResultKind.FULL_REPORT
            );
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost ownership");

        assertThat(reportRepository.count()).isEqualTo(reportsBefore);
        assertThat(messageRepository.count()).isEqualTo(messagesBefore);
        ResearchTask persistedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(persistedTask.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(persistedTask.getStage()).isEqualTo(ResearchTask.Stage.REPORT_PERSIST);
        assertThat(persistedTask.getLeaseToken()).isEqualTo("current-token");
        assertThat(persistedTask.getResultReportVersionId()).isNull();
        assertThat(persistedTask.getResultKind()).isNull();
        assertThat(conversationUpdatedAt(conversation.getId()))
                .isEqualTo(conversationUpdatedAt);
        assertThat(eventProbe.afterCommitCount()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameUserConcurrentPublicationsReuseOneReportAndCommitBothTasks() throws Exception {
        String userId = "u-concurrent";
        userAccountRepository.saveAndFlush(user(userId));
        ResearchTask firstTask = taskRepository.saveAndFlush(
                runningTask(userId, 17L, "publication-mysql-concurrent-1", "owner-1"));
        ResearchTask secondTask = taskRepository.saveAndFlush(
                runningTask(userId, 17L, "publication-mysql-concurrent-2", "owner-2"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger activePublications = new AtomicInteger();
        AtomicInteger maxActivePublications = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Long> first = submitPublication(
                    executor, ready, start, activePublications, maxActivePublications,
                    userId, firstTask, "owner-1");
            Future<Long> second = submitPublication(
                    executor, ready, start, activePublications, maxActivePublications,
                    userId, secondTask, "owner-2");
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Long firstReportId = first.get(10, TimeUnit.SECONDS);
            Long secondReportId = second.get(10, TimeUnit.SECONDS);

            assertThat(firstReportId).isEqualTo(secondReportId);
            assertThat(reportRepository.count()).isEqualTo(1);
            assertThat(maxActivePublications).hasValue(1);
            assertSucceededWithReport(firstTask.getId(), firstReportId);
            assertSucceededWithReport(secondTask.getId(), firstReportId);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Future<Long> submitPublication(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicInteger activePublications,
            AtomicInteger maxActivePublications,
            String userId,
            ResearchTask task,
            String ownerToken
    ) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent publication start gate timed out");
            }
            return publicationTransaction.executeForUser(userId, () -> {
                int active = activePublications.incrementAndGet();
                maxActivePublications.accumulateAndGet(active, Math::max);
                try {
                    // Hold the critical section briefly. If the row lock disappears, both
                    // callbacks overlap and this assertion can no longer pass by scheduler luck.
                    holdCriticalSection();
                    InvestmentReportVersion report = reportRepository
                            .findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                                    userId, "NVDA", "c".repeat(64), "d".repeat(64))
                            .orElseGet(() -> reportRepository.saveAndFlush(
                                    concurrentReportVersion(userId)));
                    researchTaskService.markSucceededForOwner(
                            task,
                            ownerToken,
                            report.getId(),
                            ResearchTask.ResultKind.FULL_REPORT
                    );
                    return report.getId();
                } finally {
                    activePublications.decrementAndGet();
                }
            });
        });
    }

    private void holdCriticalSection() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("publication serialization probe interrupted", error);
        }
    }

    private void assertSucceededWithReport(Long taskId, Long reportId) {
        ResearchTask persisted = taskRepository.findById(taskId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ResearchTask.Status.SUCCEEDED);
        assertThat(persisted.getStage()).isEqualTo(ResearchTask.Stage.COMPLETE);
        assertThat(persisted.getResultReportVersionId()).isEqualTo(reportId);
        assertThat(persisted.getLeaseToken()).isNull();
    }

    private LocalDateTime conversationUpdatedAt(Long conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM conversations WHERE id = ?",
                LocalDateTime.class,
                conversationId
        );
    }

    private ResearchTask runningTask(
            String userId,
            Long conversationId,
            String idempotencyKey,
            String leaseToken
    ) {
        ResearchTask task = new ResearchTask();
        task.setUserId(userId);
        task.setConversationId(conversationId);
        task.setIdempotencyKey(idempotencyKey);
        task.setTicker("NVDA");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(ResearchTask.Stage.REPORT_PERSIST);
        task.setAttempts(1);
        task.setLeaseToken(leaseToken);
        task.setPayloadJson("{}");
        return task;
    }

    private InvestmentReportVersion reportVersion(String userId, Long conversationId) {
        InvestmentReportVersion report = new InvestmentReportVersion();
        report.setUserId(userId);
        report.setConversationId(conversationId);
        report.setTicker("NVDA");
        report.setRecommendation("WATCH");
        report.setReportVersion(1);
        report.setDataSnapshotHash("a".repeat(64));
        report.setContextHash("b".repeat(64));
        report.setModelTier("STRONG");
        report.setModelName("qwen");
        report.setReportJson("{}");
        report.setGeneratedAt(LocalDateTime.now());
        return report;
    }

    private InvestmentReportVersion concurrentReportVersion(String userId) {
        InvestmentReportVersion report = reportVersion(userId, 17L);
        report.setDataSnapshotHash("c".repeat(64));
        report.setContextHash("d".repeat(64));
        return report;
    }

    private User user(String userId) {
        User user = new User();
        user.setUserId(userId);
        user.setEmail(userId + "@example.test");
        user.setNickname("Publication Test");
        return user;
    }

    private Conversation conversation(String userId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setOrigin("workbench");
        conversation.setTitle("Publication rollback test");
        return conversation;
    }

    static class ReportEventProbe {
        private final AtomicInteger afterCommitCount = new AtomicInteger();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onReportPersisted(InvestmentReportPersistedEvent event) {
            afterCommitCount.incrementAndGet();
        }

        int afterCommitCount() {
            return afterCommitCount.get();
        }

        void reset() {
            afterCommitCount.set(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {

        @Bean
        ResearchTaskLeaseService researchTaskLeaseService() {
            return mock(ResearchTaskLeaseService.class);
        }

        @Bean
        @Primary
        ObjectMapper publicationObjectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ReportEventProbe reportEventProbe() {
            return new ReportEventProbe();
        }
    }
}
