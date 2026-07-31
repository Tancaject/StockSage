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
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RecoveryLifecycle;
import com.stocksage.harness.HarnessModels.SuspendedRecovery;
import com.stocksage.model.dto.AnalysisState;
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
    private final ResearchTaskPublicationTransaction publicationTransaction;
    private final ChatStreamEmitter chatStreamEmitter;
    private final TraceService traceService;

    /** 整条 DEEP 管线在 worker 内运行，请求线程只负责创建任务和订阅事件。 */
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
            int plannedRounds = checkpoint
                    .map(ResearchTaskCheckpointService.CheckpointState::plannedRounds)
                    .orElse(0);
            if (hasPendingReportRecovery(workingState)) {
                // Older inline checkpoints stored the strict REPORT/PLANNED payload while their
                // checkpoint columns still said DATA_PREFETCH / 0 rounds. Recover the durable
                // debate progress from the paired turns so takeover reaches Manager directly
                // instead of replaying Bull/Bear and the planner.
                int durableRounds = completedDebateRounds(workingState);
                roundsDone = Math.max(roundsDone, durableRounds);
                plannedRounds = Math.max(plannedRounds, durableRounds);
            }

            DeepEvidenceCollector.EvidenceCollection evidence = null;
            Map<RecoveryAction, Integer> recoveryAttempts = Map.of();
            if (checkpoint.isEmpty()) {
                requireTaskOwnership(runningTask, lease, ownershipLost);
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "thought",
                        "开始收集深度研究证据……");
                evidence = evidenceCollector.collect(
                        payload.ticker(), payload.query(), payload.traceId(), payload.conversationId());
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
                // A checkpoint hash is historical metadata. Any current-policy PASS, including a
                // recovered or policy-drift-only PASS, must bind reuse/persistence to the current
                // ledger and policy contract.
                investmentReportVersionService.prepareHashes(workingState);

                if (checkpoint.isEmpty() || resumeFromEvidenceBoundary) {
                    requireTaskOwnership(runningTask, lease, ownershipLost);
                    Optional<InvestmentReport> reusable =
                            investmentReportVersionService.findReusableReport(
                                    task.getUserId(), payload.conversationId(), workingState);
                    if (reusable.isPresent()) {
                        workingState.setInvestmentReport(reusable.get());
                        finishWithText(runningTask, lease, payload,
                                reportRenderer.buildFinalAnswerBrief(workingState), null,
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
            ResearchTask fallbackTask = runningTask;
            SubmissionPayload fallbackPayload = payload;
            AnalysisState fallbackState = workingState;
            Optional<PersistedFallback> fallback = fallbackPayload == null
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
     * Background-task completion is authoritative for its trace lifecycle.
     *
     * <p>The SSE subscriber may disconnect while the durable task keeps running. Closing the
     * trace from the request stream would therefore record a false cancellation. Only a terminal
     * task owned by this pipeline may close the task trace; ownership-loss paths leave it open for
     * the takeover worker.</p>
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
                    Math.max(0, durationMs)
            );
        } catch (Exception error) {
            // The business terminal state has already committed. Do not replay the research or
            // risk duplicate publication because only the observability update failed.
            log.warn(
                    "Research task terminal trace update failed, taskId={}, traceId={}, status={}, error={}",
                    task.getId(),
                    payload.traceId(),
                    status,
                    error.getMessage()
            );
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
        publicationTransaction.executeForUser(task.getUserId(), () -> {
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
        String text = publicationTransaction.executeForUser(task.getUserId(), () -> {
            InvestmentReportVersionService.PersistedReportVersion persisted =
                    investmentReportVersionService.persistReportVersionWithMetadata(
                            task.getUserId(),
                            payload.conversationId(),
                            state,
                            modelTier,
                            modelName
                    );
            // Same-snapshot publication may replace the candidate with the already committed
            // canonical report. Render only after that decision, while still under the user lock.
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
            saveEvidenceForOwner(reportTask, lease, agentState, ownershipLost);
            markStageForOwner(
                    reportTask, lease, ResearchTask.Stage.AGENT_DEBATE, ownershipLost);
            AnalysisState completed = researchDebateService.runDebate(
                    traceId, conversationId, agentState, 1, 0,
                    (state, rounds, planned) -> saveDebateRoundForOwner(
                            reportTask, lease, state, rounds, planned, ownershipLost),
                    ownershipGuard(reportTask, ownershipLost),
                    state -> saveHarnessSnapshotForOwner(
                            reportTask, lease, state, ownershipLost));
            saveSynthesisForOwner(reportTask, lease, completed, ownershipLost);
            markStageForOwner(
                    reportTask, lease, ResearchTask.Stage.REPORT_SYNTHESIS, ownershipLost);
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
        } catch (Exception e) {
            ResearchTask failedTask = runningTask == null ? task : runningTask;
            requireTaskOwnership(failedTask, lease, ownershipLost);
            Optional<PersistedFallback> fallbackReport =
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
                    persisted.reportVersionId(),
                    null
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
        log.info("Resuming bounded evidence recovery, taskId={}, effectKey={}, actions={}",
                task.getId(), effectKey, recoveryActions);

        DeepEvidenceCollector.EvidenceCollection recovered = evidenceCollector.recover(
                evidence,
                recoveryActions,
                previousAttempts,
                payload.traceId(),
                payload.conversationId()
        );
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
        // Close the crash window between the read-only effect and the ordinary evidence checkpoint.
        saveHarnessSnapshotForOwner(task, lease, recovered.state(), ownershipLost);
        return recovered;
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
        while (hasDebateTurn(state, completed + 1, AnalysisState.DebateTurn.Side.BULL)
                && hasDebateTurn(state, completed + 1, AnalysisState.DebateTurn.Side.BEAR)) {
            completed++;
        }
        return completed;
    }

    private boolean hasDebateTurn(
            AnalysisState state,
            int round,
            AnalysisState.DebateTurn.Side side
    ) {
        return state.getDebateTurns().stream()
                .anyMatch(turn -> turn != null
                        && turn.round() == round
                        && turn.side() == side);
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

    private record PersistedFallback(
            String reportJson,
            Long reportVersionId,
            String deliveryText
    ) {
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
