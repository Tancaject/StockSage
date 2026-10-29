package com.stocksage.research.deep;

import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.research.deep.DeepResearchPipeline.OwnershipLostException;
import com.stocksage.research.task.ResearchTaskCheckpointService;
import com.stocksage.research.task.ResearchTaskLeaseService;
import com.stocksage.research.task.ResearchTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台研究 worker 的 owner fencing：租约与数据库心跳、归属校验，以及带归属校验的阶段、终态和 checkpoint 写入。
 *
 * <p>任一校验或写入发现归属已变化时，置位本次执行的 ownershipLost 标志并抛出
 * {@link OwnershipLostException}，让旧 owner 停止副作用。同步兜底入口有自己的归属检查，不经过这里。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TaskOwnershipFence {

    /** 执行任务状态机、租约续期和 owner-fenced CAS。 */
    private final ResearchTaskService researchTaskService;
    /** 保存证据、Harness、辩论轮次和综合状态检查点。 */
    private final ResearchTaskCheckpointService checkpointService;
    /** 运行研究任务租约与数据库心跳的单线程 daemon 调度器。 */
    private final TaskScheduler researchHeartbeatScheduler;

    static void checkDeadline(ResearchTask task) {
        if (task.getMaxModelCalls() == null || task.getMaxModelCalls() <= 0) {
            throw new ResearchBudgetExceededException(task.getId(), ResearchBudgetExceededException.Reason.BUDGET_UNAVAILABLE);
        }
        ResearchBudgetExceededException.remainingMillis(task.getId(), task.getBudgetDeadlineEpochMs());
    }

    void failExecutionConstraintForOwner(ResearchTask task, ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost, IllegalStateException budget) {
        requireTaskOwnership(task, lease, ownershipLost);
        if (!researchTaskService.markFailedForOwner(task, lease.token(), budget.getMessage())) {
            ownershipLost.set(true);
            throw new OwnershipLostException("research task lost ownership before execution constraint failure update", budget);
        }
    }

    ScheduledFuture<?> startResearchTaskHeartbeat(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost
    ) {
        Duration heartbeatInterval = researchTaskService.leaseHeartbeatInterval();
        if (heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            heartbeatInterval = Duration.ofSeconds(60);
        }
        return researchHeartbeatScheduler.scheduleAtFixedRate(() -> {
            if (ownershipLost.get()) {
                return;
            }
            try {
                boolean leaseRenewed = researchTaskService.renewLease(lease);
                if (!leaseRenewed) {
                    ownershipLost.set(true);
                    log.warn("Research task heartbeat lost Redis ownership, taskId={}", task.getId());
                    return;
                }
                boolean taskHeartbeat = researchTaskService.heartbeatForOwner(task, lease.token());
                if (!taskHeartbeat) {
                    ownershipLost.set(true);
                    log.warn("Research task heartbeat could not renew ownership, taskId={}, taskHeartbeat={}, leaseRenewed={}",
                            task.getId(), taskHeartbeat, leaseRenewed);
                }
            } catch (Exception e) {
                ownershipLost.set(true);
                log.warn("Research task heartbeat failed, taskId={}, error={}", task.getId(), e.getMessage());
            }
        }, Instant.now().plus(heartbeatInterval), heartbeatInterval);
    }

    void requireLeaseOwnership(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost
    ) {
        if (ownershipLost.get()) {
            throw new OwnershipLostException(
                    "research task ownership was already lost, taskId=" + task.getId());
        }
        boolean renewed;
        try {
            renewed = researchTaskService.renewLease(lease);
        } catch (Exception error) {
            ownershipLost.set(true);
            throw new OwnershipLostException(
                    "research task Redis ownership check failed, taskId=" + task.getId(), error);
        }
        if (!renewed) {
            ownershipLost.set(true);
            throw new OwnershipLostException(
                    "research task Redis ownership was lost, taskId=" + task.getId());
        }
    }

    /** Token 热路径只读取 heartbeat 已维护的本地标志，不触发 Redis 或数据库调用。 */
    Runnable ownershipGuard(ResearchTask task, AtomicBoolean ownershipLost) {
        Long taskId = task == null ? null : task.getId();
        return () -> {
            if (ownershipLost.get()) {
                throw new OwnershipLostException(
                        "research task ownership was lost during model streaming, taskId=" + taskId);
            }
        };
    }

    void requireTaskOwnership(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost
    ) {
        requireLeaseOwnership(task, lease, ownershipLost);
        boolean taskHeartbeat;
        try {
            taskHeartbeat = researchTaskService.heartbeatForOwner(task, lease.token());
        } catch (Exception error) {
            ownershipLost.set(true);
            throw new OwnershipLostException(
                    "research task DB ownership check failed, taskId=" + task.getId(), error);
        }
        if (!taskHeartbeat) {
            ownershipLost.set(true);
            throw new OwnershipLostException(
                    "research task DB ownership was lost, taskId=" + task.getId());
        }
    }

    void markStageForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            ResearchTask.Stage stage,
            AtomicBoolean ownershipLost
    ) {
        requireTaskOwnership(task, lease, ownershipLost);
        try {
            researchTaskService.markStageForOwner(task, lease.token(), stage);
        } catch (IllegalStateException error) {
            ownershipLost.set(true);
            throw new OwnershipLostException(
                    "research task lost ownership before stage update, taskId=" + task.getId(), error);
        }
    }

    void markSucceededForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            Long reportVersionId,
            ResearchTask.ResultKind resultKind,
            AtomicBoolean ownershipLost
    ) {
        requireTaskOwnership(task, lease, ownershipLost);
        checkDeadline(task);
        try {
            researchTaskService.markSucceededForOwner(
                    task, lease.token(), reportVersionId, resultKind);
        } catch (IllegalStateException error) {
            ownershipLost.set(true);
            throw new OwnershipLostException(
                    "research task lost ownership before completion, taskId=" + task.getId(), error);
        }
    }

    void saveHarnessSnapshotForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state,
            AtomicBoolean ownershipLost
    ) {
        requireTaskOwnership(task, lease, ownershipLost);
        try {
            checkpointService.saveHarnessSnapshot(task.getId(), lease.token(), state);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw checkpointOwnershipLost(task, ownershipLost, error);
        }
    }

    void saveEvidenceReplanForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state,
            AtomicBoolean ownershipLost
    ) {
        try {
            checkpointService.saveEvidenceReplan(task.getId(), lease.token(), state);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw checkpointOwnershipLost(task, ownershipLost, error);
        }
    }

    void saveEvidenceForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state,
            AtomicBoolean ownershipLost
    ) {
        requireTaskOwnership(task, lease, ownershipLost);
        try {
            checkpointService.saveEvidence(task.getId(), lease.token(), state);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw checkpointOwnershipLost(task, ownershipLost, error);
        }
    }

    void saveDebateRoundForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state,
            int roundsCompleted,
            int authorizedThroughRound,
            AtomicBoolean ownershipLost
    ) {
        requireTaskOwnership(task, lease, ownershipLost);
        try {
            checkpointService.saveDebateRound(
                    task.getId(), lease.token(), state, roundsCompleted, authorizedThroughRound);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw checkpointOwnershipLost(task, ownershipLost, error);
        }
    }

    void saveSynthesisForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state,
            AtomicBoolean ownershipLost
    ) {
        requireTaskOwnership(task, lease, ownershipLost);
        try {
            checkpointService.saveSynthesis(task.getId(), lease.token(), state);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw checkpointOwnershipLost(task, ownershipLost, error);
        }
    }

    OwnershipLostException checkpointOwnershipLost(
            ResearchTask task,
            AtomicBoolean ownershipLost,
            ResearchTaskCheckpointService.OwnershipLostException error
    ) {
        ownershipLost.set(true);
        return new OwnershipLostException(
                "research task lost ownership before checkpoint update, taskId=" + task.getId(), error);
    }
}
