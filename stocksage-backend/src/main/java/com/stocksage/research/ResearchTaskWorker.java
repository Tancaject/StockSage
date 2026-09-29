package com.stocksage.research;

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

/**
 * 研究任务 Redis Stream 的生命周期托管 worker。
 *
 * <p>每条记录先读取数据库任务、获取可续约租约并通过 {@link ResearchTaskService} 建立 owner fence，
 * 再调用 {@link DeepResearchPipeline}。成功或终态记录才 ACK；ownership 丢失和临时失败保留在 PEL，
 * 由本地重试或定时 reclaim 接手。停止时等待已进入执行区的任务完成到配置上限。</p>
 */
@Slf4j
@Service
public class ResearchTaskWorker implements SmartLifecycle {

    /** Redis Stream 队列操作。 */
    private final ResearchTaskQueue queue;
    /** 按 taskId 读取当前数据库任务。 */
    private final ResearchTaskRepository repository;
    /** 获取租约、执行 fenced 状态变更和失败重置。 */
    private final ResearchTaskService researchTaskService;
    /** 执行证据、辩论、报告和原子发布全流程。 */
    private final DeepResearchPipeline deepResearchPipeline;
    /** 承载多个长期消费循环。 */
    private final ThreadPoolTaskExecutor executor;
    /** 队列 worker 总开关。 */
    private final boolean enabled;
    /** 启动的独立 consumer 数。 */
    private final int workerThreads;
    /** 单任务最大执行尝试数。 */
    private final int maxAttempts;
    /** 停机等待 in-flight 任务的最长时间。 */
    private final long gracefulShutdownWaitMillis;
    /** 每个 JVM 实例唯一的 consumer 名称前缀。 */
    private final String consumerPrefix = "worker-" + UUID.randomUUID();
    /** SmartLifecycle 运行状态和消费循环退出开关。 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 已进入执行区、尚未退出的任务数。 */
    private final AtomicInteger inFlight = new AtomicInteger(0);
    /** 协调停机线程等待 inFlight 归零。 */
    private final Object executionMonitor = new Object();
    /** reclaim 线程转交给消费循环处理的 stale 记录。 */
    private final ConcurrentLinkedQueue<MapRecord<String, String, String>> reclaimed =
            new ConcurrentLinkedQueue<>();

    /**
     * 创建 worker 并收敛线程数、尝试数和停机等待配置。
     *
     * @param queue Redis Stream 队列
     * @param repository 任务仓储
     * @param researchTaskService 任务状态与租约服务
     * @param deepResearchPipeline 深度研究管线
     * @param executor worker 执行器
     * @param enabled 是否启用
     * @param workerThreads consumer 数
     * @param maxAttempts 最大尝试数
     * @param gracefulShutdownWaitSeconds 停机等待秒数
     */
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

    /** 创建 consumer group，并为每个配置线程启动一个长轮询循环。 */
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

    /** 停止接收新记录，并等待已开始执行的任务退出到配置期限。 */
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

    /**
     * 停止完成后调用 Spring 生命周期回调。
     *
     * @param callback Spring 提供的停止完成回调
     */
    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    /** @return worker 是否仍接受和处理队列记录。 */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** @return 是否随 Spring 上下文自动启动。 */
    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    /** @return 最大阶段值，使 worker 尽量最后停止。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** 定时把长时间 pending 的记录 claim 到当前进程的待处理队列。 */
    @Scheduled(fixedDelayString = "${stocksage.research-task.queue.reclaim-interval-ms:60000}")
    public void reclaimStalePending() {
        if (!running.get()) {
            return;
        }
        try {
            // 调用 XCLAIM 接手可能由崩溃 worker 留下的 PEL 记录，不先 ACK。
            List<MapRecord<String, String, String>> records =
                    queue.claimStale(consumerPrefix + "-reclaimer", Math.max(16, workerThreads * 8));
            reclaimed.addAll(records);
        } catch (ResearchTaskQueue.QueueUnavailableException error) {
            log.warn("Research task pending reclaim unavailable: {}", error.getMessage());
        }
    }

    /** 持续优先处理已 reclaim 记录，再阻塞读取新记录。 */
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

    /**
     * 处理单条记录：终态去重、租约获取、尝试 fencing、管线执行及 ACK/重试。
     */
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

        // 调用租约服务竞争同一幂等任务的执行权；失败时保留记录给现 owner。
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

        boolean retryLocally = false;
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
            // 只有建立 owner fence 后才调用完整研究管线，管线内部还会持续心跳校验。
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
                retryLocally = true;
                log.warn("Research task execution failed, taskId={}, reset to pending for local retry: {}",
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
        if (retryLocally && running.get()) {
            // Only expose the Redis pending record to another local worker after the failed
            // attempt's lease has been released. Redis remains the durable fallback: if this
            // process stops during the short backoff, the record stays in the PEL for reclaim.
            backoff();
            if (running.get()) {
                reclaimed.offer(record);
            }
        }
    }

    /** 在停机锁下登记一个 in-flight 执行；停止后拒绝新执行。 */
    private boolean beginExecution() {
        synchronized (executionMonitor) {
            if (!running.get()) {
                return false;
            }
            inFlight.incrementAndGet();
            return true;
        }
    }

    /** 注销 in-flight 执行，并在归零时唤醒停机等待线程。 */
    private void endExecution() {
        synchronized (executionMonitor) {
            if (inFlight.decrementAndGet() == 0) {
                executionMonitor.notifyAll();
            }
        }
    }

    /** 仅在正常运行期间 ACK，停机时把记录留给后续 reclaim。 */
    private void ackIfRunning(MapRecord<String, String, String> record) {
        if (running.get()) {
            queue.ack(record);
        }
    }

    /** 判断数据库任务是否已经成功或失败终结。 */
    private boolean isTerminal(ResearchTask task) {
        return task.getStatus() == ResearchTask.Status.SUCCEEDED
                || task.getStatus() == ResearchTask.Status.FAILED;
    }

    /** 将可空尝试次数视为零。 */
    private int safeAttempts(ResearchTask task) {
        return task.getAttempts() == null ? 0 : task.getAttempts();
    }

    /** 从 Stream 记录读取 taskId；损坏记录返回 null 并按无任务 ACK。 */
    private Long parseTaskId(MapRecord<String, String, String> record) {
        try {
            String value = record == null ? null : record.getValue().get("taskId");
            return value == null || value.isBlank() ? null : Long.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** 尽力创建 consumer group；Redis 暂时不可用时留给循环重试。 */
    private void ensureGroupBestEffort() {
        try {
            queue.ensureConsumerGroup();
        } catch (ResearchTaskQueue.QueueUnavailableException error) {
            log.warn("Research task consumer group unavailable: {}", error.getMessage());
        }
    }

    /** 队列或任务失败后短暂退避；中断时恢复线程标记。 */
    private void backoff() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
