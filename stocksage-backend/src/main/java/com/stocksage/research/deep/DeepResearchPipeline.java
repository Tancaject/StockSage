package com.stocksage.research.deep;

import static com.stocksage.research.deep.TaskOwnershipFence.checkDeadline;

import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.tool.ToolCallContext;
import com.stocksage.workbench.OfflineDemoSampleService;

import com.stocksage.conversation.ConversationMessageService;

import com.stocksage.agent.intent.TimeSensitivity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.runtime.AgentStep;
import com.stocksage.agent.runtime.ModelInvocationContext;
import com.stocksage.debate.DebateModels.Side;
import com.stocksage.debate.ResearchDebateService;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.research.report.InvestmentReport;
import com.stocksage.research.report.InvestmentReportVersionService;
import com.stocksage.research.report.ReportMarkdownRenderer;
import com.stocksage.research.task.ResearchTaskCheckpointService;
import com.stocksage.research.task.ResearchTaskLeaseService;
import com.stocksage.research.task.ResearchTaskPublicationTransaction;
import com.stocksage.research.task.ResearchTaskService;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 深度研究任务的执行流水线。
 *
 * <p>拥有后台执行与队列不可用时的同步研究生命周期：启动尝试、证据恢复、租约心跳、
 * 辩论检查点、报告发布和任务终态。两个入口复用辩论阶段，保留各自的恢复与交付契约。</p>
 *
 * <p>证据、Harness、辩论和综合状态写入持久化 checkpoint；最终报告、会话消息和带 owner fencing 的
 * SUCCEEDED CAS 在后台入口通过 {@link ResearchTaskPublicationTransaction} 同事务发布；同步入口
 * 只发布报告与任务终态，助手消息交由聊天服务保存。所有权丢失时停止旧 owner 的副作用，
 * 保留检查点和任务 trace，由接管 worker 继续。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepResearchPipeline {

    /** 执行任务状态机、租约续期和 owner-fenced CAS。 */
    private final ResearchTaskService researchTaskService;
    /** 运行 Bull/Bear/Manager 多轮辩论与报告综合。 */
    private final ResearchDebateService researchDebateService;
    /** 计算双哈希、执行安全复用并持久化报告版本。 */
    private final InvestmentReportVersionService investmentReportVersionService;
    /** 外部服务不可用时生成显式 OFFLINE_FALLBACK 样本报告。 */
    private final OfflineDemoSampleService offlineDemoSampleService;
    /** 解析任务提交载荷并序列化兼容结果。 */
    private final ObjectMapper objectMapper;
    /** 保存证据、Harness、辩论轮次和综合状态检查点。 */
    private final ResearchTaskCheckpointService checkpointService;
    /** 收集/恢复确定性证据并执行首次 Harness 闸门。 */
    private final DeepEvidenceCollector evidenceCollector;

    /** 证据摘要上限；按每字 3 字节计不超过 messages.content（TEXT, 64KB）。 */
    private static final int EVIDENCE_DIGEST_MAX_CHARS = 18000;
    /** 在证据门禁通过后执行有界取证循环补证。 */
    private final DeepEvidenceReplanService evidenceReplanService;
    /** 把结构化报告或证据不足状态渲染为用户 Markdown。 */
    private final ReportMarkdownRenderer reportRenderer;
    /** 在发布事务中按归属和幂等契约写入最终助手消息。 */
    private final ConversationMessageService conversationMessageService;
    /** 原子提交报告、消息和任务终态。 */
    private final ResearchTaskPublicationTransaction publicationTransaction;
    /** 向已订阅 SSE/Trace 发布后台进度和最终文本。 */
    private final ChatStreamEmitter chatStreamEmitter;
    /** 仅在任务真正终态后关闭后台任务 trace。 */
    private final TraceService traceService;

    private final ResearchEvidenceSnapshotService evidenceSnapshotService;
    /** worker 侧的归属校验与带归属校验的写入。 */
    private final TaskOwnershipFence fence;

    /**
     * 在 worker 内运行完整 DEEP 管线；请求线程只负责创建任务和订阅事件。
     *
     * @param task 已落库 PENDING 任务或由 worker fenced 接管的 RUNNING 任务
     * @param lease 当前执行租约，token 将贯穿所有 owner-fenced 写入
     */
    public void runFullPipeline(ResearchTask task, ResearchTaskLeaseService.Lease lease) {
        long pipelineStartedAt = System.currentTimeMillis();
        Attempt attempt = new Attempt(task, lease);

        try {
            startOrTakeOver(attempt);
            ResumePoint resume = loadResumePoint(attempt);
            if (!runEvidencePhase(attempt, resume)) {
                return;
            }
            runReportPhase(attempt, resume);
        } catch (OwnershipLostException error) {
            log.info("Research task stopped after ownership loss, taskId={}, error={}",
                    attempt.task == null ? null : attempt.task.getId(), error.getMessage());
            throw error;
        } catch (ResearchTaskCheckpointService.CheckpointFormatException
                 | ResearchEvidenceSnapshotService.SnapshotException
                 | ResearchBudgetExceededException
                 | ResearchCapacityExceededException error) {
            // 执行配置或持久化决定无法恢复时，保留快照，禁止重采集或发布离线替代报告。
            fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
            if (!researchTaskService.markFailedForOwner(attempt.runningTask, attempt.lease.token(), error.getMessage())) {
                attempt.ownershipLost.set(true);
                throw new OwnershipLostException(
                        "research task lost ownership before recovery contract failure update", error);
            }
            chatStreamEmitter.emit(attempt.payload.traceId(), attempt.payload.conversationId(), "error", error.getMessage());
        } catch (Exception error) {
            handleAttemptFailure(attempt, error);
        } finally {
            if (attempt.heartbeat != null) {
                attempt.heartbeat.cancel(false);
            }
            try {
                researchTaskService.release(attempt.lease);
            } finally {
                endTerminalTaskTraceBestEffort(
                        attempt.runningTask,
                        attempt.payload,
                        System.currentTimeMillis() - pipelineStartedAt
                );
            }
        }
    }

    /** 单次后台执行的可变进度；失败处理和 finally 读取的是异常发生那一刻的值。 */
    private static final class Attempt {
        private final ResearchTask task;
        private final ResearchTaskLeaseService.Lease lease;
        private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
        private SubmissionPayload payload;
        private ResearchTask runningTask;
        private AnalysisState workingState;
        private ScheduledFuture<?> heartbeat;
        private boolean attemptStarted;

        private Attempt(ResearchTask task, ResearchTaskLeaseService.Lease lease) {
            this.task = task;
            this.lease = lease;
            this.runningTask = task;
        }
    }

    /** 断点恢复位置：从 checkpoint 推出的恢复边界和已完成、已授权的辩论轮次。 */
    private record ResumePoint(
            Optional<ResearchTaskCheckpointService.CheckpointState> checkpoint,
            boolean checkpointedReportArtifact,
            boolean resumeFromEvidenceBoundary,
            boolean resumingReportRecovery,
            int roundsDone,
            int authorizedThroughRound
    ) {
    }

    /** 校验租约，启动 PENDING 任务或确认 RUNNING 接管权，并开始心跳。 */
    private void startOrTakeOver(Attempt attempt) {
        attempt.payload = parsePayload(attempt.task);
        // PENDING 任务尚无数据库 owner，startAttempt 前只能先校验 Redis 租约所有权。
        fence.requireLeaseOwnership(attempt.task, attempt.lease, attempt.ownershipLost);
        if (attempt.task.getStatus() == ResearchTask.Status.RUNNING) {
            if (!attempt.lease.token().equals(attempt.task.getLeaseToken())) {
                attempt.ownershipLost.set(true);
                throw new OwnershipLostException(
                        "RUNNING research task is not owned by the supplied lease");
            }
            attempt.runningTask = attempt.task;
        } else {
            attempt.runningTask = researchTaskService.startAttempt(
                    attempt.task, attempt.lease.token(), ResearchTask.Stage.DATA_PREFETCH);
        }
        attempt.attemptStarted = true;
        fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
        attempt.heartbeat = fence.startResearchTaskHeartbeat(attempt.runningTask, attempt.lease, attempt.ownershipLost);
        checkDeadline(attempt.runningTask);
    }

    /** 取得执行权后读取 checkpoint，确定恢复边界与辩论轮次。 */
    private ResumePoint loadResumePoint(Attempt attempt) {
        // 取得 PENDING 启动权或 RUNNING 接管权后再读 checkpoint，避免旧 worker 越权恢复。
        Optional<ResearchTaskCheckpointService.CheckpointState> checkpoint =
                checkpointService.load(attempt.task.getId());
        attempt.workingState = checkpoint.map(ResearchTaskCheckpointService.CheckpointState::state).orElse(null);
        if (attempt.workingState != null) {
            // 提交载荷是请求条件的权威来源，不能沿用 checkpoint 中其它尝试的时间要求。
            attempt.workingState.setTimeSensitivity(attempt.payload.timeSensitivity());
            bindInvocationContext(attempt.workingState, attempt.runningTask, attempt.lease, attempt.payload.traceId());
        }
        boolean checkpointedReportArtifact = attempt.workingState != null
                && attempt.workingState.getInvestmentReport() != null;
        boolean resumeFromEvidenceBoundary = checkpoint
                .map(this::isEvidenceBoundaryCheckpoint)
                .orElse(false);
        boolean resumingReportRecovery = hasPendingReportRecovery(attempt.workingState);

        int roundsDone = checkpoint
                .map(ResearchTaskCheckpointService.CheckpointState::debateRoundsCompleted)
                .orElse(0);
        int authorizedThroughRound = checkpoint
                .map(ResearchTaskCheckpointService.CheckpointState::plannedRounds)
                .orElse(0);
        if (resumingReportRecovery) {
            // 旧同步 checkpoint 的列仍是 DATA_PREFETCH/0；从成对辩论记录恢复轮次，接管后直接进入 Manager。
            int durableRounds = completedDebateRounds(attempt.workingState);
            roundsDone = Math.max(roundsDone, durableRounds);
            authorizedThroughRound = roundsDone;
        }
        return new ResumePoint(checkpoint, checkpointedReportArtifact, resumeFromEvidenceBoundary,
                resumingReportRecovery, roundsDone, authorizedThroughRound);
    }

    /**
     * 证据阶段：首次收集或按断点复验、有界恢复与补证，未通过门禁或可复用已有报告时直接发布。
     *
     * @return false 表示本次执行已在证据阶段结束
     */
    private boolean runEvidencePhase(Attempt attempt, ResumePoint resume) {
        DeepEvidenceCollector.EvidenceCollection evidence = null;
        Map<RecoveryAction, Integer> recoveryAttempts = Map.of();
        if (resume.checkpoint().isEmpty()) {
            fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
            chatStreamEmitter.emit(attempt.payload.traceId(), attempt.payload.conversationId(), "thought",
                    "开始收集深度研究证据……");
            // 调用证据收集器建立结构化 ledger；后续辩论不得绕过 Harness 决定。
            var collectionPayload = attempt.payload;
            String collectionUserId = attempt.runningTask.getUserId();
            evidence = ToolCallContext.withRunExecution(
                    new ToolCallContext.RunExecution(attempt.runningTask.getId(), attempt.runningTask.getAttempts(), attempt.lease.token(),
                            attempt.payload.traceId(), attempt.runningTask.getBudgetDeadlineEpochMs()),
                    () -> evidenceCollector.collect(
                    collectionPayload.ticker(), collectionPayload.query(), collectionPayload.traceId(),
                    collectionPayload.conversationId(), collectionPayload.timeSensitivity(), collectionUserId));
            checkDeadline(attempt.runningTask);
        } else {
            fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
            recoveryAttempts = checkpointRecoveryAttempts(attempt.workingState);
            chatStreamEmitter.emit(attempt.payload.traceId(), attempt.payload.conversationId(), "observation",
                    "从持久化断点恢复研究任务：重新执行当前证据门禁与未完成的恢复动作。");
            evidence = evidenceCollector.reevaluateCheckpoint(
                    attempt.workingState,
                    recoveryAttempts,
                    attempt.payload.traceId()
            );
        }

        if (evidence != null) {
            attempt.workingState = evidence.state();
            evidence = executeEvidenceRecovery(
                    attempt.runningTask,
                    attempt.lease,
                    attempt.payload,
                    evidence,
                    recoveryAttempts,
                    attempt.ownershipLost
            );
            attempt.workingState = evidence.state();
            bindInvocationContext(attempt.workingState, attempt.runningTask, attempt.lease, attempt.payload.traceId());
            if ((resume.checkpoint().isEmpty() || resume.resumeFromEvidenceBoundary())
                    && !resume.resumingReportRecovery()) {
                ResearchTask ownedTask = attempt.runningTask;
                evidence = evidenceReplanService.replan(
                        attempt.runningTask.getId(),
                        attempt.task.getUserId(),
                        attempt.payload.conversationId(),
                        attempt.payload.traceId(),
                        evidence,
                        () -> fence.requireTaskOwnership(ownedTask, attempt.lease, attempt.ownershipLost),
                        state -> fence.saveEvidenceReplanForOwner(
                                ownedTask, attempt.lease, state, attempt.ownershipLost)
                );
                attempt.workingState = evidence.state();
            }
            bindInvocationContext(attempt.workingState, attempt.runningTask, attempt.lease, attempt.payload.traceId());
            if (evidence.harnessDecision().outcome() != HarnessOutcome.PASS) {
                String text = reportRenderer.buildInsufficientEvidenceReport(
                        attempt.payload.ticker(), evidence.tickerResolved(), evidence.fundamentalsOk(), evidence.marketOk());
                ResearchTask.ResultKind resultKind =
                        evidence.harnessDecision().outcome() == HarnessOutcome.BLOCK
                                ? ResearchTask.ResultKind.POLICY_BLOCKED
                                : ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE;
                finishWithText(
                        attempt.runningTask, attempt.lease, attempt.payload, text, null, resultKind, attempt.ownershipLost);
                return false;
            }
            if (attempt.payload.evidenceOnly()) {
                // 评测专用：证据阶段完成后结束，不调用辩论、Manager 和报告模型。
                finishWithText(attempt.runningTask, attempt.lease, attempt.payload,
                        evidenceCollector.evidenceDigest(attempt.workingState, EVIDENCE_DIGEST_MAX_CHARS), null,
                        ResearchTask.ResultKind.EVIDENCE_ONLY, attempt.ownershipLost);
                return false;
            }

            // checkpoint 哈希仅是历史元数据；当前策略通过后，复用与持久化必须重新绑定当前证据账本和策略。
            investmentReportVersionService.prepareHashes(attempt.workingState);

            if (resume.checkpoint().isEmpty() || resume.resumeFromEvidenceBoundary()) {
                fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
                checkDeadline(attempt.runningTask);
                // 调用版本服务复用同快照报告，但仍需当前策略和人工审核允许。
                Optional<InvestmentReportVersionService.PersistedReportVersion> reusable =
                        investmentReportVersionService.findReusableReport(
                                attempt.task.getUserId(), attempt.payload.conversationId(), attempt.workingState);
                if (reusable.isPresent()) {
                    attempt.workingState.setInvestmentReport(reusable.get().report());
                    finishWithText(attempt.runningTask, attempt.lease, attempt.payload,
                            reportRenderer.buildFinalAnswerBrief(attempt.workingState), reusable.get().reportVersionId(),
                            ResearchTask.ResultKind.FULL_REPORT, attempt.ownershipLost);
                    return false;
                }

                fence.saveEvidenceForOwner(attempt.runningTask, attempt.lease, attempt.workingState, attempt.ownershipLost);
                fence.markStageForOwner(
                        attempt.runningTask, attempt.lease, ResearchTask.Stage.AGENT_DEBATE, attempt.ownershipLost);
            } else {
                fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
                chatStreamEmitter.emit(attempt.payload.traceId(), attempt.payload.conversationId(), "observation",
                        "证据门禁复验通过：继续恢复已完成 "
                                + resume.roundsDone() + " 轮的研究断点。");
            }
        }
        return true;
    }

    /** 报告阶段：复验断点报告或运行辩论，再按报告质量发布完整报告或不评级说明。 */
    private void runReportPhase(Attempt attempt, ResumePoint resume) {
        if (resume.checkpointedReportArtifact() && attempt.workingState.getInvestmentReport() != null) {
            fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
            ResearchTask ownedTask = attempt.runningTask;
            attempt.workingState = researchDebateService.revalidateCheckpointedReport(
                    attempt.payload.traceId(),
                    attempt.payload.conversationId(),
                    attempt.workingState,
                    fence.ownershipGuard(ownedTask, attempt.ownershipLost),
                    state -> fence.saveHarnessSnapshotForOwner(
                            ownedTask, attempt.lease, state, attempt.ownershipLost)
            );
            fence.saveSynthesisForOwner(attempt.runningTask, attempt.lease, attempt.workingState, attempt.ownershipLost);
            fence.markStageForOwner(
                    attempt.runningTask, attempt.lease, ResearchTask.Stage.REPORT_SYNTHESIS, attempt.ownershipLost);
        }

        if (attempt.workingState.getInvestmentReport() == null) {
            fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
            ResearchTask ownedTask = attempt.runningTask;
            AnalysisState completed = runDebateAndCheckpoint(
                    ownedTask, attempt.lease, attempt.payload.traceId(), attempt.payload.conversationId(), attempt.workingState,
                    resume.roundsDone() + 1, resume.authorizedThroughRound(), attempt.ownershipLost);
            attempt.workingState = completed;
        }

        if (attempt.workingState.getInvestmentReport() == null
                || attempt.workingState.getInvestmentReport().getQualityStatus()
                != InvestmentReport.ReportQualityStatus.VERIFIED) {
            String notRated = reportRenderer.buildFinalAnswerBrief(attempt.workingState);
            finishWithText(
                    attempt.runningTask,
                    attempt.lease,
                    attempt.payload,
                    notRated,
                    null,
                    ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE,
                    attempt.ownershipLost
            );
            return;
        }

        fence.markStageForOwner(attempt.runningTask, attempt.lease, ResearchTask.Stage.REPORT_PERSIST, attempt.ownershipLost);
        finishWithPublishedReport(
                attempt.runningTask,
                attempt.lease,
                attempt.payload,
                attempt.workingState,
                ModelTier.STRONG.name(),
                null,
                ResearchTask.ResultKind.FULL_REPORT,
                attempt.ownershipLost
        );
    }

    /** 非契约类失败：预算/容量失败直接结束，否则尝试离线兜底报告，兜底也失败才标记任务失败。 */
    private void handleAttemptFailure(Attempt attempt, Exception error) {
        try {
            ResearchBudgetExceededException.rethrowIfPresent(error);
            ResearchCapacityExceededException.rethrowIfPresent(error);
            if (attempt.attemptStarted) checkDeadline(attempt.runningTask);
        } catch (ResearchBudgetExceededException | ResearchCapacityExceededException budget) {
            fence.failExecutionConstraintForOwner(attempt.runningTask, attempt.lease, attempt.ownershipLost, budget);
            if (attempt.payload != null) chatStreamEmitter.emit(attempt.payload.traceId(), attempt.payload.conversationId(), "error", budget.getMessage());
            return;
        }
        if (!attempt.attemptStarted) {
            boolean failed = researchTaskService.markFailedIfPending(attempt.task, error.getMessage());
            if (!failed) {
                attempt.ownershipLost.set(true);
                throw new OwnershipLostException(
                        "research task left pending state before pre-attempt failure update",
                        error
                );
            }
            if (attempt.payload != null) {
                chatStreamEmitter.emit(attempt.payload.traceId(), attempt.payload.conversationId(), "error",
                        "深度研究任务执行失败：" + safeError(error));
            }
            return;
        }

        fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
        ResearchTask fallbackTask = attempt.runningTask;
        SubmissionPayload fallbackPayload = attempt.payload;
        AnalysisState fallbackState = attempt.workingState;
        Optional<PersistedFallback> fallback;
        try {
            fallback = fallbackPayload == null
                ? Optional.empty()
                : publicationTransaction.executeForUser(attempt.task.getUserId(), () -> {
                    Optional<PersistedFallback> candidate = persistOfflineFallbackReport(
                            attempt.task.getUserId(),
                            fallbackPayload.conversationId(),
                            fallbackState,
                            fallbackTask,
                            attempt.lease,
                            error,
                            attempt.ownershipLost
                    );
                    if (candidate.isEmpty()) {
                        return Optional.empty();
                    }
                    PersistedFallback persisted = candidate.orElseThrow();
                    String brief = reportRenderer.buildFinalAnswerBrief(fallbackState);
                    String text = brief == null || brief.isBlank()
                            ? persisted.reportJson()
                            : brief;
                    conversationMessageService.persistAssistantReport(
                            fallbackPayload.conversationId(),
                            fallbackTask.getUserId(),
                            text,
                            fallbackPayload.traceId()
                    );
                    fence.markSucceededForOwner(
                            fallbackTask,
                            attempt.lease,
                            persisted.reportVersionId(),
                            ResearchTask.ResultKind.OFFLINE_FALLBACK,
                            attempt.ownershipLost
                    );
                    return Optional.of(new PersistedFallback(
                            persisted.reportJson(),
                            persisted.reportVersionId(),
                            text
                    ));
                });
        } catch (ResearchBudgetExceededException | ResearchCapacityExceededException budget) {
            fence.failExecutionConstraintForOwner(attempt.runningTask, attempt.lease, attempt.ownershipLost, budget);
            if (attempt.payload != null) chatStreamEmitter.emit(attempt.payload.traceId(), attempt.payload.conversationId(), "error", budget.getMessage());
            return;
        }
        if (fallback.isPresent()) {
            PersistedFallback persistedFallback = fallback.get();
            deleteCompletedCheckpointBestEffort(attempt.runningTask);
            chatStreamEmitter.emit(
                    attempt.payload.traceId(),
                    attempt.payload.conversationId(),
                    "task-final",
                    persistedFallback.deliveryText()
            );
            return;
        }

        fence.requireTaskOwnership(attempt.runningTask, attempt.lease, attempt.ownershipLost);
        boolean failed = researchTaskService.markFailedForOwner(
                attempt.runningTask, attempt.lease.token(), error.getMessage());
        if (failed && attempt.payload != null) {
            chatStreamEmitter.emit(attempt.payload.traceId(), attempt.payload.conversationId(), "error",
                    "深度研究任务执行失败：" + safeError(error));
        }
        if (!failed) {
            attempt.ownershipLost.set(true);
            throw new OwnershipLostException(
                    "research task lost ownership before failure update", error);
        }
    }

    /**
     * 仅由后台任务终态结束对应 trace。
     *
     * <p>SSE 订阅断开时持久化任务仍可能运行，请求流不能据此记录取消。
     * 本流水线仅关闭自己持有的终态任务 trace；丢失所有权时留给接管 worker 关闭。</p>
     */
    private void endTerminalTaskTraceBestEffort(
            ResearchTask task,
            SubmissionPayload payload,
            long durationMs
    ) {
        if (task == null
                || payload == null
                || payload.traceId() == null
                || payload.traceId().isBlank()) {
            return;
        }
        String status;
        if (task.getStatus() == ResearchTask.Status.SUCCEEDED) {
            status = "success";
        } else if (task.getStatus() == ResearchTask.Status.FAILED) {
            status = "error";
        } else {
            return;
        }
        try {
            traceService.endTrace(
                    payload.traceId(),
                    status,
                    0,
                    Math.max(0, durationMs),
                    taskOutcome(task)
            );
        } catch (Exception error) {
            // 业务终态已经提交；可观测性更新失败不能触发研究重放或重复发布。
            log.warn(
                    "Research task terminal trace update failed, taskId={}, traceId={}, status={}, error={}",
                    task.getId(),
                    payload.traceId(),
                    status,
                    error.getMessage()
            );
        }
    }

    /** 将持久化任务终态映射为独立的业务结果，不把技术成功等同于完整报告。 */
    public String taskOutcome(ResearchTask task) {
        if (task.getStatus() == ResearchTask.Status.FAILED) {
            return "FAILED";
        }
        ResearchTask.ResultKind resultKind = task.getResultKind();
        if (resultKind == null) {
            return null;
        }
        return switch (resultKind) {
            case FULL_REPORT -> "COMPLETED";
            // EVIDENCE_ONLY 只在评测请求下出现，没有报告，不能算 COMPLETED。
            case INSUFFICIENT_EVIDENCE, OFFLINE_FALLBACK, EVIDENCE_ONLY -> "DEGRADED";
            case POLICY_BLOCKED -> "BLOCKED";
        };
    }

    private void finishWithText(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            SubmissionPayload payload,
            String text,
            Long reportVersionId,
            ResearchTask.ResultKind resultKind,
            AtomicBoolean ownershipLost
    ) {
        fence.requireTaskOwnership(task, lease, ownershipLost);
        // 在用户行锁事务内同时写消息和 owner-fenced 终态，任一步失败则整体回滚。
        publicationTransaction.executeForUser(task.getUserId(), () -> {
            checkDeadline(task);
            conversationMessageService.persistAssistantReport(
                    payload.conversationId(), task.getUserId(), text, payload.traceId());
            fence.markSucceededForOwner(task, lease, reportVersionId, resultKind, ownershipLost);
        });
        deleteCompletedCheckpointBestEffort(task);
        chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "task-final", text);
    }

    private void finishWithPublishedReport(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            SubmissionPayload payload,
            AnalysisState state,
            String modelTier,
            String modelName,
            ResearchTask.ResultKind resultKind,
            AtomicBoolean ownershipLost
    ) {
        fence.requireTaskOwnership(task, lease, ownershipLost);
        // 报告版本、最终消息和任务终态在同一用户发布锁/事务下提交。
        String text = publicationTransaction.executeForUser(task.getUserId(), () -> {
            checkDeadline(task);
            InvestmentReportVersionService.PersistedReportVersion persisted =
                    investmentReportVersionService.persistReportVersionWithMetadata(
                            task.getUserId(),
                            payload.conversationId(),
                            state,
                            modelTier,
                            modelName
                    );
            // 同快照发布可能改用已提交的规范报告，因此须在用户锁内完成裁决后再渲染。
            String committedText = reportRenderer.buildFinalAnswerBrief(state);
            conversationMessageService.persistAssistantReport(
                    payload.conversationId(),
                    task.getUserId(),
                    committedText,
                    payload.traceId()
            );
            fence.markSucceededForOwner(
                    task, lease, persisted.reportVersionId(), resultKind, ownershipLost);
            return committedText;
        });
        deleteCompletedCheckpointBestEffort(task);
        chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "task-final", text);
    }

    private void deleteCompletedCheckpointBestEffort(ResearchTask task) {
        try {
            // 评测任务保留结构化辩论等断点状态，供复盘和冻结证据回放。
            if (parsePayload(task).evaluation()) return;
            checkpointService.deleteForCompletedTask(task.getId());
        } catch (Exception error) {
            log.warn("Completed research task checkpoint cleanup failed, taskId={}, error={}",
                    task.getId(), error.getMessage());
        }
    }

    private SubmissionPayload parsePayload(ResearchTask task) {
        try {
            JsonNode root = objectMapper.readTree(task.getPayloadJson());
            String ticker = root.path("ticker").asText(task.getTicker());
            String query = root.path("query").asText("");
            String traceId = root.path("traceId").asText("");
            Long conversationId = root.hasNonNull("conversationId")
                    ? root.path("conversationId").asLong()
                    : task.getConversationId();
            TimeSensitivity timeSensitivity = TimeSensitivity.UNSPECIFIED;
            JsonNode timeRequirement = root.path("timeSensitivity");
            if (timeRequirement.isTextual()) {
                try {
                    timeSensitivity = TimeSensitivity.valueOf(timeRequirement.textValue());
                } catch (IllegalArgumentException ignored) {
                    // 旧任务或未知枚举值不能升级成已确认的请求条件。
                }
            }
            String evaluationMode = root.path("evaluationMode").asText("");
            return new SubmissionPayload(ticker, query, traceId, conversationId, timeSensitivity,
                    evaluationMode.isBlank() ? null : evaluationMode);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid research task submission payload", e);
        }
    }

    private String safeError(Exception error) {
        return error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
    }

    /** @param evaluationMode 评测专用的请求级模式，普通任务为 null */
    private record SubmissionPayload(String ticker, String query, String traceId, Long conversationId,
                                     TimeSensitivity timeSensitivity, String evaluationMode) {
        /** 证据阶段完成后结束。 */
        boolean evidenceOnly() {
            return "EVIDENCE_ONLY".equals(evaluationMode);
        }

        /** 评测任务：保存调用原文，并保留 checkpoint 供复盘和回放。 */
        boolean evaluation() {
            return evaluationMode != null;
        }
    }

    /**
     * 队列不可用时在请求内执行研究；复用研究阶段，结果交由聊天入口发送。
     * 已有任务或所有权丢失时仅返回观察身份，不接管其他 owner，也不发布后台 task-final。
     */
    public InlineResult runInlineFallback(
            String primaryTicker,
            String userQuery,
            String traceId,
            Long conversationId,
            String userId,
            Coordinator.SelectedModel selectedModel,
            ResearchTask task,
            TimeSensitivity timeSensitivity,
            String initialContext
    ) {
        StringBuilder context = new StringBuilder(initialContext);
        chatStreamEmitter.emit(traceId, conversationId, "thought",
                "任务队列暂不可用，正在请求内同步完成深度研究。");
        Optional<ResearchTaskLeaseService.Lease> lease = researchTaskService.tryAcquire(task);
        if (lease.isEmpty()) {
            return new InlineResult(
                    context.toString().trim(), reportRenderer.buildTaskAcceptedAnswer(task),
                    task.getId(), taskTraceId(task, traceId), null);
        }
        ResearchTaskLeaseService.Lease acquired = lease.orElseThrow();
        try {
            if (!researchTaskService.renewLease(acquired)) {
                researchTaskService.release(acquired);
                log.info("Inline research lease expired before DB start; switching to task "
                        + "observation, taskId={}", task.getId());
                return observeInlineTask(context, task, traceId);
            }
        } catch (RuntimeException leaseError) {
            researchTaskService.release(acquired);
            log.info("Inline research lease validation failed before DB start; switching to task "
                    + "observation, taskId={}, error={}", task.getId(), leaseError.getMessage());
            return observeInlineTask(context, task, traceId);
        }
        ResearchTask runningTask;
        try {
            runningTask = researchTaskService.startAttempt(
                    task, acquired.token(), ResearchTask.Stage.DATA_PREFETCH);
        } catch (IllegalStateException ownershipRace) {
            researchTaskService.release(acquired);
            log.info("Inline research start was fenced; switching to task observation, taskId={}, error={}",
                    task.getId(), ownershipRace.getMessage());
            return observeInlineTask(context, task, traceId);
        } catch (RuntimeException error) {
            researchTaskService.release(acquired);
            throw error;
        }
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = fence.startResearchTaskHeartbeat(runningTask, acquired, ownershipLost);
        try {
            requireInlineOwnership(runningTask, acquired, ownershipLost, "evidence prefetch");
            checkDeadline(runningTask);
            DeepEvidenceCollector.EvidenceCollection evidence = ToolCallContext.withRunExecution(
                    new ToolCallContext.RunExecution(runningTask.getId(), runningTask.getAttempts(), acquired.token(),
                            traceId, runningTask.getBudgetDeadlineEpochMs()),
                    () -> evidenceCollector.collect(primaryTicker, userQuery, traceId, conversationId, timeSensitivity,
                            runningTask.getUserId()));
            bindInvocationContext(evidence.state(), runningTask, acquired, traceId);
            if (evidence.harnessDecision().outcome() == HarnessOutcome.RECOVER) {
                List<RecoveryAction> recoveryActions = evidence.harnessDecision().recoveryActions();
                Map<RecoveryAction, Integer> completedAttempts =
                        incrementRecoveryAttempts(Map.of(), recoveryActions);
                evidence.state().setHarnessSnapshot(
                        evidenceSnapshot(evidence.harnessDecision(), completedAttempts));
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "planned recovery checkpoint");
                saveInlineHarnessSnapshot(runningTask, acquired, evidence.state());
                recordEvidenceRecoveryExecution(
                        traceId, recoveryActions, completedAttempts);
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "evidence recovery");
                DeepEvidenceCollector.EvidenceCollection recoveryInput = evidence;
                evidence = ToolCallContext.withRunExecution(
                        new ToolCallContext.RunExecution(runningTask.getId(), runningTask.getAttempts(), acquired.token(),
                                traceId, runningTask.getBudgetDeadlineEpochMs()),
                        () -> evidenceCollector.recover(
                        recoveryInput,
                        recoveryActions,
                        Map.of(),
                        traceId,
                        conversationId,
                        runningTask.getUserId()
                ));
                evidence.state().setHarnessSnapshot(
                        evidenceSnapshot(evidence.harnessDecision(), completedAttempts));
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "revalidated recovery checkpoint");
                saveInlineHarnessSnapshot(runningTask, acquired, evidence.state());
            }
            ResearchTask ownedTask = runningTask;
            bindInvocationContext(evidence.state(), runningTask, acquired, traceId);
            evidence = evidenceReplanService.replan(
                    runningTask.getId(),
                    userId,
                    conversationId,
                    traceId,
                    evidence,
                    () -> requireInlineOwnership(
                            ownedTask, acquired, ownershipLost, "evidence replan"),
                    state -> saveInlineEvidenceReplan(ownedTask, acquired, state)
            );
            context.append(evidenceCollector.evidenceDigest(evidence.state(), EVIDENCE_DIGEST_MAX_CHARS)).append("\n\n");
            AnalysisState agentState = evidence.state();
            bindInvocationContext(agentState, runningTask, acquired, traceId);
            if (evidence.harnessDecision().outcome() != HarnessOutcome.PASS) {
                String answer = reportRenderer.buildInsufficientEvidenceReport(
                        primaryTicker,
                        evidence.tickerResolved(),
                        evidence.fundamentalsOk(),
                        evidence.marketOk()
                );
                ResearchTask.ResultKind resultKind =
                        evidence.harnessDecision().outcome() == HarnessOutcome.BLOCK
                                ? ResearchTask.ResultKind.POLICY_BLOCKED
                                : ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE;
                completeInlineTaskForOwner(
                        runningTask, acquired, ownershipLost, null, resultKind);
                return new InlineResult(
                        context.toString().trim(), answer, null, traceId,
                        taskOutcome(runningTask));
            }
            if (parsePayload(task).evidenceOnly()) {
                // 评测专用：与后台路径一致，证据阶段完成后结束。
                completeInlineTaskForOwner(
                        runningTask, acquired, ownershipLost, null, ResearchTask.ResultKind.EVIDENCE_ONLY);
                return new InlineResult(
                        context.toString().trim(), evidenceCollector.evidenceDigest(agentState, EVIDENCE_DIGEST_MAX_CHARS),
                        null, traceId, taskOutcome(runningTask));
            }

            investmentReportVersionService.prepareHashes(agentState);
            checkDeadline(runningTask);
            Optional<InvestmentReportVersionService.PersistedReportVersion> reusableReport = investmentReportVersionService.findReusableReport(
                    userId, conversationId, agentState);
            if (reusableReport.isPresent()) {
                InvestmentReport report = reusableReport.orElseThrow().report();
                agentState.setInvestmentReport(report);
                completeInlineTaskForOwner(
                        runningTask,
                        acquired,
                        ownershipLost,
                        reusableReport.orElseThrow().reportVersionId(),
                        ResearchTask.ResultKind.FULL_REPORT
                );
                return new InlineResult(
                        context.toString().trim(), reportRenderer.buildFinalAnswerBrief(agentState), null, traceId,
                        taskOutcome(runningTask));
            }

            requireInlineOwnership(runningTask, acquired, ownershipLost, "debate start");
            chatStreamEmitter.emit(traceId, conversationId, "thought",
                    "开始 Bull/Bear 辩论，并由 Research Manager 综合裁决。");
            if (heartbeat != null) {
                heartbeat.cancel(false);
                heartbeat = null;
            }
            String reportJson;
            try {
                reportJson = runResearchDebateWithTask(
                        traceId,
                        conversationId,
                        userId,
                        agentState,
                        selectedModel,
                        runningTask,
                        acquired
                );
            } catch (OwnershipLostException error) {
                throw error;
            } catch (Exception error) {
                ResearchBudgetExceededException.rethrowIfPresent(error);
                ResearchCapacityExceededException.rethrowIfPresent(error);
                throw new IllegalStateException("inline DEEP research failed", error);
            }
            if (agentState.getInvestmentReport() == null && reportJson != null && !reportJson.isBlank()) {
                try {
                    agentState.setInvestmentReport(objectMapper.readValue(reportJson, InvestmentReport.class));
                } catch (JsonProcessingException error) {
                    log.warn("Inline DEEP report could not be deserialized for rendering: {}", error.getMessage());
                }
            }
            String answer = agentState.getInvestmentReport() == null
                    ? reportJson
                    : reportRenderer.buildFinalAnswerBrief(agentState);
            return new InlineResult(
                    context.toString().trim(), answer, null, traceId,
                    taskOutcome(runningTask));
        } catch (OwnershipLostException error) {
            log.info("Inline research stopped after ownership loss; switching to task observation, "
                    + "taskId={}, error={}", runningTask.getId(), error.getMessage());
            return observeInlineTask(context, runningTask, traceId);
        } catch (RuntimeException error) {
            try {
                ResearchBudgetExceededException.rethrowIfPresent(error);
                ResearchCapacityExceededException.rethrowIfPresent(error);
            } catch (ResearchBudgetExceededException | ResearchCapacityExceededException budget) {
                error = budget;
            }
            boolean provenanceFailure = error instanceof ResearchEvidenceSnapshotService.SnapshotException
                    || error instanceof ResearchBudgetExceededException
                    || error instanceof ResearchCapacityExceededException;
            String failureMessage = provenanceFailure ? error.getMessage() : "同步深度研究执行失败，本次任务已经结束。";
            if (runningTask.getStatus() == ResearchTask.Status.FAILED) {
                chatStreamEmitter.emit(traceId, conversationId, "error", failureMessage);
                return new InlineResult(
                        context.toString().trim(), provenanceFailure ? failureMessage : reportRenderer.buildResearchFailedAnswer(), null, traceId,
                        taskOutcome(runningTask));
            }
            try {
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "failure publication");
            } catch (RuntimeException ownershipProofError) {
                log.info("Inline research failure could not be published by the current owner; "
                                + "switching to task observation, taskId={}, failure={}, fence={}",
                        runningTask.getId(), error.getMessage(), ownershipProofError.getMessage());
                return observeInlineTask(context, runningTask, traceId);
            }
            boolean failed =
                    researchTaskService.markFailedForOwner(
                            runningTask, acquired.token(), error.getMessage());
            if (failed) {
                chatStreamEmitter.emit(traceId, conversationId, "error",
                        failureMessage);
                return new InlineResult(
                        context.toString().trim(), provenanceFailure
                                ? failureMessage : reportRenderer.buildResearchFailedAnswer(), null, traceId,
                        taskOutcome(runningTask));
            }
            log.info("Inline research failure arrived after ownership changed; switching to task "
                    + "observation, taskId={}, error={}", runningTask.getId(), error.getMessage());
            return observeInlineTask(context, runningTask, traceId);
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            researchTaskService.release(acquired);
        }
    }

    private void requireInlineOwnership(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost,
            String operation
    ) {
        if (ownershipLost.get()) {
            throw inlineOwnershipLost(task, operation, null);
        }
        boolean leaseRenewed = researchTaskService.renewLease(lease);
        boolean databaseOwner = leaseRenewed
                && researchTaskService.heartbeatForOwner(task, lease.token());
        if (!databaseOwner) {
            ownershipLost.set(true);
            throw inlineOwnershipLost(task, operation, null);
        }
    }

    private void saveInlineHarnessSnapshot(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state
    ) {
        try {
            checkpointService.saveHarnessSnapshot(
                    task.getId(), lease.token(), state);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw inlineOwnershipLost(task, "harness checkpoint", error);
        }
    }

    private void saveInlineEvidenceReplan(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state
    ) {
        try {
            checkpointService.saveEvidenceReplan(
                    task.getId(), lease.token(), state);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw new OwnershipLostException(
                    "inline research lost ownership before evidence replan checkpoint, taskId="
                            + task.getId(),
                    error
            );
        }
    }

    private void completeInlineTaskForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost,
            Long reportVersionId,
            ResearchTask.ResultKind resultKind
    ) {
        requireInlineOwnership(task, lease, ownershipLost, "terminal completion");
        checkDeadline(task);
        try {
            researchTaskService.markSucceededForOwner(
                    task, lease.token(), reportVersionId, resultKind);
        } catch (IllegalStateException error) {
            throw inlineOwnershipLost(task, "terminal completion", error);
        }
    }

    private OwnershipLostException inlineOwnershipLost(
            ResearchTask task,
            String operation,
            Throwable cause
    ) {
        String message = "inline research task ownership was lost before "
                + operation + ", taskId=" + (task == null ? null : task.getId());
        return cause == null
                ? new OwnershipLostException(message)
                : new OwnershipLostException(message, cause);
    }

    private InlineResult observeInlineTask(
            StringBuilder context,
            ResearchTask task,
            String traceId
    ) {
        return new InlineResult(
                context.toString().trim(),
                reportRenderer.buildTaskAcceptedAnswer(task),
                task.getId(),
                taskTraceId(task, traceId),
                null
        );
    }

    /** 提交入口观察已有任务时复用其持久 Trace 身份，避免把聊天观察者当成任务执行者。 */
    public String taskTraceId(ResearchTask task, String fallbackTraceId) {
        if (task == null || task.getPayloadJson() == null || task.getPayloadJson().isBlank()) {
            return fallbackTraceId;
        }
        try {
            String storedTraceId = objectMapper.readTree(task.getPayloadJson()).path("traceId").asText("").trim();
            return storedTraceId.isBlank() ? fallbackTraceId : storedTraceId;
        } catch (JsonProcessingException error) {
            log.warn("Unable to read traceId from research task payload, taskId={}: {}",
                    task.getId(), error.getMessage());
            return fallbackTraceId;
        }
    }

    /** 同步研究返回给调用方的交付结果；观察任务时 outcome 为空。 */
    public record InlineResult(String context, String directAnswer, Long submittedTaskId,
                               String eventTraceId, String taskOutcome) {
    }

    /**
     * 在研究任务的租约保护下运行 Bull/Bear 辩论，并把结果持久化为报告版本。
     *
     * <p>辩论失败时优先尝试离线兜底报告；兜底也失败才把任务标记为失败并向上抛出。</p>
     *
     * @param traceId 研究 trace ID
     * @param conversationId 来源会话 ID
     * @param userId 任务所属用户
     * @param agentState 已包含确定性证据的状态
     * @param selectedModel 最终报告模型元数据
     * @param task 已落库研究任务
     * @param lease 当前执行租约
     * @return 已发布报告的 JSON 表示
     * @throws Exception 辩论和离线兜底均失败时
     */
    public String runResearchDebateWithTask(
            String traceId,
            Long conversationId,
            String userId,
            AnalysisState agentState,
            Coordinator.SelectedModel selectedModel,
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease
    ) throws Exception {
        ResearchTask runningTask = null;
        ScheduledFuture<?> heartbeat = null;
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        try {
            boolean alreadyOwned = task.getStatus() == ResearchTask.Status.RUNNING
                    && lease.token().equals(task.getLeaseToken());
            runningTask = alreadyOwned
                    ? task
                    : researchTaskService.startAttempt(
                            task,
                            lease.token(),
                            ResearchTask.Stage.AGENT_DEBATE
                    );
            fence.requireTaskOwnership(runningTask, lease, ownershipLost);
            heartbeat = fence.startResearchTaskHeartbeat(runningTask, lease, ownershipLost);
            ResearchTask reportTask = runningTask;
            fence.saveEvidenceForOwner(reportTask, lease, agentState, ownershipLost);
            fence.markStageForOwner(
                    reportTask, lease, ResearchTask.Stage.AGENT_DEBATE, ownershipLost);
            AnalysisState completed = runDebateAndCheckpoint(
                    reportTask, lease, traceId, conversationId, agentState, 1, 0, ownershipLost);
            if (completed.getInvestmentReport() == null
                    || completed.getInvestmentReport().getQualityStatus()
                    != InvestmentReport.ReportQualityStatus.VERIFIED) {
                String reportJson = objectMapper.writeValueAsString(completed.getInvestmentReport());
                fence.markSucceededForOwner(
                        runningTask,
                        lease,
                        null,
                        ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE,
                        ownershipLost
                );
                return reportJson;
            }
            fence.markStageForOwner(runningTask, lease, ResearchTask.Stage.REPORT_PERSIST, ownershipLost);
            fence.requireTaskOwnership(runningTask, lease, ownershipLost);
            ResearchTask publicationTask = runningTask;
            InvestmentReportVersionService.PersistedReportVersion persisted =
                    publicationTransaction.executeForUser(userId, () -> {
                        checkDeadline(publicationTask);
                        InvestmentReportVersionService.PersistedReportVersion published =
                                investmentReportVersionService.persistReportVersionWithMetadata(
                                        userId,
                                        conversationId,
                                        completed,
                                        selectedModel == null
                                                ? null
                                                : selectedModel.tier().name(),
                                        selectedModel == null
                                                ? null
                                                : selectedModel.modelName()
                        );
                        fence.markSucceededForOwner(
                                publicationTask,
                                lease,
                                published.reportVersionId(),
                                ResearchTask.ResultKind.FULL_REPORT,
                                ownershipLost
                        );
                        return published;
                    });
            String reportJson = objectMapper.writeValueAsString(
                    persisted.report() == null ? completed.getInvestmentReport() : persisted.report()
            );
            return reportJson;
        } catch (OwnershipLostException error) {
            throw error;
        } catch (ResearchEvidenceSnapshotService.SnapshotException | ResearchBudgetExceededException | ResearchCapacityExceededException error) {
            ResearchTask failedTask = runningTask == null ? task : runningTask;
            fence.requireTaskOwnership(failedTask, lease, ownershipLost);
            if (!researchTaskService.markFailedForOwner(failedTask, lease.token(), error.getMessage())) {
                throw new OwnershipLostException("research ownership lost before execution constraint failure update", error);
            }
            throw error;
        } catch (Exception e) {
            ResearchTask failedTask = runningTask == null ? task : runningTask;
            try {
                ResearchBudgetExceededException.rethrowIfPresent(e);
                ResearchCapacityExceededException.rethrowIfPresent(e);
                checkDeadline(failedTask);
            } catch (ResearchBudgetExceededException | ResearchCapacityExceededException budget) {
                fence.failExecutionConstraintForOwner(failedTask, lease, ownershipLost, budget);
                throw budget;
            }
            fence.requireTaskOwnership(failedTask, lease, ownershipLost);
            Optional<PersistedFallback> fallbackReport;
            try {
                fallbackReport =
                    publicationTransaction.executeForUser(userId, () -> {
                        Optional<PersistedFallback> candidate = persistOfflineFallbackReport(
                                userId,
                                conversationId,
                                agentState,
                                failedTask,
                                lease,
                                e,
                                ownershipLost
                        );
                        candidate.ifPresent(fallback -> fence.markSucceededForOwner(
                                failedTask,
                                lease,
                                fallback.reportVersionId(),
                                ResearchTask.ResultKind.OFFLINE_FALLBACK,
                                ownershipLost
                        ));
                        return candidate;
                    });
            } catch (ResearchBudgetExceededException | ResearchCapacityExceededException budget) {
                fence.failExecutionConstraintForOwner(failedTask, lease, ownershipLost, budget);
                throw budget;
            }
            if (fallbackReport.isPresent()) {
                return fallbackReport.orElseThrow().reportJson();
            }
            fence.requireTaskOwnership(failedTask, lease, ownershipLost);
            boolean failed = researchTaskService.markFailedForOwner(
                    failedTask, lease.token(), e.getMessage());
            if (!failed) {
                ownershipLost.set(true);
                throw new OwnershipLostException(
                        "research task lost ownership before failure update", e);
            }
            throw e;
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            researchTaskService.release(lease);
        }
    }

    /** 两个入口共用阶段写入；恢复轮次和交付方式仍由各自入口决定。 */
    private AnalysisState runDebateAndCheckpoint(
            ResearchTask task, ResearchTaskLeaseService.Lease lease, String traceId, Long conversationId,
            AnalysisState state, int nextRound, int authorizedThroughRound, AtomicBoolean ownershipLost) {
        bindInvocationContext(state, task, lease, traceId);
        AnalysisState completed = researchDebateService.runDebate(
                traceId, conversationId, state, nextRound, authorizedThroughRound,
                (snapshot, rounds, authorized) -> fence.saveDebateRoundForOwner(
                        task, lease, snapshot, rounds, authorized, ownershipLost),
                fence.ownershipGuard(task, ownershipLost),
                snapshot -> fence.saveHarnessSnapshotForOwner(task, lease, snapshot, ownershipLost));
        fence.saveSynthesisForOwner(task, lease, completed, ownershipLost);
        fence.markStageForOwner(task, lease, ResearchTask.Stage.REPORT_SYNTHESIS, ownershipLost);
        return completed;
    }

    private void bindInvocationContext(AnalysisState state, ResearchTask task,
            ResearchTaskLeaseService.Lease lease, String traceId) {
        checkDeadline(task);
        try {
            String snapshotId = evidenceSnapshotService.capture(task.getId(), task.getAttempts(), lease.token(), state);
            state.setModelInvocationContext(new ModelInvocationContext(
                    task.getId(), task.getAttempts(), lease.token(), traceId, snapshotId,
                    task.getBudgetDeadlineEpochMs()));
        } catch (ResearchEvidenceSnapshotService.SnapshotException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            ResearchBudgetExceededException.rethrowIfPresent(failure);
            ResearchCapacityExceededException.rethrowIfPresent(failure);
            throw new ResearchEvidenceSnapshotService.SnapshotException(task.getId(), "证据快照事务未能确认提交", failure);
        }
    }

    private Optional<PersistedFallback> persistOfflineFallbackReport(
            String userId,
            Long conversationId,
            AnalysisState agentState,
            ResearchTask runningTask,
            ResearchTaskLeaseService.Lease lease,
            Exception sourceError,
            AtomicBoolean ownershipLost
    ) {
        ResearchBudgetExceededException.rethrowIfPresent(sourceError);
        ResearchCapacityExceededException.rethrowIfPresent(sourceError);
        if (agentState == null || runningTask == null || lease == null) {
            return Optional.empty();
        }
        checkDeadline(runningTask);
        Optional<InvestmentReport> fallbackReport = offlineDemoSampleService.buildFallbackReport(agentState);
        if (fallbackReport.isEmpty()) {
            return Optional.empty();
        }
        try {
            InvestmentReport report = fallbackReport.get();
            agentState.setInvestmentReport(report);
            fence.markStageForOwner(
                    runningTask, lease, ResearchTask.Stage.REPORT_PERSIST, ownershipLost);
            fence.requireTaskOwnership(runningTask, lease, ownershipLost);
            checkDeadline(runningTask);
            InvestmentReportVersionService.PersistedReportVersion persisted =
                    investmentReportVersionService.persistReportVersionWithMetadata(
                            userId,
                            conversationId,
                            agentState,
                            OfflineDemoSampleService.MODEL_TIER,
                            OfflineDemoSampleService.MODEL_NAME
                    );
            log.warn("Research debate failed, persisted offline fallback report for ticker={}, taskId={}, sourceError={}",
                    agentState.getPrimaryTicker(), runningTask.getId(), sourceError == null ? "" : sourceError.getMessage());
            return Optional.of(new PersistedFallback(
                    objectMapper.writeValueAsString(
                            persisted.report() == null ? report : persisted.report()),
                    persisted.reportVersionId(),
                    null
            ));
        } catch (OwnershipLostException ownershipError) {
            throw ownershipError;
        } catch (Exception fallbackError) {
            ResearchBudgetExceededException.rethrowIfPresent(fallbackError);
            ResearchCapacityExceededException.rethrowIfPresent(fallbackError);
            log.warn("Offline fallback report failed after research debate error, taskId={}, sourceError={}, fallbackError={}",
                    runningTask.getId(),
                    sourceError == null ? "" : sourceError.getMessage(),
                    fallbackError.getMessage());
            return Optional.empty();
        }
    }

    private DeepEvidenceCollector.EvidenceCollection executeEvidenceRecovery(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            SubmissionPayload payload,
            DeepEvidenceCollector.EvidenceCollection evidence,
            Map<RecoveryAction, Integer> previousAttempts,
            AtomicBoolean ownershipLost
    ) {
        if (evidence.harnessDecision().outcome() != HarnessOutcome.RECOVER) {
            return evidence;
        }

        List<RecoveryAction> recoveryActions = evidence.harnessDecision().recoveryActions();
        // 先把计数加一并落盘再执行：崩溃接管时本次预算视为已用，重新验收后不会再重试一次。
        Map<RecoveryAction, Integer> completedAttempts =
                incrementRecoveryAttempts(previousAttempts, recoveryActions);
        evidence.state().setHarnessSnapshot(evidenceSnapshot(evidence.harnessDecision(), completedAttempts));
        fence.saveHarnessSnapshotForOwner(task, lease, evidence.state(), ownershipLost);
        recordEvidenceRecoveryExecution(payload.traceId(), recoveryActions, completedAttempts);
        log.info("Running bounded evidence recovery, taskId={}, actions={}", task.getId(), recoveryActions);

        DeepEvidenceCollector.EvidenceCollection recovered = ToolCallContext.withRunExecution(
                new ToolCallContext.RunExecution(task.getId(), task.getAttempts(), lease.token(),
                        payload.traceId(), task.getBudgetDeadlineEpochMs()),
                () -> evidenceCollector.recover(
                evidence,
                recoveryActions,
                previousAttempts,
                payload.traceId(),
                payload.conversationId(),
                task.getUserId()
        ));
        recovered.state().setHarnessSnapshot(evidenceSnapshot(recovered.harnessDecision(), completedAttempts));
        fence.saveHarnessSnapshotForOwner(task, lease, recovered.state(), ownershipLost);
        return recovered;
    }

    private static HarnessSnapshot evidenceSnapshot(
            HarnessDecision decision,
            Map<RecoveryAction, Integer> recoveryAttempts
    ) {
        return HarnessSnapshot.from(
                DeepResearchCompletionPolicy.POLICY_ID,
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.EVIDENCE,
                decision,
                recoveryAttempts
        );
    }

    void recordEvidenceRecoveryExecution(
            String traceId,
            List<RecoveryAction> recoveryActions,
            Map<RecoveryAction, Integer> recoveryAttempts
    ) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            traceService.addStep(traceId, AgentStep.builder()
                    .thought("Harness recovery plan was durably checkpointed")
                    .action("harness-evidence-recovery")
                    .actionInput("{\"argumentKeys\":[\"recoveryActions\"]}")
                    .observation("PLANNED")
                    .durationMs(0)
                    .tokenCount(0)
                    .attributes(Map.of(
                            "stepKind", "harness_recovery_execution",
                            "policyId", DeepResearchCompletionPolicy.POLICY_ID,
                            "policyVersion", Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                            "phase", HarnessPhase.EVIDENCE.name(),
                            "recoveryActions", List.copyOf(recoveryActions),
                            "recoveryAttempts", recoveryAttempts
                    ))
                    .build());
        } catch (Exception error) {
            log.debug("Failed to persist Harness recovery execution trace, traceId={}", traceId, error);
        }
    }

    private boolean isEvidenceBoundaryCheckpoint(
            ResearchTaskCheckpointService.CheckpointState checkpoint
    ) {
        return checkpoint.stageCompleted() == null
                || checkpoint.stageCompleted() == ResearchTask.Stage.DATA_PREFETCH;
    }

    private boolean hasPendingReportRecovery(AnalysisState state) {
        HarnessSnapshot snapshot = state == null ? null : state.getHarnessSnapshot();
        return snapshot != null && snapshot.hasPendingRecovery(HarnessPhase.REPORT);
    }

    private int completedDebateRounds(AnalysisState state) {
        if (state == null || state.getDebateTurns() == null) {
            return 0;
        }
        int completed = 0;
        while (hasDebateTurn(state, completed + 1, Side.BULL)
                && hasDebateTurn(state, completed + 1, Side.BEAR)) {
            completed++;
        }
        return completed;
    }

    private boolean hasDebateTurn(
            AnalysisState state,
            int round,
            Side side
    ) {
        return state.getDebateTurns().stream()
                .anyMatch(turn -> turn != null
                        && turn.round() == round
                        && turn.side() == side
                        && turn.points() != null
                        && !turn.points().isEmpty());
    }

    private Map<RecoveryAction, Integer> checkpointRecoveryAttempts(AnalysisState state) {
        HarnessSnapshot snapshot = state == null ? null : state.getHarnessSnapshot();
        return snapshot == null ? Map.of() : snapshot.recoveryAttempts();
    }

    private Map<RecoveryAction, Integer> incrementRecoveryAttempts(
            Map<RecoveryAction, Integer> previousAttempts,
            List<RecoveryAction> actions
    ) {
        java.util.EnumMap<RecoveryAction, Integer> attempts =
                new java.util.EnumMap<>(RecoveryAction.class);
        if (previousAttempts != null) {
            previousAttempts.forEach((action, count) -> {
                if (action != null) {
                    attempts.put(action, Math.max(0, count == null ? 0 : count));
                }
            });
        }
        if (actions != null) {
            actions.forEach(action -> {
                if (action != null) {
                    attempts.merge(action, 1, Integer::sum);
                }
            });
        }
        return Map.copyOf(attempts);
    }

    private record PersistedFallback(
            String reportJson,
            Long reportVersionId,
            String deliveryText
    ) {
    }

    /** 通知 worker 当前执行已失去 ownership，应静默停止并把队列记录留给新 owner。 */
    public static final class OwnershipLostException extends RuntimeException {

        /** @param message 所有权丢失的阶段与任务上下文 */
        public OwnershipLostException(String message) {
            super(message);
        }

        /**
         * @param message 所有权丢失的阶段与任务上下文
         * @param cause 触发 owner-fence 失败的原始异常
         */
        public OwnershipLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
