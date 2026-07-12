package com.stocksage.service;

import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ResearchTaskWorker implements SmartLifecycle {

    private final ResearchTaskQueue queue;
    private final ResearchTaskRepository repository;
    private final ResearchTaskService researchTaskService;
    private final DeepResearchPipeline deepResearchPipeline;
    private final ThreadPoolTaskExecutor executor;
    private final boolean enabled;
    private final int workerThreads;
    private final int maxAttempts;
    private final long gracefulShutdownWaitMillis;
    private final String consumerPrefix = "worker-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final Object executionMonitor = new Object();
    private final ConcurrentLinkedQueue<MapRecord<String, String, String>> reclaimed =
            new ConcurrentLinkedQueue<>();

    public ResearchTaskWorker(
            ResearchTaskQueue queue,
            ResearchTaskRepository repository,
            ResearchTaskService researchTaskService,
            DeepResearchPipeline deepResearchPipeline,
            @Qualifier("researchWorkerExecutor") ThreadPoolTaskExecutor executor,
            @Value("${stocksage.research-task.queue.enabled:true}") boolean enabled,
            @Value("${stocksage.research-task.worker-threads:2}") int workerThreads,
            @Value("${stocksage.research-task.max-attempts:3}") int maxAttempts,
            @Value("${stocksage.research-task.graceful-shutdown-wait-seconds:30}") long gracefulShutdownWaitSeconds
    ) {
        this.queue = queue;
        this.repository = repository;
        this.researchTaskService = researchTaskService;
        this.deepResearchPipeline = deepResearchPipeline;
        this.executor = executor;
        this.enabled = enabled;
        this.workerThreads = Math.max(1, workerThreads);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.gracefulShutdownWaitMillis = Math.max(0, gracefulShutdownWaitSeconds) * 1000;
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        ensureGroupBestEffort();
        for (int index = 0; index < workerThreads; index++) {
            String consumerName = consumerPrefix + "-" + index;
            executor.execute(() -> consumeLoop(consumerName));
        }
    }

    @Override
    public void stop() {
        running.set(false);
        long deadline = System.currentTimeMillis() + gracefulShutdownWaitMillis;
        synchronized (executionMonitor) {
            while (inFlight.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.warn("Research worker graceful stop timed out with {} task(s) still running", inFlight.get());
                    return;
                }
                try {
                    executionMonitor.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Scheduled(fixedDelayString = "${stocksage.research-task.queue.reclaim-interval-ms:60000}")
    public void reclaimStalePending() {
        if (!running.get()) {
            return;
        }
        try {
            List<MapRecord<String, String, String>> records =
                    queue.claimStale(consumerPrefix + "-reclaimer", Math.max(16, workerThreads * 8));
            reclaimed.addAll(records);
        } catch (ResearchTaskQueue.QueueUnavailableException error) {
            log.warn("Research task pending reclaim unavailable: {}", error.getMessage());
        }
    }

    private void consumeLoop(String consumerName) {
        while (running.get()) {
            try {
                MapRecord<String, String, String> claimed = reclaimed.poll();
                if (claimed != null) {
                    processRecord(claimed);
                    continue;
                }
                for (MapRecord<String, String, String> record : queue.poll(consumerName)) {
                    processRecord(record);
                }
            } catch (ResearchTaskQueue.QueueUnavailableException error) {
                log.warn("Research worker queue unavailable, retrying: {}", error.getMessage());
                ensureGroupBestEffort();
                backoff();
            } catch (Exception error) {
                log.warn("Research worker loop failed, retrying: {}", error.getMessage());
                backoff();
            }
        }
    }

    private void processRecord(MapRecord<String, String, String> record) {
        if (!running.get()) {
            return;
        }
        Long taskId = parseTaskId(record);
        ResearchTask task = taskId == null ? null : repository.findById(taskId).orElse(null);
        if (task == null || isTerminal(task)) {
            ackIfRunning(record);
            return;
        }

        Optional<ResearchTaskLeaseService.Lease> lease = researchTaskService.tryAcquire(task);
        if (lease.isEmpty()) {
            log.debug("Research task lease is owned elsewhere; leaving queue record pending, taskId={}", task.getId());
            return;
        }
        ResearchTaskLeaseService.Lease acquired = lease.get();
        if (!beginExecution()) {
            researchTaskService.release(acquired);
            return;
        }

        try {
            if (safeAttempts(task) >= maxAttempts) {
                boolean failed = researchTaskService.failStaleRunningAtAttemptLimit(
                        task,
                        acquired,
                        maxAttempts,
                        "research task exceeded attempt limit " + maxAttempts
                );
                if (!failed) {
                    log.debug("Research task attempt-limit fencing rejected; leaving queue record pending, taskId={}",
                            task.getId());
                    return;
                }
                queue.enqueueToDlq(task.getId(), "max attempts exceeded");
                ackIfRunning(record);
                return;
            }
            if (task.getStatus() == ResearchTask.Status.RUNNING) {
                boolean takenOver = researchTaskService.takeOverRunningAttempt(task, acquired, maxAttempts);
                if (!takenOver) {
                    log.debug("Research task takeover fencing rejected; leaving queue record pending, taskId={}",
                            task.getId());
                    return;
                }
                log.info("Research task stale attempt taken over, taskId={}, attempts={}",
                        task.getId(), task.getAttempts());
            }
            deepResearchPipeline.runFullPipeline(task, acquired);
            ackIfRunning(record);
        } catch (DeepResearchPipeline.OwnershipLostException error) {
            log.info("Research task worker stopped after ownership loss; leaving record pending, taskId={}, error={}",
                    task.getId(), error.getMessage());
        } catch (Exception error) {
            boolean resetForRetry = researchTaskService.resetRunningForRetryForOwner(
                    task,
                    acquired.token(),
                    error.getMessage()
            );
            if (resetForRetry) {
                log.warn("Research task execution failed, taskId={}, reset to pending for reclaim: {}",
                        task.getId(), error.getMessage());
            } else {
                log.warn("Research task execution failed after ownership changed or task became terminal, "
                                + "taskId={}, leaving record pending: {}",
                        task.getId(), error.getMessage());
            }
        } finally {
            researchTaskService.release(acquired);
            endExecution();
        }
    }

    private boolean beginExecution() {
        synchronized (executionMonitor) {
            if (!running.get()) {
                return false;
            }
            inFlight.incrementAndGet();
            return true;
        }
    }

    private void endExecution() {
        synchronized (executionMonitor) {
            if (inFlight.decrementAndGet() == 0) {
                executionMonitor.notifyAll();
            }
        }
    }

    private void ackIfRunning(MapRecord<String, String, String> record) {
        if (running.get()) {
            queue.ack(record);
        }
    }

    private boolean isTerminal(ResearchTask task) {
        return task.getStatus() == ResearchTask.Status.SUCCEEDED
                || task.getStatus() == ResearchTask.Status.FAILED;
    }

    private int safeAttempts(ResearchTask task) {
        return task.getAttempts() == null ? 0 : task.getAttempts();
    }

    private Long parseTaskId(MapRecord<String, String, String> record) {
        try {
            String value = record == null ? null : record.getValue().get("taskId");
            return value == null || value.isBlank() ? null : Long.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void ensureGroupBestEffort() {
        try {
            queue.ensureConsumerGroup();
        } catch (ResearchTaskQueue.QueueUnavailableException error) {
            log.warn("Research task consumer group unavailable: {}", error.getMessage());
        }
    }

    private void backoff() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
