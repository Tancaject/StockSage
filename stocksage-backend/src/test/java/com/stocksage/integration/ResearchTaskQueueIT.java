package com.stocksage.integration;

import com.stocksage.config.AsyncConfig;
import com.stocksage.config.ResearchTaskMetricsConfig;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.service.DeepResearchPipeline;
import com.stocksage.service.ResearchTaskLeaseService;
import com.stocksage.service.ResearchTaskQueue;
import com.stocksage.service.ResearchTaskService;
import com.stocksage.service.ResearchTaskWorker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest(
        classes = ResearchTaskQueueIT.TestApplication.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "stocksage.research-task.queue.enabled=true",
                "stocksage.research-task.queue.stream=stream:research-tasks:it",
                "stocksage.research-task.queue.group=research-workers-it",
                "stocksage.research-task.queue.dlq-stream=stream:research-tasks-dlq:it",
                "stocksage.research-task.queue.poll-block-ms=100",
                "stocksage.research-task.queue.claim-min-idle-ms=500",
                "stocksage.research-task.worker-threads=2",
                "stocksage.research-task.max-attempts=2",
                "stocksage.research-task.graceful-shutdown-wait-seconds=5"
        }
)
class ResearchTaskQueueIT {

    private static final String TASK_STREAM = "stream:research-tasks:it";
    private static final String DLQ_STREAM = "stream:research-tasks-dlq:it";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("stocksage_queue_it")
            .withUsername("stocksage")
            .withPassword("stocksage");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    private ResearchTaskQueue queue;

    @Autowired
    private ResearchTaskWorker worker;

    @Autowired
    private ResearchTaskService researchTaskService;

    @Autowired
    private ResearchTaskRepository researchTaskRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private DeepResearchPipeline deepResearchPipeline;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void resetQueue() {
        worker.stop();
        redis.delete(List.of(TASK_STREAM, DLQ_STREAM));
        researchTaskRepository.deleteAll();
        reset(deepResearchPipeline);
        worker.start();
    }

    @AfterEach
    void stopWorker() {
        worker.stop();
    }

    @Test
    void twoWorkersNeverExecuteSameTaskTwice() throws Exception {
        Map<Long, AtomicInteger> executions = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            ResearchTask task = invocation.getArgument(0);
            ResearchTaskLeaseService.Lease lease = invocation.getArgument(1);
            researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH);
            executions.computeIfAbsent(task.getId(), ignored -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(2_000);
            researchTaskService.markSucceededForOwner(task, lease.token(), null);
            researchTaskService.release(lease);
            return null;
        }).when(deepResearchPipeline).runFullPipeline(any(), any());

        ResearchTask task = createPendingTask("AAPL");
        queue.enqueue(task.getId());
        queue.enqueue(task.getId());

        awaitUntil(() -> executions.containsKey(task.getId()), Duration.ofSeconds(10));
        Thread.sleep(3_000);

        assertThat(executions.get(task.getId()).get()).isEqualTo(1);
    }

    @Test
    void failedExecutionIsReclaimedAndSentToDlqAfterMaxAttempts() throws Exception {
        doAnswer(invocation -> {
            ResearchTask task = invocation.getArgument(0);
            ResearchTaskLeaseService.Lease lease = invocation.getArgument(1);
            researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH);
            researchTaskService.release(lease);
            throw new IllegalStateException("simulated pipeline failure");
        }).when(deepResearchPipeline).runFullPipeline(any(), any());

        ResearchTask task = createPendingTask("MSFT");
        queue.enqueue(task.getId());

        awaitUntil(() -> attempts(task.getId()) == 1, Duration.ofSeconds(10));
        waitUntilClaimable();
        worker.reclaimStalePending();
        awaitUntil(() -> attempts(task.getId()) == 2, Duration.ofSeconds(10));
        waitUntilClaimable();
        worker.reclaimStalePending();

        awaitUntil(() -> queue.dlqDepth() == 1, Duration.ofSeconds(10));
        ResearchTask failed = researchTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ResearchTask.Status.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(2);
    }

    @Test
    void stopLeavesCompletedInFlightRecordPendingForTakeover() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releasePipeline = new CountDownLatch(1);
        doAnswer(invocation -> {
            ResearchTask task = invocation.getArgument(0);
            ResearchTaskLeaseService.Lease lease = invocation.getArgument(1);
            researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH);
            started.countDown();
            if (!releasePipeline.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("pipeline release timed out");
            }
            researchTaskService.markSucceededForOwner(task, lease.token(), null);
            researchTaskService.release(lease);
            return null;
        }).when(deepResearchPipeline).runFullPipeline(any(), any());

        ResearchTask task = createPendingTask("NVDA");
        queue.enqueue(task.getId());
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(worker::stop);
        awaitUntil(() -> !worker.isRunning(), Duration.ofSeconds(2));
        releasePipeline.countDown();
        stopping.get(5, TimeUnit.SECONDS);

        assertThat(queue.pendingDepth()).isEqualTo(1);
        assertThat(researchTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(ResearchTask.Status.SUCCEEDED);
    }

    @Test
    void exposesQueueDlqAndRunningTaskDepthMetrics() {
        ResearchTask task = createPendingTask("AMD");
        queue.enqueue(task.getId());
        queue.enqueueToDlq(task.getId(), "metrics-probe");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(ResearchTask.Stage.DATA_PREFETCH);
        researchTaskRepository.saveAndFlush(task);

        assertThat(meterRegistry.get("stocksage.research.queue.depth").gauge().value()).isEqualTo(1);
        assertThat(meterRegistry.get("stocksage.research.queue.dlq.depth").gauge().value()).isEqualTo(1);
        assertThat(meterRegistry.get("stocksage.research.tasks.running").gauge().value()).isEqualTo(1);
    }

    private ResearchTask createPendingTask(String ticker) {
        String traceId = "trace-" + UUID.randomUUID();
        String payload = researchTaskService.buildSubmissionPayload(ticker, "research " + ticker, traceId, null);
        return researchTaskService.createIfAbsent(
                "queue-it-" + UUID.randomUUID(),
                "queue-it-user",
                null,
                ticker,
                ResearchTask.Stage.CREATED,
                payload
        ).task();
    }

    private int attempts(Long taskId) {
        return researchTaskRepository.findById(taskId)
                .map(ResearchTask::getAttempts)
                .orElse(0);
    }

    private void waitUntilClaimable() throws InterruptedException {
        Thread.sleep(650);
    }

    private void awaitUntil(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition was not met within " + timeout);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration"
    })
    @EnableScheduling
    @EnableJpaRepositories(
            basePackageClasses = ResearchTaskQueueIT.class,
            considerNestedRepositories = true
    )
    @EntityScan(basePackageClasses = ResearchTask.class)
    @Import({
            AsyncConfig.class,
            ResearchTaskQueue.class,
            ResearchTaskMetricsConfig.class,
            ResearchTaskWorker.class,
            ResearchTaskService.class,
            ResearchTaskLeaseService.class
    })
    static class TestApplication {
    }

    interface TestResearchTaskRepository extends ResearchTaskRepository {
    }

    interface TestResearchTaskCheckpointRepository extends ResearchTaskCheckpointRepository {
    }
}
