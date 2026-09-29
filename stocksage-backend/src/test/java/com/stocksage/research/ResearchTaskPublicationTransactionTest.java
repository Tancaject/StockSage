package com.stocksage.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.Message;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.User;
import com.stocksage.repository.InvestmentReportVersionRepository;
import com.stocksage.repository.MessageRepository;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.repository.UserAccountRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.stocksage.config.ModelTokenBudgetProperties.class)
@Import({
        ResearchTaskPublicationTransaction.class,
        ResearchTaskService.class,
        ResearchTaskPublicationTransactionTest.Dependencies.class
})
class ResearchTaskPublicationTransactionTest {

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
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ReportEventProbe eventProbe;

    @BeforeEach
    void resetProbe() {
        eventProbe.reset();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reviewUpdatesCannotOverwriteOriginalProducer() {
        InvestmentReportVersion report = reportVersion();
        report.setProducerRunId(42L);
        report.setProducerAttempt(2);
        report.setProducerEvidenceSnapshotId("snapshot-42");
        report = reportRepository.saveAndFlush(report);
        report.setProducerRunId(99L);
        report.setProducerAttempt(3);
        report.setProducerEvidenceSnapshotId("snapshot-99");
        report.setReviewComment("reviewed");
        reportRepository.saveAndFlush(report);

        InvestmentReportVersion persisted = reportRepository.findById(report.getId()).orElseThrow();
        assertThat(persisted.getReviewComment()).isEqualTo("reviewed");
        assertThat(persisted.getProducerRunId()).isEqualTo(42L);
        assertThat(persisted.getProducerAttempt()).isEqualTo(2);
        assertThat(persisted.getProducerEvidenceSnapshotId()).isEqualTo("snapshot-42");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleOwnerTerminalCasRollsBackReportMessageAndAfterCommitEvent() {
        ResearchTask task = taskRepository.saveAndFlush(runningTask());
        long reportsBefore = reportRepository.count();
        long messagesBefore = messageRepository.count();

        assertThatThrownBy(() -> publicationTransaction.execute(() -> {
            InvestmentReportVersion report = reportRepository.saveAndFlush(reportVersion());
            messageRepository.saveAndFlush(assistantMessage());
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
        assertThat(eventProbe.afterCommitCount()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameUserConcurrentPublicationsReuseOneReportAndCommitBothTasks() throws Exception {
        String userId = "u-concurrent";
        userAccountRepository.saveAndFlush(user(userId));
        ResearchTask firstTask = taskRepository.saveAndFlush(
                runningTask(userId, "publication-concurrent-1", "owner-1"));
        ResearchTask secondTask = taskRepository.saveAndFlush(
                runningTask(userId, "publication-concurrent-2", "owner-2"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Long> first = submitPublication(
                    executor, ready, start, userId, firstTask, "owner-1");
            Future<Long> second = submitPublication(
                    executor, ready, start, userId, secondTask, "owner-2");
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Long firstReportId = first.get(10, TimeUnit.SECONDS);
            Long secondReportId = second.get(10, TimeUnit.SECONDS);

            assertThat(firstReportId).isEqualTo(secondReportId);
            assertThat(reportRepository.count()).isEqualTo(1);
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
            });
        });
    }

    private void assertSucceededWithReport(Long taskId, Long reportId) {
        ResearchTask persisted = taskRepository.findById(taskId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ResearchTask.Status.SUCCEEDED);
        assertThat(persisted.getStage()).isEqualTo(ResearchTask.Stage.COMPLETE);
        assertThat(persisted.getResultReportVersionId()).isEqualTo(reportId);
        assertThat(persisted.getLeaseToken()).isNull();
    }

    private ResearchTask runningTask() {
        return runningTask("u-a", "publication-rollback", "current-token");
    }

    private ResearchTask runningTask(String userId, String idempotencyKey, String leaseToken) {
        ResearchTask task = new ResearchTask();
        task.setUserId(userId);
        task.setConversationId(17L);
        task.setIdempotencyKey(idempotencyKey);
        task.setTicker("NVDA");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(ResearchTask.Stage.REPORT_PERSIST);
        task.setAttempts(1);
        task.setLeaseToken(leaseToken);
        task.setPayloadJson("{}");
        return task;
    }

    private InvestmentReportVersion reportVersion() {
        InvestmentReportVersion report = new InvestmentReportVersion();
        report.setUserId("u-a");
        report.setConversationId(17L);
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
        InvestmentReportVersion report = reportVersion();
        report.setUserId(userId);
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

    private Message assistantMessage() {
        Message message = new Message();
        message.setConversationId(17L);
        message.setRole("assistant");
        message.setContent("publication must roll back");
        message.setTraceId("trace-publication");
        return message;
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
