package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.ResearchDebateService;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.tool.ChatStreamEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 深度研究任务的执行流水线。
 *
 * <p>负责把一次 Bull/Bear 辩论包装成带租约与心跳的研究任务：启动尝试、租约心跳续期、
 * 报告版本持久化、失败时的离线兜底报告，以及任务终态回写。从 ChatService 拆出，
 * 使任务生命周期逻辑可以独立测试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepResearchPipeline {

    private final ResearchTaskService researchTaskService;
    private final ResearchDebateService researchDebateService;
    private final InvestmentReportVersionService investmentReportVersionService;
    private final OfflineDemoSampleService offlineDemoSampleService;
    private final ObjectMapper objectMapper;
    // 深度研究任务租约心跳（AsyncConfig#researchHeartbeatScheduler，单线程 daemon）。
    private final TaskScheduler researchHeartbeatScheduler;
    private final ResearchTaskCheckpointService checkpointService;
    private final DeepEvidenceCollector evidenceCollector;
    private final ReportMarkdownRenderer reportRenderer;
    private final ConversationMessageService conversationMessageService;
    private final ChatStreamEmitter chatStreamEmitter;

    /** 整条 DEEP 管线在 worker 内运行，请求线程只负责创建任务和订阅事件。 */
    public void runFullPipeline(ResearchTask task, ResearchTaskLeaseService.Lease lease) {
        SubmissionPayload payload = null;
        ResearchTask runningTask = task;
        AnalysisState workingState = null;
        ScheduledFuture<?> heartbeat = null;
        boolean attemptStarted = false;
        AtomicBoolean ownershipLost = new AtomicBoolean(false);

        try {
            payload = parsePayload(task);
            // A PENDING task has no DB owner yet, so only the lease can be checked before startAttempt.
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

            // Load only after the pending-start or RUNNING-takeover fence owns the task.
            Optional<ResearchTaskCheckpointService.CheckpointState> checkpoint =
                    checkpointService.load(task.getId());
            workingState = checkpoint.map(ResearchTaskCheckpointService.CheckpointState::state).orElse(null);

            int roundsDone = checkpoint
                    .map(ResearchTaskCheckpointService.CheckpointState::debateRoundsCompleted)
                    .orElse(0);
            int plannedRounds = checkpoint
                    .map(ResearchTaskCheckpointService.CheckpointState::plannedRounds)
                    .orElse(0);

            if (checkpoint.isEmpty()) {
                requireTaskOwnership(runningTask, lease, ownershipLost);
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "thought",
                        "开始收集深度研究证据……");
                DeepEvidenceCollector.EvidenceCollection evidence = evidenceCollector.collect(
                        payload.ticker(), payload.query(), payload.traceId(), payload.conversationId());
                if (evidence.harnessDecision().outcome() == HarnessOutcome.RECOVER) {
                    List<RecoveryAction> recoveryActions =
                            evidence.harnessDecision().recoveryActions();
                    applyEvidenceSnapshot(evidence.state(), evidence.harnessDecision(), Map.of());
                    saveHarnessSnapshotForOwner(
                            runningTask, lease, evidence.state(), ownershipLost);
                    evidence = evidenceCollector.recover(
                            evidence,
                            recoveryActions,
                            payload.traceId(),
                            payload.conversationId()
                    );
                    applyEvidenceSnapshot(
                            evidence.state(),
                            evidence.harnessDecision(),
                            recoveryAttempts(recoveryActions)
                    );
                }
                workingState = evidence.state();
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

                requireTaskOwnership(runningTask, lease, ownershipLost);
                investmentReportVersionService.prepareHashes(workingState);
                Optional<InvestmentReport> reusable = investmentReportVersionService.findReusableReport(
                        task.getUserId(), payload.conversationId(), workingState);
                if (reusable.isPresent()) {
                    workingState.setInvestmentReport(reusable.get());
                    finishWithText(runningTask, lease, payload,
                            reportRenderer.buildFinalAnswerBrief(workingState), null,
                            ResearchTask.ResultKind.FULL_REPORT, ownershipLost);
                    return;
                }

                saveEvidenceForOwner(runningTask, lease, workingState, ownershipLost);
                markStageForOwner(runningTask, lease, ResearchTask.Stage.AGENT_DEBATE, ownershipLost);
            } else {
                requireTaskOwnership(runningTask, lease, ownershipLost);
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "observation",
                        "从断点恢复研究任务：已完成 " + roundsDone + " 轮辩论。");
            }

            if (workingState.getInvestmentReport() == null) {
                requireTaskOwnership(runningTask, lease, ownershipLost);
                ResearchTask ownedTask = runningTask;
                AnalysisState completed = researchDebateService.runDebate(
                        payload.traceId(), payload.conversationId(), workingState,
                        roundsDone + 1, plannedRounds,
                        (state, rounds, planned) -> saveDebateRoundForOwner(
                                ownedTask, lease, state, rounds, planned, ownershipLost),
                        ownershipGuard(ownedTask, ownershipLost),
                        state -> saveHarnessSnapshotForOwner(
                                ownedTask, lease, state, ownershipLost));
                saveSynthesisForOwner(runningTask, lease, completed, ownershipLost);
                markStageForOwner(
                        runningTask, lease, ResearchTask.Stage.REPORT_SYNTHESIS, ownershipLost);
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
            requireTaskOwnership(runningTask, lease, ownershipLost);
            InvestmentReportVersionService.PersistedReportVersion persisted =
                    investmentReportVersionService.persistReportVersionWithMetadata(
                            task.getUserId(), payload.conversationId(), workingState,
                            ModelTier.STRONG.name(), null);
            String brief = reportRenderer.buildFinalAnswerBrief(workingState);
            finishWithText(
                    runningTask, lease, payload, brief, persisted.reportVersionId(),
                    ResearchTask.ResultKind.FULL_REPORT, ownershipLost);
        } catch (OwnershipLostException error) {
            log.info("Research task stopped after ownership loss, taskId={}, error={}",
                    task == null ? null : task.getId(), error.getMessage());
            throw error;
        } catch (Exception error) {
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
            Optional<PersistedFallback> fallback = payload == null
                    ? Optional.empty()
                    : persistOfflineFallbackReport(
                            task.getUserId(), payload.conversationId(), workingState,
                            runningTask, lease, error, ownershipLost);
            if (fallback.isPresent()) {
                String brief = reportRenderer.buildFinalAnswerBrief(workingState);
                PersistedFallback persistedFallback = fallback.get();
                String text = brief == null || brief.isBlank() ? persistedFallback.reportJson() : brief;
                finishWithText(runningTask, lease, payload, text,
                        persistedFallback.reportVersionId(),
                        ResearchTask.ResultKind.OFFLINE_FALLBACK, ownershipLost);
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
            researchTaskService.release(lease);
        }
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
        conversationMessageService.persistAssistantReport(
                payload.conversationId(), task.getUserId(), text, payload.traceId());
        markSucceededForOwner(task, lease, reportVersionId, resultKind, ownershipLost);
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
            return new SubmissionPayload(ticker, query, traceId, conversationId);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid research task submission payload", e);
        }
    }

    private String safeError(Exception error) {
        return error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
    }

    private record SubmissionPayload(String ticker, String query, String traceId, Long conversationId) {
    }

    /**
     * 在研究任务的租约保护下运行 Bull/Bear 辩论，并把结果持久化为报告版本。
     *
     * <p>辩论失败时优先尝试离线兜底报告；兜底也失败才把任务标记为失败并向上抛出。</p>
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
            AnalysisState completed = researchDebateService.runDebate(
                    traceId, conversationId, agentState, 1, 0, null,
                    ownershipGuard(reportTask, ownershipLost),
                    state -> saveHarnessSnapshotForOwner(
                            reportTask, lease, state, ownershipLost));
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
            InvestmentReportVersionService.PersistedReportVersion persisted =
                    investmentReportVersionService.persistReportVersionWithMetadata(
                            userId,
                            conversationId,
                            completed,
                            selectedModel == null ? null : selectedModel.tier().name(),
                            selectedModel == null ? null : selectedModel.modelName()
                    );
            String reportJson = objectMapper.writeValueAsString(
                    persisted.report() == null ? completed.getInvestmentReport() : persisted.report()
            );
            markSucceededForOwner(
                    runningTask, lease, persisted.reportVersionId(),
                    ResearchTask.ResultKind.FULL_REPORT, ownershipLost);
            return reportJson;
        } catch (OwnershipLostException error) {
            throw error;
        } catch (Exception e) {
            ResearchTask failedTask = runningTask == null ? task : runningTask;
            requireTaskOwnership(failedTask, lease, ownershipLost);
            Optional<PersistedFallback> fallbackReport = persistOfflineFallbackReport(
                    userId,
                    conversationId,
                    agentState,
                    failedTask,
                    lease,
                    e,
                    ownershipLost
            );
            if (fallbackReport.isPresent()) {
                PersistedFallback fallback = fallbackReport.get();
                markSucceededForOwner(
                        failedTask, lease, fallback.reportVersionId(),
                        ResearchTask.ResultKind.OFFLINE_FALLBACK, ownershipLost);
                return fallback.reportJson();
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

    Optional<String> tryPersistOfflineFallbackReport(
            String userId,
            Long conversationId,
            AnalysisState agentState,
            ResearchTask runningTask,
            ResearchTaskLeaseService.Lease lease,
            Exception sourceError
    ) {
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        Optional<PersistedFallback> persisted = persistOfflineFallbackReport(
                userId,
                conversationId,
                agentState,
                runningTask,
                lease,
                sourceError,
                ownershipLost
        );
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        PersistedFallback fallback = persisted.get();
        markSucceededForOwner(
                runningTask, lease, fallback.reportVersionId(),
                ResearchTask.ResultKind.OFFLINE_FALLBACK, ownershipLost);
        return Optional.of(fallback.reportJson());
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
        if (agentState == null || runningTask == null || lease == null) {
            return Optional.empty();
        }
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
                    persisted.reportVersionId()
            ));
        } catch (OwnershipLostException ownershipError) {
            throw ownershipError;
        } catch (Exception fallbackError) {
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

    private void applyEvidenceSnapshot(
            AnalysisState state,
            HarnessDecision decision,
            Map<RecoveryAction, Integer> recoveryAttempts
    ) {
        state.setHarnessSnapshot(HarnessSnapshot.from(
                DeepResearchCompletionPolicy.POLICY_ID,
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.EVIDENCE,
                decision,
                recoveryAttempts
        ));
    }

    private Map<RecoveryAction, Integer> recoveryAttempts(List<RecoveryAction> actions) {
        if (actions == null) {
            return Map.of();
        }
        return actions.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        action -> action,
                        action -> 1,
                        Math::max
                ));
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
            int plannedRounds,
            AtomicBoolean ownershipLost
    ) {
        requireTaskOwnership(task, lease, ownershipLost);
        try {
            checkpointService.saveDebateRound(
                    task.getId(), lease.token(), state, roundsCompleted, plannedRounds);
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

    private record PersistedFallback(String reportJson, Long reportVersionId) {
    }

    /** Signals the worker that another execution owns the task and this attempt must stop silently. */
    public static final class OwnershipLostException extends RuntimeException {

        public OwnershipLostException(String message) {
            super(message);
        }

        public OwnershipLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
