package com.stocksage.repository;

import com.stocksage.model.entity.InvestmentReportVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class InvestmentReportVersionRepositoryTest {

    @Autowired
    private InvestmentReportVersionRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void pessimisticWriteLookupSerializesCaptureAgainstReviewUpdate() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        InvestmentReportVersion saved = transactions.execute(status ->
                repository.saveAndFlush(reportVersion()));
        assertThat(saved).isNotNull();

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transactions.executeWithoutResult(status -> {
                repository.findByIdAndUserIdForUpdate(saved.getId(), "u-a").orElseThrow();
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertThat(firstLocked.await(2, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> {
                secondAttempted.countDown();
                transactions.executeWithoutResult(status -> {
                    repository.findByIdAndUserIdForUpdate(saved.getId(), "u-a").orElseThrow();
                    secondLocked.countDown();
                });
            });
            assertThat(secondAttempted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(secondLocked.await(250, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertThat(secondLocked.getCount()).isZero();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private InvestmentReportVersion reportVersion() {
        InvestmentReportVersion report = new InvestmentReportVersion();
        report.setUserId("u-a");
        report.setConversationId(3L);
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

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating repository lock test", interrupted);
        }
    }
}
