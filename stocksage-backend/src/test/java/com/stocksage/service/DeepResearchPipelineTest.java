package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.ResearchDebateService;
import com.stocksage.agent.ModelTier;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.tool.ChatStreamEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepResearchPipelineTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final InvestmentReportVersionService investmentReportVersionService = mock(InvestmentReportVersionService.class);
    private final ResearchTaskService researchTaskService = mock(ResearchTaskService.class);
    private final ResearchDebateService researchDebateService = mock(ResearchDebateService.class);
    private final OfflineDemoSampleService offlineDemoSampleService = mock(OfflineDemoSampleService.class);
    private final ResearchTaskCheckpointService checkpointService = mock(ResearchTaskCheckpointService.class);
    private final DeepEvidenceCollector evidenceCollector = mock(DeepEvidenceCollector.class);
    private final ReportMarkdownRenderer reportRenderer = mock(ReportMarkdownRenderer.class);
    private final ConversationMessageService conversationMessageService = mock(ConversationMessageService.class);
    private final ChatStreamEmitter chatStreamEmitter = mock(ChatStreamEmitter.class);
    private final DeepResearchPipeline pipeline = new DeepResearchPipeline(
            researchTaskService,
            researchDebateService,
            investmentReportVersionService,
            offlineDemoSampleService,
            objectMapper,
            mock(TaskScheduler.class),
            checkpointService,
            evidenceCollector,
            reportRenderer,
            conversationMessageService,
            chatStreamEmitter
    );

    @Test
    void freshTaskRunsFullPipelineAndEmitsTaskFinal() {
        ResearchTask task = pendingTask(7L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState evidenceState = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        AnalysisState completed = AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .investmentReport(InvestmentReport.builder().ticker("AAPL").recommendation("BUY").build())
                .build();
        when(checkpointService.load(7L)).thenReturn(Optional.empty());
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenReturn(new DeepEvidenceCollector.EvidenceCollection("ctx", evidenceState, true, true, true, true));
        when(investmentReportVersionService.findReusableReport("u_001", 20L, evidenceState))
                .thenReturn(Optional.empty());
        when(researchDebateService.runDebate(eq("trace-1"), eq(20L), eq(evidenceState), eq(1), eq(0), any()))
                .thenReturn(completed);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, completed, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        completed.getInvestmentReport(), 99L, false));
        when(reportRenderer.buildFinalAnswerBrief(completed)).thenReturn("brief");

        pipeline.runFullPipeline(task, lease);

        verify(checkpointService).saveEvidence(7L, evidenceState);
        verify(researchTaskService).markStageForOwner(task, lease.token(), ResearchTask.Stage.AGENT_DEBATE);
        verify(checkpointService).saveSynthesis(7L, completed);
        verify(conversationMessageService).persistAssistantReport(20L, "u_001", "brief", "trace-1");
        verify(researchTaskService).markSucceededForOwner(task, lease.token(), 99L);
        verify(checkpointService).deleteForTask(7L);
        verify(chatStreamEmitter).emit("trace-1", 20L, "task-final", "brief");
    }

    @Test
    void insufficientEvidenceSucceedsWithoutReportVersionOrDebate() {
        ResearchTask task = pendingTask(8L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        when(checkpointService.load(8L)).thenReturn(Optional.empty());
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenReturn(new DeepEvidenceCollector.EvidenceCollection("ctx", state, false, true, false, false));
        when(reportRenderer.buildInsufficientEvidenceReport("AAPL", true, false, false))
                .thenReturn("insufficient");

        pipeline.runFullPipeline(task, lease);

        verify(researchDebateService, never()).runDebate(any(), any(), any(), any(Integer.class), any(Integer.class), any());
        verify(investmentReportVersionService, never()).persistReportVersionWithMetadata(any(), any(), any(), any(), any());
        verify(conversationMessageService).persistAssistantReport(20L, "u_001", "insufficient", "trace-1");
        verify(researchTaskService).markSucceededForOwner(task, lease.token(), null);
        verify(chatStreamEmitter).emit("trace-1", 20L, "task-final", "insufficient");
    }

    @Test
    void checkpointAfterTwoRoundsResumesAtRoundThreeWithoutCollectingEvidence() {
        ResearchTask task = pendingTask(9L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        AnalysisState completed = AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .investmentReport(InvestmentReport.builder().ticker("AAPL").recommendation("HOLD").build())
                .build();
        when(checkpointService.load(9L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.AGENT_DEBATE, 2, 3, state)));
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.AGENT_DEBATE)).thenReturn(task);
        when(researchDebateService.runDebate(eq("trace-1"), eq(20L), eq(state), eq(3), eq(3), any()))
                .thenReturn(completed);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, completed, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        completed.getInvestmentReport(), 100L, false));
        when(reportRenderer.buildFinalAnswerBrief(completed)).thenReturn("resumed");

        pipeline.runFullPipeline(task, lease);

        verify(evidenceCollector, never()).collect(any(), any(), any(), any());
        verify(researchDebateService).runDebate(eq("trace-1"), eq(20L), eq(state), eq(3), eq(3), any());
        verify(researchTaskService).markSucceededForOwner(task, lease.token(), 100L);
    }

    @Test
    void persistsOfflineFallbackReportAndCompletesTaskWhenDebateFails() {
        AnalysisState state = AnalysisState.builder()
                .primaryTicker("NVDA")
                .query("Should I buy NVDA?")
                .build();
        InvestmentReport fallback = InvestmentReport.builder()
                .ticker("NVDA")
                .recommendation("HOLD")
                .modelTier("DEMO")
                .modelName("offline-rule-fallback")
                .citations(List.of("[offline sample] deterministic fixture"))
                .analystSummary("offline demo fallback report")
                .build();
        ResearchTask task = new ResearchTask();
        task.setId(7L);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                "investment-report:nvda",
                "owner-token",
                ResearchTaskLeaseService.Backend.PROCESS
        );
        when(offlineDemoSampleService.buildFallbackReport(state)).thenReturn(Optional.of(fallback));
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                eq("u_001"),
                eq(20L),
                eq(state),
                eq("DEMO"),
                eq("offline-rule-fallback")
        )).thenReturn(new InvestmentReportVersionService.PersistedReportVersion(fallback, 99L, false));

        Optional<String> reportJson = pipeline.tryPersistOfflineFallbackReport(
                "u_001",
                20L,
                state,
                task,
                lease,
                new IllegalStateException("DashScope key missing")
        );

        assertThat(reportJson).isPresent();
        assertThat(reportJson.orElseThrow()).contains("offline-rule-fallback");
        assertThat(state.getInvestmentReport()).isSameAs(fallback);
        verify(researchTaskService).markStageForOwner(task, "owner-token", ResearchTask.Stage.REPORT_PERSIST);
        verify(researchTaskService).markSucceededForOwner(task, "owner-token", 99L);
    }

    private ResearchTask pendingTask(Long id) {
        ResearchTask task = new ResearchTask();
        task.setId(id);
        task.setUserId("u_001");
        task.setConversationId(20L);
        task.setTicker("AAPL");
        task.setPayloadJson("{\"ticker\":\"AAPL\",\"query\":\"q\",\"traceId\":\"trace-1\",\"conversationId\":20}");
        return task;
    }

    private ResearchTaskLeaseService.Lease lease() {
        return new ResearchTaskLeaseService.Lease(
                "deep-submit:aapl",
                "owner-token",
                ResearchTaskLeaseService.Backend.PROCESS
        );
    }
}
