package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.ResearchDebateService;
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
import java.util.concurrent.ScheduledFuture;

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

        try {
            payload = parsePayload(task);
            Optional<ResearchTaskCheckpointService.CheckpointState> checkpoint = checkpointService.load(task.getId());
            workingState = checkpoint.map(ResearchTaskCheckpointService.CheckpointState::state).orElse(null);
            ResearchTask.Stage resumeStage = checkpoint
                    .map(ResearchTaskCheckpointService.CheckpointState::stageCompleted)
                    .orElse(ResearchTask.Stage.DATA_PREFETCH);
            runningTask = researchTaskService.startAttempt(task, lease.token(), resumeStage);
            attemptStarted = true;
            heartbeat = startResearchTaskHeartbeat(runningTask, lease);

            int roundsDone = checkpoint
                    .map(ResearchTaskCheckpointService.CheckpointState::debateRoundsCompleted)
                    .orElse(0);
            int plannedRounds = checkpoint
                    .map(ResearchTaskCheckpointService.CheckpointState::plannedRounds)
                    .orElse(0);

            if (checkpoint.isEmpty()) {
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "thought",
                        "开始收集深度研究证据……");
                DeepEvidenceCollector.EvidenceCollection evidence = evidenceCollector.collect(
                        payload.ticker(), payload.query(), payload.traceId(), payload.conversationId());
                workingState = evidence.state();
                if (!evidence.sufficientForRecommendation()) {
                    String text = reportRenderer.buildInsufficientEvidenceReport(
                            payload.ticker(), evidence.tickerResolved(), evidence.fundamentalsOk(), evidence.marketOk());
                    finishWithText(runningTask, lease, payload, text, null);
                    return;
                }

                investmentReportVersionService.prepareHashes(workingState);
                Optional<InvestmentReport> reusable = investmentReportVersionService.findReusableReport(
                        task.getUserId(), payload.conversationId(), workingState);
                if (reusable.isPresent()) {
                    workingState.setInvestmentReport(reusable.get());
                    finishWithText(runningTask, lease, payload,
                            reportRenderer.buildFinalAnswerBrief(workingState), null);
                    return;
                }

                checkpointService.saveEvidence(task.getId(), workingState);
                researchTaskService.markStageForOwner(
                        runningTask, lease.token(), ResearchTask.Stage.AGENT_DEBATE);
            } else {
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "observation",
                        "从断点恢复研究任务：已完成 " + roundsDone + " 轮辩论。");
            }

            if (workingState.getInvestmentReport() == null) {
                AnalysisState completed = researchDebateService.runDebate(
                        payload.traceId(), payload.conversationId(), workingState,
                        roundsDone + 1, plannedRounds,
                        (state, rounds, planned) -> checkpointService.saveDebateRound(
                                task.getId(), state, rounds, planned));
                checkpointService.saveSynthesis(task.getId(), completed);
                researchTaskService.markStageForOwner(
                        runningTask, lease.token(), ResearchTask.Stage.REPORT_SYNTHESIS);
                workingState = completed;
            }

            researchTaskService.markStageForOwner(
                    runningTask, lease.token(), ResearchTask.Stage.REPORT_PERSIST);
            InvestmentReportVersionService.PersistedReportVersion persisted =
                    investmentReportVersionService.persistReportVersionWithMetadata(
                            task.getUserId(), payload.conversationId(), workingState,
                            ModelTier.STRONG.name(), null);
            String brief = reportRenderer.buildFinalAnswerBrief(workingState);
            conversationMessageService.persistAssistantReport(
                    payload.conversationId(), task.getUserId(), brief, payload.traceId());
            researchTaskService.markSucceededForOwner(
                    runningTask, lease.token(), persisted.reportVersionId());
            checkpointService.deleteForTask(task.getId());
            chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "task-final", brief);
        } catch (Exception error) {
            if (!attemptStarted) {
                researchTaskService.markFailed(task, error.getMessage());
                if (payload != null) {
                    chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "error",
                            "深度研究任务执行失败：" + safeError(error));
                }
                return;
            }

            Optional<String> fallback = payload == null
                    ? Optional.empty()
                    : tryPersistOfflineFallbackReport(
                            task.getUserId(), payload.conversationId(), workingState,
                            runningTask, lease, error);
            if (fallback.isPresent()) {
                String brief = reportRenderer.buildFinalAnswerBrief(workingState);
                String text = brief == null || brief.isBlank() ? fallback.get() : brief;
                conversationMessageService.persistAssistantReport(
                        payload.conversationId(), task.getUserId(), text, payload.traceId());
                checkpointService.deleteForTask(task.getId());
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "task-final", text);
                return;
            }

            boolean failed = researchTaskService.markFailedForOwner(
                    runningTask, lease.token(), error.getMessage());
            if (payload != null) {
                chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "error",
                        "深度研究任务执行失败：" + safeError(error));
            }
            if (!failed) {
                throw new IllegalStateException("research task lost ownership before failure update", error);
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
            Long reportVersionId
    ) {
        conversationMessageService.persistAssistantReport(
                payload.conversationId(), task.getUserId(), text, payload.traceId());
        researchTaskService.markSucceededForOwner(task, lease.token(), reportVersionId);
        checkpointService.deleteForTask(task.getId());
        chatStreamEmitter.emit(payload.traceId(), payload.conversationId(), "task-final", text);
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
            heartbeat = startResearchTaskHeartbeat(runningTask, lease);
            AnalysisState completed = researchDebateService.runDebate(traceId, conversationId, agentState);
            researchTaskService.markStageForOwner(runningTask, lease.token(), ResearchTask.Stage.REPORT_PERSIST);
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
            researchTaskService.markSucceededForOwner(runningTask, lease.token(), persisted.reportVersionId());
            return reportJson;
        } catch (Exception e) {
            Optional<String> fallbackReport = tryPersistOfflineFallbackReport(
                    userId,
                    conversationId,
                    agentState,
                    runningTask == null ? task : runningTask,
                    lease,
                    e
            );
            if (fallbackReport.isPresent()) {
                return fallbackReport.get();
            }
            try {
                researchTaskService.markFailedForOwner(
                        runningTask == null ? task : runningTask,
                        lease.token(),
                        e.getMessage()
                );
            } catch (Exception taskError) {
                log.warn("Failed to mark research task failed, taskId={}, error={}",
                        task == null ? null : task.getId(), taskError.getMessage());
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
            researchTaskService.markStageForOwner(runningTask, lease.token(), ResearchTask.Stage.REPORT_PERSIST);
            InvestmentReportVersionService.PersistedReportVersion persisted =
                    investmentReportVersionService.persistReportVersionWithMetadata(
                            userId,
                            conversationId,
                            agentState,
                            OfflineDemoSampleService.MODEL_TIER,
                            OfflineDemoSampleService.MODEL_NAME
                    );
            researchTaskService.markSucceededForOwner(runningTask, lease.token(), persisted.reportVersionId());
            log.warn("Research debate failed, persisted offline fallback report for ticker={}, taskId={}, sourceError={}",
                    agentState.getPrimaryTicker(), runningTask.getId(), sourceError == null ? "" : sourceError.getMessage());
            return Optional.of(objectMapper.writeValueAsString(
                    persisted.report() == null ? report : persisted.report()
            ));
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
            ResearchTaskLeaseService.Lease lease
    ) {
        return researchHeartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean taskHeartbeat = researchTaskService.heartbeatForOwner(task, lease.token());
                boolean leaseRenewed = researchTaskService.renewLease(lease);
                if (!taskHeartbeat || !leaseRenewed) {
                    log.warn("Research task heartbeat could not renew ownership, taskId={}, taskHeartbeat={}, leaseRenewed={}",
                            task.getId(), taskHeartbeat, leaseRenewed);
                }
            } catch (Exception e) {
                log.warn("Research task heartbeat failed, taskId={}, error={}", task.getId(), e.getMessage());
            }
        }, Instant.now().plusSeconds(60), Duration.ofSeconds(60));
    }
}
