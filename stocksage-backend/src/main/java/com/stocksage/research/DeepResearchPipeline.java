package com.stocksage.research;

import com.stocksage.service.OfflineDemoSampleService;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.tool.ToolCallContext;

import com.stocksage.conversation.ConversationMessageService;
import com.stocksage.util.PromptText;

import com.stocksage.agent.intent.TimeSensitivity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ModelTier;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RecoveryLifecycle;
import com.stocksage.harness.HarnessModels.SuspendedRecovery;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.ModelInvocationContext;
import com.stocksage.model.dto.DebateModels.Side;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.List;
import java.util.Locale;
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
    /** 运行研究任务租约与数据库心跳的单线程 daemon 调度器。 */
    private final TaskScheduler researchHeartbeatScheduler;
    /** 保存证据、Harness、辩论轮次和综合状态检查点。 */
    private final ResearchTaskCheckpointService checkpointService;
    /** 收集/恢复确定性证据并执行首次 Harness 闸门。 */
    private final DeepEvidenceCollector evidenceCollector;
    /** 在证据门禁通过后最多执行一次有界问题相关补证。 */
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

    private final ResearchRunManifestService runManifestService;
    private final ResearchEvidenceSnapshotService evidenceSnapshotService;

    /**
     * 在 worker 内运行完整 DEEP 管线；请求线程只负责创建任务和订阅事件。
     *
     * @param task 已落库 PENDING 任务或由 worker fenced 接管的 RUNNING 任务
     * @param lease 当前执行租约，token 将贯穿所有 owner-fenced 写入
     */
    public void runFullPipeline(ResearchTask task, ResearchTaskLeaseService.Lease lease) {
        long pipelineStartedAt = System.currentTimeMillis();
        SubmissionPayload payload = null;
        ResearchTask runningTask = task;
        AnalysisState workingState = null;
        ScheduledFuture<?> heartbeat = null;
        boolean attemptStarted = false;
        AtomicBoolean ownershipLost = new AtomicBoolean(false);

        try {
            payload = parsePayload(task);
            // PENDING 任务尚无数据库 owner，startAttempt 前只能先校验 Redis 租约所有权。
            requireLeaseOwnership(task, lease, ownershipLost);
            if (task.getStatus() == ResearchTask.Status.RUNNING) {
                if (!lease.token().equals(task.getLeaseToken())) {
                    ownershipLost.set(true);
                    throw new OwnershipLostException(
                            "RUNNING research task is not owned by the supplied lease");
                }
                runningTask = task;
            } else {
                runningTask = researchTaskService.startAttempt(
                        task, lease.token(), ResearchTask.Stage.DATA_PREFETCH);
            }
            attemptStarted = true;
            requireTaskOwnership(runningTask, lease, ownershipLost);
            heartbeat = startResearchTaskHeartbeat(runningTask, lease, ownershipLost);
            checkDeadline(runningTask);
            verifyRunConfiguration(runningTask.getId(), lease.token());

            // 取得 PENDING 启动权或 RUNNING 接管权后再读 checkpoint，避免旧 worker 越权恢复。
            Optional<ResearchTaskCheckpointService.CheckpointState> checkpoint =
                    checkpointService.load(task.getId());
            workingState = checkpoint.map(ResearchTaskCheckpointService.CheckpointState::state).orElse(null);
            if (workingState != null) {
                // 提交载荷是请求条件的权威来源，不能沿用 checkpoint 中其它尝试的时间要求。
                workingState.setTimeSensitivity(payload.timeSensitivity());
                bindInvocationContext(workingState, runningTask, lease, payload.traceId());
            }
            boolean checkpointedReportArtifact = workingState != null
                    && workingState.getInvestmentReport() != null;
            boolean resumeFromEvidenceBoundary = checkpoint
                    .map(this::isEvidenceBoundaryCheckpoint)
                    .orElse(false);
            SuspendedRecovery suspendedReportRecovery =
                    suspendedReportRecovery(workingState);

            int roundsDone = checkpoint
                    .map(ResearchTaskCheckpointService.CheckpointState::debateRoundsCompleted)
                    .orElse(0);
            int authorizedThroughRound = checkpoint
                    .map(ResearchTaskCheckpointService.CheckpointState::plannedRounds)
                    .orElse(0);
            if (hasPendingReportRecovery(workingState)) {
                // 旧同步 checkpoint 的列仍是 DATA_PREFETCH/0；从成对辩论记录恢复轮次，接管后直接进入 Manager。
                int durableRounds = completedDebateRounds(workingState);
                roundsDone = Math.max(roundsDone, durableRounds);
                authorizedThroughRound = roundsDone;
            }

            DeepEvidenceCollector.EvidenceCollection evidence = null;
            Map<RecoveryAction, Integer> recoveryAttempts = Map.of();
            if (checkpoint.isEmpty()) {
                requireTaskOwnership(runningTask, lease, ownershipLost);
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "thought",
                        "开始收集深度研究证据……");
                // 调用证据收集器建立结构化 ledger；后续辩论不得绕过 Harness 决定。
                var collectionPayload = payload;
                String collectionUserId = runningTask.getUserId();
                evidence = ToolCallContext.withRunExecution(
                        new ToolCallContext.RunExecution(runningTask.getId(), runningTask.getAttempts(), lease.token(),
                                payload.traceId(), runningTask.getBudgetDeadlineEpochMs()),
                        () -> evidenceCollector.collect(
                        collectionPayload.ticker(), collectionPayload.query(), collectionPayload.traceId(),
                        collectionPayload.conversationId(), collectionPayload.timeSensitivity(), collectionUserId));
                checkDeadline(runningTask);
            } else {
                requireTaskOwnership(runningTask, lease, ownershipLost);
                recoveryAttempts = checkpointRecoveryAttempts(workingState);
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "observation",
                        "从持久化断点恢复研究任务：重新执行当前证据门禁与未完成的恢复动作。");
                evidence = evidenceCollector.reevaluateCheckpoint(
                        workingState,
                        recoveryAttempts,
                        payload.traceId()
                );
                evidence = restoreDurablePlannedRecovery(workingState, evidence);
            }

            if (evidence != null) {
                workingState = evidence.state();
                evidence = executeEvidenceRecovery(
                        runningTask,
                        lease,
                        payload,
                        evidence,
                        recoveryAttempts,
                        suspendedReportRecovery,
                        ownershipLost
                );
                workingState = evidence.state();
                bindInvocationContext(workingState, runningTask, lease, payload.traceId());
                if ((checkpoint.isEmpty() || resumeFromEvidenceBoundary)
                        && suspendedReportRecovery == null) {
                    ResearchTask ownedTask = runningTask;
                    evidence = evidenceReplanService.replan(
                            runningTask.getId(),
                            task.getUserId(),
                            payload.conversationId(),
                            payload.traceId(),
                            evidence,
                            () -> requireTaskOwnership(ownedTask, lease, ownershipLost),
                            state -> saveEvidenceReplanForOwner(
                                    ownedTask, lease, state, ownershipLost)
                    );
                    workingState = evidence.state();
                }
                bindInvocationContext(workingState, runningTask, lease, payload.traceId());
                if (evidence.harnessDecision().outcome() != HarnessOutcome.PASS) {
                    String text = reportRenderer.buildInsufficientEvidenceReport(
                            payload.ticker(), evidence.tickerResolved(), evidence.fundamentalsOk(), evidence.marketOk());
                    ResearchTask.ResultKind resultKind =
                            evidence.harnessDecision().outcome() == HarnessOutcome.BLOCK
                                    ? ResearchTask.ResultKind.POLICY_BLOCKED
                                    : ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE;
                    finishWithText(
                            runningTask, lease, payload, text, null, resultKind, ownershipLost);
                    return;
                }

                restoreSuspendedReportRecovery(workingState, suspendedReportRecovery);
                // checkpoint 哈希仅是历史元数据；当前策略通过后，复用与持久化必须重新绑定当前证据账本和策略。
                investmentReportVersionService.prepareHashes(workingState);

                if (checkpoint.isEmpty() || resumeFromEvidenceBoundary) {
                    requireTaskOwnership(runningTask, lease, ownershipLost);
                    checkDeadline(runningTask);
                    // 调用版本服务复用同快照报告，但仍需当前策略和人工审核允许。
                    Optional<InvestmentReportVersionService.PersistedReportVersion> reusable =
                            investmentReportVersionService.findReusableReport(
                                    task.getUserId(), payload.conversationId(), workingState);
                    if (reusable.isPresent()) {
                        workingState.setInvestmentReport(reusable.get().report());
                        finishWithText(runningTask, lease, payload,
                                reportRenderer.buildFinalAnswerBrief(workingState), reusable.get().reportVersionId(),
                                ResearchTask.ResultKind.FULL_REPORT, ownershipLost);
                        return;
                    }

                    saveEvidenceForOwner(runningTask, lease, workingState, ownershipLost);
                    markStageForOwner(
                            runningTask, lease, ResearchTask.Stage.AGENT_DEBATE, ownershipLost);
                } else {
                    requireTaskOwnership(runningTask, lease, ownershipLost);
                    chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "observation",
                            "证据门禁复验通过：继续恢复已完成 "
                                    + roundsDone + " 轮的研究断点。");
                }
            }

            if (checkpointedReportArtifact && workingState.getInvestmentReport() != null) {
                requireTaskOwnership(runningTask, lease, ownershipLost);
                ResearchTask ownedTask = runningTask;
                workingState = researchDebateService.revalidateCheckpointedReport(
                        payload.traceId(),
                        payload.conversationId(),
                        workingState,
                        ownershipGuard(ownedTask, ownershipLost),
                        state -> saveHarnessSnapshotForOwner(
                                ownedTask, lease, state, ownershipLost)
                );
                saveSynthesisForOwner(runningTask, lease, workingState, ownershipLost);
                markStageForOwner(
                        runningTask, lease, ResearchTask.Stage.REPORT_SYNTHESIS, ownershipLost);
            }

            if (workingState.getInvestmentReport() == null) {
                requireTaskOwnership(runningTask, lease, ownershipLost);
                ResearchTask ownedTask = runningTask;
                AnalysisState completed = runDebateAndCheckpoint(
                        ownedTask, lease, payload.traceId(), payload.conversationId(), workingState,
                        roundsDone + 1, authorizedThroughRound, ownershipLost);
                workingState = completed;
            }

            if (workingState.getInvestmentReport() == null
                    || workingState.getInvestmentReport().getQualityStatus()
                    != InvestmentReport.ReportQualityStatus.VERIFIED) {
                String notRated = reportRenderer.buildFinalAnswerBrief(workingState);
                finishWithText(
                        runningTask,
                        lease,
                        payload,
                        notRated,
                        null,
                        ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE,
                        ownershipLost
                );
                return;
            }

            markStageForOwner(runningTask, lease, ResearchTask.Stage.REPORT_PERSIST, ownershipLost);
            finishWithPublishedReport(
                    runningTask,
                    lease,
                    payload,
                    workingState,
                    ModelTier.STRONG.name(),
                    null,
                    ResearchTask.ResultKind.FULL_REPORT,
                    ownershipLost
            );
        } catch (OwnershipLostException error) {
            log.info("Research task stopped after ownership loss, taskId={}, error={}",
                    task == null ? null : task.getId(), error.getMessage());
            throw error;
        } catch (ResearchTaskCheckpointService.CheckpointFormatException
                 | ResearchEvidenceSnapshotService.SnapshotException
                 | ResearchBudgetExceededException
                 | ResearchCapacityExceededException
                 | ResearchRunManifestService.ManifestException error) {
            // 执行配置或持久化决定无法恢复时，保留快照，禁止重采集或发布离线替代报告。
            requireTaskOwnership(runningTask, lease, ownershipLost);
            if (!researchTaskService.markFailedForOwner(runningTask, lease.token(), error.getMessage())) {
                ownershipLost.set(true);
                throw new OwnershipLostException(
                        "research task lost ownership before recovery contract failure update", error);
            }
            chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "error", error.getMessage());
        } catch (Exception error) {
            try {
                ResearchBudgetExceededException.rethrowIfPresent(error);
                ResearchCapacityExceededException.rethrowIfPresent(error);
                if (attemptStarted) checkDeadline(runningTask);
            } catch (ResearchBudgetExceededException | ResearchCapacityExceededException budget) {
                failExecutionConstraintForOwner(runningTask, lease, ownershipLost, budget);
                if (payload != null) chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "error", budget.getMessage());
                return;
            }
            if (!attemptStarted) {
                boolean failed = researchTaskService.markFailedIfPending(task, error.getMessage());
                if (!failed) {
                    ownershipLost.set(true);
                    throw new OwnershipLostException(
                            "research task left pending state before pre-attempt failure update",
                            error
                    );
                }
                if (payload != null) {
                    chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "error",
                            "深度研究任务执行失败：" + safeError(error));
                }
                return;
            }

            requireTaskOwnership(runningTask, lease, ownershipLost);
            ResearchTask fallbackTask = runningTask;
            SubmissionPayload fallbackPayload = payload;
            AnalysisState fallbackState = workingState;
            Optional<PersistedFallback> fallback;
            try {
                fallback = fallbackPayload == null
                    ? Optional.empty()
                    : publicationTransaction.executeForUser(task.getUserId(), () -> {
                        Optional<PersistedFallback> candidate = persistOfflineFallbackReport(
                                task.getUserId(),
                                fallbackPayload.conversationId(),
                                fallbackState,
                                fallbackTask,
                                lease,
                                error,
                                ownershipLost
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
                        markSucceededForOwner(
                                fallbackTask,
                                lease,
                                persisted.reportVersionId(),
                                ResearchTask.ResultKind.OFFLINE_FALLBACK,
                                ownershipLost
                        );
                        return Optional.of(new PersistedFallback(
                                persisted.reportJson(),
                                persisted.reportVersionId(),
                                text
                        ));
                    });
            } catch (ResearchBudgetExceededException | ResearchCapacityExceededException budget) {
                failExecutionConstraintForOwner(runningTask, lease, ownershipLost, budget);
                if (payload != null) chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "error", budget.getMessage());
                return;
            }
            if (fallback.isPresent()) {
                PersistedFallback persistedFallback = fallback.get();
                deleteCompletedCheckpointBestEffort(runningTask);
                chatStreamEmitter.emit(
                        payload.traceId(),
                        payload.conversationId(),
                        "task-final",
                        persistedFallback.deliveryText()
                );
                return;
            }

            requireTaskOwnership(runningTask, lease, ownershipLost);
            boolean failed = researchTaskService.markFailedForOwner(
                    runningTask, lease.token(), error.getMessage());
            if (failed && payload != null) {
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "error",
                        "深度研究任务执行失败：" + safeError(error));
            }
            if (!failed) {
                ownershipLost.set(true);
                throw new OwnershipLostException(
                        "research task lost ownership before failure update", error);
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            try {
                researchTaskService.release(lease);
            } finally {
                endTerminalTaskTraceBestEffort(
                        runningTask,
                        payload,
                        System.currentTimeMillis() - pipelineStartedAt
                );
            }
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
            case INSUFFICIENT_EVIDENCE, OFFLINE_FALLBACK -> "DEGRADED";
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
        requireTaskOwnership(task, lease, ownershipLost);
        // 在用户行锁事务内同时写消息和 owner-fenced 终态，任一步失败则整体回滚。
        publicationTransaction.executeForUser(task.getUserId(), () -> {
            checkDeadline(task);
            conversationMessageService.persistAssistantReport(
                    payload.conversationId(), task.getUserId(), text, payload.traceId());
            markSucceededForOwner(task, lease, reportVersionId, resultKind, ownershipLost);
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
        requireTaskOwnership(task, lease, ownershipLost);
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
            markSucceededForOwner(
                    task, lease, persisted.reportVersionId(), resultKind, ownershipLost);
            return committedText;
        });
        deleteCompletedCheckpointBestEffort(task);
        chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "task-final", text);
    }

    private void deleteCompletedCheckpointBestEffort(ResearchTask task) {
        try {
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
            return new SubmissionPayload(ticker, query, traceId, conversationId, timeSensitivity);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid research task submission payload", e);
        }
    }

    private String safeError(Exception error) {
        return error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
    }

    private record SubmissionPayload(String ticker, String query, String traceId, Long conversationId,
                                     TimeSensitivity timeSensitivity) {
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
        ScheduledFuture<?> heartbeat = startInlineHeartbeat(runningTask, acquired, ownershipLost);
        try {
            requireInlineOwnership(runningTask, acquired, ownershipLost, "evidence prefetch");
            checkDeadline(runningTask);
            verifyRunConfiguration(runningTask.getId(), acquired.token());
            DeepEvidenceCollector.EvidenceCollection evidence = ToolCallContext.withRunExecution(
                    new ToolCallContext.RunExecution(runningTask.getId(), runningTask.getAttempts(), acquired.token(),
                            traceId, runningTask.getBudgetDeadlineEpochMs()),
                    () -> evidenceCollector.collect(primaryTicker, userQuery, traceId, conversationId, timeSensitivity,
                            runningTask.getUserId()));
            bindInvocationContext(evidence.state(), runningTask, acquired, traceId);
            if (evidence.harnessDecision().outcome() == HarnessOutcome.RECOVER) {
                List<RecoveryAction> recoveryActions = evidence.harnessDecision().recoveryActions();
                String recoveryEffectKey = inlineRecoveryEffectKey(
                        runningTask.getId(),
                        recoveryActions
                );
                evidence.state().setHarnessSnapshot(HarnessSnapshot.recovery(
                        DeepResearchCompletionPolicy.POLICY_ID,
                        Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                        HarnessPhase.EVIDENCE,
                        evidence.harnessDecision(),
                        Map.of(),
                        RecoveryLifecycle.PLANNED,
                        recoveryActions,
                        recoveryEffectKey
                ));
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "planned recovery checkpoint");
                saveInlineHarnessSnapshot(runningTask, acquired, evidence.state());
                recordEvidenceRecoveryExecution(
                        traceId, recoveryEffectKey, recoveryActions);
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
                Map<RecoveryAction, Integer> completedAttempts = recoveryActions.stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                action -> action,
                                action -> 1,
                                Math::max
                        ));
                evidence.state().setHarnessSnapshot(HarnessSnapshot.recovery(
                        DeepResearchCompletionPolicy.POLICY_ID,
                        Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                        HarnessPhase.EVIDENCE,
                        evidence.harnessDecision(),
                        completedAttempts,
                        RecoveryLifecycle.REVALIDATED,
                        recoveryActions,
                        recoveryEffectKey
                ));
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
            context.append(evidence.contextMarkdown());
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

            investmentReportVersionService.prepareHashes(agentState);
            checkDeadline(runningTask);
            Optional<InvestmentReportVersionService.PersistedReportVersion> reusableReport = investmentReportVersionService.findReusableReport(
                    userId, conversationId, agentState);
            if (reusableReport.isPresent()) {
                InvestmentReport report = reusableReport.orElseThrow().report();
                agentState.setInvestmentReport(report);
                appendInlineReportContext(context, safeReportJson(report));
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
            appendInlineReportContext(context, reportJson);
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
            boolean provenanceFailure = error instanceof ResearchRunManifestService.ManifestException
                    || error instanceof ResearchEvidenceSnapshotService.SnapshotException
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

    private void verifyRunConfiguration(Long taskId, String leaseToken) {
        try {
            runManifestService.verifyOrFreeze(taskId, leaseToken);
        } catch (ResearchRunManifestService.ManifestException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            ResearchBudgetExceededException.rethrowIfPresent(failure);
            ResearchCapacityExceededException.rethrowIfPresent(failure);
            // Transaction commit failures occur outside the service method's catch block.
            throw new ResearchRunManifestService.ManifestException(
                    taskId, "模型配置清单事务未能确认提交", failure);
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

    /** inline 降级仍可能包含耗时的数据预取，因此在进入辩论流水线前也必须持续续租。 */
    private ScheduledFuture<?> startInlineHeartbeat(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost
    ) {
        Duration interval = researchTaskService.leaseHeartbeatInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            interval = Duration.ofSeconds(60);
        }
        return researchHeartbeatScheduler.scheduleAtFixedRate(() -> {
            if (ownershipLost.get()) {
                return;
            }
            try {
                boolean leaseRenewed = researchTaskService.renewLease(lease);
                boolean taskRenewed = leaseRenewed
                        && researchTaskService.heartbeatForOwner(task, lease.token());
                if (!taskRenewed) {
                    ownershipLost.set(true);
                    log.warn("Inline research task heartbeat lost ownership, taskId={}", task.getId());
                }
            } catch (Exception error) {
                ownershipLost.set(true);
                log.warn("Inline research task heartbeat failed, taskId={}, error={}",
                        task.getId(), error.getMessage());
            }
        }, Instant.now().plus(interval), interval);
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

    private String inlineRecoveryEffectKey(Long taskId, List<RecoveryAction> actions) {
        String actionKey = actions == null
                ? "none"
                : actions.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .map(action -> action.name().toLowerCase(Locale.ROOT) + "-1")
                .collect(java.util.stream.Collectors.joining("+"));
        if (actionKey.isBlank()) {
            actionKey = "none";
        }
        return "deep-evidence:"
                + taskId
                + ":"
                + DeepResearchCompletionPolicy.POLICY_ID
                + "-v"
                + DeepResearchCompletionPolicy.POLICY_VERSION
                + ":"
                + actionKey;
    }

    /** 同步研究返回给调用方的交付结果；观察任务时 outcome 为空。 */
    public record InlineResult(String context, String directAnswer, Long submittedTaskId,
                               String eventTraceId, String taskOutcome) {
    }

    private void appendInlineReportContext(StringBuilder context, String reportJson) {
        context.append("## Research Manager\n")
                .append(PromptText.truncate(reportJson, 4500)).append("\n\n");
    }

    private String safeReportJson(InvestmentReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize investment report for prepared context: {}", e.getMessage());
            return "{}";
        }
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
            requireTaskOwnership(runningTask, lease, ownershipLost);
            heartbeat = startResearchTaskHeartbeat(runningTask, lease, ownershipLost);
            ResearchTask reportTask = runningTask;
            saveEvidenceForOwner(reportTask, lease, agentState, ownershipLost);
            markStageForOwner(
                    reportTask, lease, ResearchTask.Stage.AGENT_DEBATE, ownershipLost);
            AnalysisState completed = runDebateAndCheckpoint(
                    reportTask, lease, traceId, conversationId, agentState, 1, 0, ownershipLost);
            if (completed.getInvestmentReport() == null
                    || completed.getInvestmentReport().getQualityStatus()
                    != InvestmentReport.ReportQualityStatus.VERIFIED) {
                String reportJson = objectMapper.writeValueAsString(completed.getInvestmentReport());
                markSucceededForOwner(
                        runningTask,
                        lease,
                        null,
                        ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE,
                        ownershipLost
                );
                return reportJson;
            }
            markStageForOwner(runningTask, lease, ResearchTask.Stage.REPORT_PERSIST, ownershipLost);
            requireTaskOwnership(runningTask, lease, ownershipLost);
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
                        markSucceededForOwner(
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
            requireTaskOwnership(failedTask, lease, ownershipLost);
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
                failExecutionConstraintForOwner(failedTask, lease, ownershipLost, budget);
                throw budget;
            }
            requireTaskOwnership(failedTask, lease, ownershipLost);
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
                        candidate.ifPresent(fallback -> markSucceededForOwner(
                                failedTask,
                                lease,
                                fallback.reportVersionId(),
                                ResearchTask.ResultKind.OFFLINE_FALLBACK,
                                ownershipLost
                        ));
                        return candidate;
                    });
            } catch (ResearchBudgetExceededException | ResearchCapacityExceededException budget) {
                failExecutionConstraintForOwner(failedTask, lease, ownershipLost, budget);
                throw budget;
            }
            if (fallbackReport.isPresent()) {
                return fallbackReport.orElseThrow().reportJson();
            }
            requireTaskOwnership(failedTask, lease, ownershipLost);
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
                (snapshot, rounds, authorized) -> saveDebateRoundForOwner(
                        task, lease, snapshot, rounds, authorized, ownershipLost),
                ownershipGuard(task, ownershipLost),
                snapshot -> saveHarnessSnapshotForOwner(task, lease, snapshot, ownershipLost));
        saveSynthesisForOwner(task, lease, completed, ownershipLost);
        markStageForOwner(task, lease, ResearchTask.Stage.REPORT_SYNTHESIS, ownershipLost);
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

    private static void checkDeadline(ResearchTask task) {
        if (task.getMaxModelCalls() == null || task.getMaxModelCalls() <= 0) {
            throw new ResearchBudgetExceededException(task.getId(), ResearchBudgetExceededException.Reason.BUDGET_UNAVAILABLE);
        }
        ResearchBudgetExceededException.remainingMillis(task.getId(), task.getBudgetDeadlineEpochMs());
    }

    private void failExecutionConstraintForOwner(ResearchTask task, ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost, IllegalStateException budget) {
        requireTaskOwnership(task, lease, ownershipLost);
        if (!researchTaskService.markFailedForOwner(task, lease.token(), budget.getMessage())) {
            ownershipLost.set(true);
            throw new OwnershipLostException("research task lost ownership before execution constraint failure update", budget);
        }
    }

    Optional<String> tryPersistOfflineFallbackReport(
            String userId,
            Long conversationId,
            AnalysisState agentState,
            ResearchTask runningTask,
            ResearchTaskLeaseService.Lease lease,
            Exception sourceError
    ) {
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        requireTaskOwnership(runningTask, lease, ownershipLost);
        Optional<PersistedFallback> persisted =
                publicationTransaction.executeForUser(userId, () -> {
                    Optional<PersistedFallback> candidate = persistOfflineFallbackReport(
                            userId,
                            conversationId,
                            agentState,
                            runningTask,
                            lease,
                            sourceError,
                            ownershipLost
                    );
                    candidate.ifPresent(fallback -> markSucceededForOwner(
                            runningTask,
                            lease,
                            fallback.reportVersionId(),
                            ResearchTask.ResultKind.OFFLINE_FALLBACK,
                            ownershipLost
                    ));
                    return candidate;
                });
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(persisted.orElseThrow().reportJson());
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
            markStageForOwner(
                    runningTask, lease, ResearchTask.Stage.REPORT_PERSIST, ownershipLost);
            requireTaskOwnership(runningTask, lease, ownershipLost);
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

    private ScheduledFuture<?> startResearchTaskHeartbeat(
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

    private void requireLeaseOwnership(
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
    private Runnable ownershipGuard(ResearchTask task, AtomicBoolean ownershipLost) {
        Long taskId = task == null ? null : task.getId();
        return () -> {
            if (ownershipLost.get()) {
                throw new OwnershipLostException(
                        "research task ownership was lost during model streaming, taskId=" + taskId);
            }
        };
    }

    private void requireTaskOwnership(
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

    private void markStageForOwner(
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

    private void markSucceededForOwner(
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

    private void saveHarnessSnapshotForOwner(
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

    private void saveEvidenceReplanForOwner(
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

    private DeepEvidenceCollector.EvidenceCollection executeEvidenceRecovery(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            SubmissionPayload payload,
            DeepEvidenceCollector.EvidenceCollection evidence,
            Map<RecoveryAction, Integer> previousAttempts,
            SuspendedRecovery suspendedReportRecovery,
            AtomicBoolean ownershipLost
    ) {
        if (evidence.harnessDecision().outcome() != HarnessOutcome.RECOVER) {
            closeLegacyPendingRecoveryIfNeeded(
                    task,
                    lease,
                    evidence,
                    previousAttempts,
                    suspendedReportRecovery,
                    ownershipLost
            );
            return evidence;
        }

        List<RecoveryAction> recoveryActions = evidence.harnessDecision().recoveryActions();
        String effectKey = recoveryEffectKey(
                task.getId(),
                recoveryActions,
                previousAttempts
        );
        HarnessSnapshot existingSnapshot = evidence.state().getHarnessSnapshot();
        if (existingSnapshot != null
                && existingSnapshot.hasPendingEvidenceRecovery()
                && sameRecoveryActions(existingSnapshot.recoveryActions(), recoveryActions)
                && !existingSnapshot.recoveryEffectKey().isBlank()) {
            effectKey = existingSnapshot.recoveryEffectKey();
        }

        applyEvidenceRecoverySnapshot(
                evidence.state(),
                evidence.harnessDecision(),
                previousAttempts,
                RecoveryLifecycle.PLANNED,
                recoveryActions,
                effectKey,
                suspendedReportRecovery
        );
        saveHarnessSnapshotForOwner(task, lease, evidence.state(), ownershipLost);
        recordEvidenceRecoveryExecution(payload.traceId(), effectKey, recoveryActions);
        log.info("Resuming bounded evidence recovery, taskId={}, effectKey={}, actions={}",
                task.getId(), effectKey, recoveryActions);

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
        Map<RecoveryAction, Integer> completedAttempts =
                incrementRecoveryAttempts(previousAttempts, recoveryActions);
        applyEvidenceRecoverySnapshot(
                recovered.state(),
                recovered.harnessDecision(),
                completedAttempts,
                RecoveryLifecycle.REVALIDATED,
                recoveryActions,
                effectKey,
                suspendedReportRecovery
        );
        // 立即写入 checkpoint，收窄只读恢复副作用与常规证据 checkpoint 之间的崩溃窗口。
        saveHarnessSnapshotForOwner(task, lease, recovered.state(), ownershipLost);
        return recovered;
    }

    void recordEvidenceRecoveryExecution(
            String traceId,
            String effectKey,
            List<RecoveryAction> recoveryActions
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
                            "recoveryLifecycle", RecoveryLifecycle.PLANNED.name(),
                            "recoveryActions", List.copyOf(recoveryActions),
                            "recoveryEffectKey", effectKey
                    ))
                    .build());
        } catch (Exception error) {
            log.debug("Failed to persist Harness recovery execution trace, traceId={}", traceId, error);
        }
    }

    private void closeLegacyPendingRecoveryIfNeeded(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            DeepEvidenceCollector.EvidenceCollection evidence,
            Map<RecoveryAction, Integer> recoveryAttempts,
            SuspendedRecovery suspendedReportRecovery,
            AtomicBoolean ownershipLost
    ) {
        HarnessSnapshot snapshot = evidence.state().getHarnessSnapshot();
        if (snapshot == null || !snapshot.hasPendingEvidenceRecovery()) {
            return;
        }
        applyEvidenceRecoverySnapshot(
                evidence.state(),
                evidence.harnessDecision(),
                recoveryAttempts,
                RecoveryLifecycle.REVALIDATED,
                snapshot.recoveryActions(),
                snapshot.recoveryEffectKey(),
                suspendedReportRecovery
        );
        saveHarnessSnapshotForOwner(task, lease, evidence.state(), ownershipLost);
    }

    private DeepEvidenceCollector.EvidenceCollection restoreDurablePlannedRecovery(
            AnalysisState state,
            DeepEvidenceCollector.EvidenceCollection reevaluated
    ) {
        HarnessSnapshot snapshot = state == null ? null : state.getHarnessSnapshot();
        if (snapshot == null
                || !snapshot.hasPendingEvidenceRecovery()
                || snapshot.recoveryActions().isEmpty()
                || !DeepResearchCompletionPolicy.POLICY_ID.equals(snapshot.policyId())
                || !Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION)
                .equals(snapshot.policyVersion())
                || snapshot.recoveryActions().stream().anyMatch(action ->
                action != RecoveryAction.RETRY_FUNDAMENTALS
                        && action != RecoveryAction.RETRY_MARKET
                        && action != RecoveryAction.RETRY_NEWS)) {
            return reevaluated;
        }
        HarnessDecision durablePlan = new HarnessDecision(
                HarnessOutcome.RECOVER,
                reevaluated.harnessDecision().violations(),
                snapshot.recoveryActions()
        );
        return new DeepEvidenceCollector.EvidenceCollection(
                reevaluated.contextMarkdown(),
                reevaluated.state(),
                false,
                reevaluated.tickerResolved(),
                reevaluated.fundamentalsOk(),
                reevaluated.marketOk(),
                reevaluated.newsOk(),
                reevaluated.evidenceLedger(),
                durablePlan
        );
    }

    private void applyEvidenceRecoverySnapshot(
            AnalysisState state,
            HarnessDecision decision,
            Map<RecoveryAction, Integer> recoveryAttempts,
            RecoveryLifecycle recoveryLifecycle,
            List<RecoveryAction> recoveryActions,
            String recoveryEffectKey,
            SuspendedRecovery suspendedReportRecovery
    ) {
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                DeepResearchCompletionPolicy.POLICY_ID,
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.EVIDENCE,
                decision,
                recoveryAttempts,
                recoveryLifecycle,
                recoveryActions,
                recoveryEffectKey,
                suspendedReportRecovery
        ));
    }

    private SuspendedRecovery suspendedReportRecovery(AnalysisState state) {
        HarnessSnapshot snapshot = state == null ? null : state.getHarnessSnapshot();
        if (snapshot == null) {
            return null;
        }
        if (snapshot.suspendedRecovery() != null
                && snapshot.suspendedRecovery().phase() == HarnessPhase.REPORT) {
            return snapshot.suspendedRecovery();
        }
        return snapshot.phase() == HarnessPhase.REPORT
                ? SuspendedRecovery.from(snapshot)
                : null;
    }

    private void restoreSuspendedReportRecovery(
            AnalysisState state,
            SuspendedRecovery suspendedReportRecovery
    ) {
        if (state == null
                || suspendedReportRecovery == null
                || suspendedReportRecovery.phase() != HarnessPhase.REPORT) {
            return;
        }
        HarnessSnapshot currentSnapshot = state.getHarnessSnapshot();
        if (currentSnapshot == null || currentSnapshot.phase() != HarnessPhase.EVIDENCE) {
            return;
        }
        HarnessDecision reportDecision = new HarnessDecision(
                suspendedReportRecovery.outcome(),
                suspendedReportRecovery.violations().stream()
                        .map(code -> new HarnessViolation(code, null))
                        .toList(),
                suspendedReportRecovery.recoveryActions()
        );
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                DeepResearchCompletionPolicy.POLICY_ID,
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.REPORT,
                reportDecision,
                currentSnapshot.recoveryAttempts(),
                suspendedReportRecovery.recoveryLifecycle(),
                suspendedReportRecovery.recoveryActions(),
                suspendedReportRecovery.recoveryEffectKey()
        ));
    }

    private boolean isEvidenceBoundaryCheckpoint(
            ResearchTaskCheckpointService.CheckpointState checkpoint
    ) {
        return checkpoint.stageCompleted() == null
                || checkpoint.stageCompleted() == ResearchTask.Stage.DATA_PREFETCH;
    }

    private boolean hasPendingReportRecovery(AnalysisState state) {
        HarnessSnapshot snapshot = state == null ? null : state.getHarnessSnapshot();
        return snapshot != null
                && snapshot.phase() == HarnessPhase.REPORT
                && snapshot.recoveryLifecycle() == RecoveryLifecycle.PLANNED;
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

    private String recoveryEffectKey(
            Long taskId,
            List<RecoveryAction> actions,
            Map<RecoveryAction, Integer> previousAttempts
    ) {
        String actionKey = actions == null
                ? "none"
                : actions.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .map(action -> action.name().toLowerCase(java.util.Locale.ROOT)
                        + "-" + (previousAttempts == null
                        ? 1
                        : previousAttempts.getOrDefault(action, 0) + 1))
                .collect(java.util.stream.Collectors.joining("+"));
        if (actionKey.isBlank()) {
            actionKey = "none";
        }
        return "deep-evidence:"
                + taskId
                + ":"
                + DeepResearchCompletionPolicy.POLICY_ID
                + "-v"
                + DeepResearchCompletionPolicy.POLICY_VERSION
                + ":"
                + actionKey;
    }

    private boolean sameRecoveryActions(
            List<RecoveryAction> first,
            List<RecoveryAction> second
    ) {
        List<RecoveryAction> normalizedFirst = first == null
                ? List.of()
                : first.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        List<RecoveryAction> normalizedSecond = second == null
                ? List.of()
                : second.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        return normalizedFirst.equals(normalizedSecond);
    }

    private void saveEvidenceForOwner(
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

    private void saveDebateRoundForOwner(
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

    private void saveSynthesisForOwner(
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

    private OwnershipLostException checkpointOwnershipLost(
            ResearchTask task,
            AtomicBoolean ownershipLost,
            ResearchTaskCheckpointService.OwnershipLostException error
    ) {
        ownershipLost.set(true);
        return new OwnershipLostException(
                "research task lost ownership before checkpoint update, taskId=" + task.getId(), error);
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
