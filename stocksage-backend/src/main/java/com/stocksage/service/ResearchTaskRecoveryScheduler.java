package com.stocksage.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 定时恢复因进程退出、租约丢失或 worker 中断而停滞的研究任务。
 *
 * <p>先由 {@link ResearchTaskService} 在数据库中原子判断重试/终止，再把可重试任务写回
 * {@link ResearchTaskQueue}；队列临时不可用时保留数据库恢复标记，等待下一轮重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchTaskRecoveryScheduler {

    /** 扫描并以条件更新方式恢复数据库任务状态。 */
    private final ResearchTaskService researchTaskService;
    /** 将已标记为可重试的任务重新投递到 Redis Stream。 */
    private final ResearchTaskQueue researchTaskQueue;

    /** RUNNING 任务超过该分钟数未心跳即视为停滞。 */
    @Value("${stocksage.research-task.stale-after-minutes:15}")
    private long staleAfterMinutes = 15;

    /** 达到该尝试次数的停滞任务会被终止而非再次入队。 */
    @Value("${stocksage.research-task.max-attempts:3}")
    private int maxAttempts = 3;

    /** 执行一次停滞任务扫描、数据库恢复和安全重入队。 */
    @Scheduled(
            cron = "${stocksage.research-task.recovery-cron:0 */5 * * * *}",
            zone = "${stocksage.research-task.scheduler-zone:Asia/Shanghai}"
    )
    public void recoverStaleResearchTasks() {
        // 调用任务服务用数据库条件更新抢占恢复权，避免多个调度实例重复恢复同一任务。
        ResearchTaskService.RecoveryResult result = researchTaskService.recoverStaleRunningTasks(
                Duration.ofMinutes(Math.max(1, staleAfterMinutes)),
                maxAttempts
        );
        for (Long taskId : result.retriedTaskIds()) {
            try {
                // 调用 Redis Stream 队列重新投递；失败不回滚已写入的数据库恢复标记。
                researchTaskQueue.enqueue(taskId);
            } catch (ResearchTaskQueue.QueueUnavailableException error) {
                log.warn("Recovered research task could not be re-enqueued; DB recovery marker will retry next round, taskId={}: {}",
                        taskId, error.getMessage());
            }
        }
        if (result.retried() > 0 || result.failed() > 0) {
            log.info("Research task recovery finished, retried={}, failed={}",
                    result.retried(), result.failed());
        }
    }
}
