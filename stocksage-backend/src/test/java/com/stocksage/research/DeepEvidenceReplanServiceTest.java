package com.stocksage.research;

import com.stocksage.agent.DeepEvidenceReplanner;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.model.dto.ModelInvocationContext;

import com.stocksage.service.TickerResolutionService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.capability.CapabilityGateway;
import com.stocksage.capability.CapabilityInvocationContext;
import com.stocksage.capability.CapabilityResult;
import com.stocksage.capability.LocalNewsSearchCapabilityAdapter;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.AnalysisState.EvidenceReplanAction;
import com.stocksage.model.dto.AnalysisState.EvidenceReplanState;
import com.stocksage.model.dto.AnalysisState.EvidenceReplanStatus;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeepEvidenceReplanServiceTest {

    @Test
    void rejectedOptionalPlannerRecordsCapacitySkipWithoutInvokingProvider() {
        enable();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.shutdown();
        ReflectionTestUtils.setField(service, "agentTaskExecutor", new TaskExecutorAdapter(executor));
        AnalysisState state = state();
        var evidence = evidence(state, mock(EvidenceLedger.class));
        assertThat(service.replan(8L, "user-1", 20L, "trace", evidence, () -> { }, ignored -> { }))
                .isSameAs(evidence);
        assertThat(state.getEvidenceReplanState().status()).isEqualTo(EvidenceReplanStatus.SKIPPED);
        assertThat(state.getEvidenceReplanState().stopReason()).isEqualTo("CAPACITY_UNAVAILABLE");
        verifyNoInteractions(chatClient, capabilityGateway);
    }

    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final CapabilityGateway capabilityGateway = mock(CapabilityGateway.class);
    private final DeepEvidenceCollector evidenceCollector = mock(DeepEvidenceCollector.class);
    private final TickerResolutionService tickerResolutionService = mock(TickerResolutionService.class);
    private final DeepEvidenceReplanService service = new DeepEvidenceReplanService(
            new DeepEvidenceReplanner(chatClient),
            new ObjectMapper(),
            capabilityGateway,
            evidenceCollector,
            tickerResolutionService,
            mock(TraceService.class),
            mock(ChatStreamEmitter.class),
            new TaskExecutorAdapter(Runnable::run)
    );

    @Test
    void providerCapacityRejectionInsideFuturePersistsExplicitOptionalSkip() {
        enable();
        var capacity = new ResearchCapacityExceededException("provider-admission", null);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenThrow(new IllegalStateException("wrapped provider", capacity));
        AnalysisState state = state();
        var original = evidence(state, mock(EvidenceLedger.class));
        List<EvidenceReplanState> saved = new ArrayList<>();
        assertThat(service.replan(8L, "user-1", 20L, "trace", original, () -> { },
                snapshot -> saved.add(snapshot.getEvidenceReplanState()))).isSameAs(original);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).status()).isEqualTo(EvidenceReplanStatus.SKIPPED);
        assertThat(saved.get(0).stopReason()).isEqualTo("CAPACITY_UNAVAILABLE");
        verifyNoInteractions(capabilityGateway, evidenceCollector);
        var emitter = (ChatStreamEmitter) ReflectionTestUtils.getField(service, "chatStreamEmitter");
        verify(emitter).emit(eq("trace"), eq(20L), eq("observation"),
                org.mockito.ArgumentMatchers.contains("在线执行容量已满"));
    }

    @Test
    void nestedBudgetExceptionIsNotConvertedToOptionalPlannerSkip() {
        enable();
        var budget = new ResearchBudgetExceededException(8L, ResearchBudgetExceededException.Reason.MODEL_CALL_LIMIT);
        when(chatClient.prompt().user(anyString()).call().content()).thenThrow(new IllegalStateException("wrapped", budget));
        AnalysisState state = state();
        assertThatThrownBy(() -> service.replan(8L, "user-1", 20L, "trace", evidence(state, mock(EvidenceLedger.class)),
                () -> { }, ignored -> { })).isSameAs(budget);
        verifyNoInteractions(capabilityGateway);
    }

    @Test
    void expiredBudgetRejectsBeforePlannerStarts() {
        enable();
        AnalysisState state = state();
        state.setModelInvocationContext(new ModelInvocationContext(8L, 1, "owner", "trace", "snapshot", System.currentTimeMillis() - 1));
        assertThatThrownBy(() -> service.replan(8L, "user-1", 20L, "trace", evidence(state, mock(EvidenceLedger.class)),
                () -> { }, ignored -> { })).isInstanceOf(ResearchBudgetExceededException.class);
        verifyNoInteractions(chatClient, capabilityGateway);
    }

    @Test
    void persistsExactPlanBeforeCapabilityAndRevalidatesAfterward() throws Exception {
        enable();
        when(tickerResolutionService.resolveExplicitTicker(anyString()))
                .thenAnswer(call -> call.getArgument(0, String.class).contains("MSFT") ? "MSFT" : "");
        assertThat(service.parseProposal("""
                {"decision":"ACT","action":"FOCUSED_NEWS_SEARCH","query":"MSFT risk","reasonCode":"FRESHNESS_GAP"}
                """, "AAPL", 1).act()).isFalse();

        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("""
                {"decision":"ACT","action":"FOCUSED_NEWS_SEARCH","query":"regulatory risk","reasonCode":"FRESHNESS_GAP"}
                """);
        AnalysisState state = state();
        EvidenceLedger beforeLedger = mock(EvidenceLedger.class);
        EvidenceLedger afterLedger = mock(EvidenceLedger.class);
        when(beforeLedger.usableEvidenceIds()).thenReturn(Set.of("base"));
        when(afterLedger.usableEvidenceIds()).thenReturn(Set.of("base", "focused"));
        state.setEvidenceLedger(beforeLedger);
        DeepEvidenceCollector.EvidenceCollection before = evidence(state, beforeLedger);
        DeepEvidenceCollector.EvidenceCollection after = evidence(state, afterLedger);
        CapabilityResult result = new CapabilityResult(
                LocalNewsSearchCapabilityAdapter.ID,
                "local",
                CapabilityResult.Status.SUCCESS,
                "{\"provider\":\"test\",\"results\":[{\"link\":\"https://example.com/a\"}]}",
                90,
                12
        );
        List<String> events = new ArrayList<>();
        List<EvidenceReplanState> checkpoints = new ArrayList<>();
        when(capabilityGateway.invoke(
                eq(LocalNewsSearchCapabilityAdapter.ID), any(Map.class), any(CapabilityInvocationContext.class)))
                .thenAnswer(call -> {
                    events.add("capability");
                    CapabilityInvocationContext context = call.getArgument(2);
                    assertThat(context.userQuery()).isEqualTo(state.getQuery());
                    assertThat(context.userId()).isEqualTo("user-1");
                    assertThat(call.getArgument(1, Map.class))
                            .containsEntry("query", "AAPL regulatory risk")
                            .containsEntry("maxResults", 5);
                    return result;
                });
        when(evidenceCollector.appendFocusedNews(before, result, "trace-1")).thenReturn(after);

        DeepEvidenceCollector.EvidenceCollection updated = service.replan(
                7L,
                "user-1",
                20L,
                "trace-1",
                before,
                () -> { },
                checkpointState -> {
                    EvidenceReplanState snapshot = checkpointState.getEvidenceReplanState();
                    checkpoints.add(snapshot);
                    events.add("checkpoint:" + snapshot.status());
                }
        );

        assertThat(updated).isSameAs(after);
        assertThat(events).containsExactly(
                "checkpoint:PLANNED", "capability", "checkpoint:REVALIDATED");
        assertThat(checkpoints).extracting(EvidenceReplanState::status)
                .containsExactly(EvidenceReplanStatus.PLANNED, EvidenceReplanStatus.REVALIDATED);
        assertThat(checkpoints.get(1).effectKey()).isEqualTo(checkpoints.get(0).effectKey());
        assertThat(checkpoints.get(1).addedEvidenceIds()).containsExactly("focused");
    }

    @Test
    void takeoverExecutesDurablePlanWithoutCallingPlannerAgain() throws Exception {
        enable();
        when(tickerResolutionService.resolveExplicitTicker(anyString())).thenReturn("");
        String query = "AAPL regulatory risk";
        String effectKey = "deep-replan:8:v1:focused-news:"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(query.getBytes(StandardCharsets.UTF_8)));
        AnalysisState state = state();
        state.setEvidenceReplanState(new EvidenceReplanState(
                1,
                EvidenceReplanStatus.PLANNED,
                EvidenceReplanAction.FOCUSED_NEWS_SEARCH,
                query,
                "FRESHNESS_GAP",
                effectKey,
                List.of(),
                null
        ));
        EvidenceLedger beforeLedger = mock(EvidenceLedger.class);
        EvidenceLedger afterLedger = mock(EvidenceLedger.class);
        when(beforeLedger.usableEvidenceIds()).thenReturn(Set.of());
        when(afterLedger.usableEvidenceIds()).thenReturn(Set.of());
        state.setEvidenceLedger(beforeLedger);
        DeepEvidenceCollector.EvidenceCollection before = evidence(state, beforeLedger);
        DeepEvidenceCollector.EvidenceCollection after = evidence(state, afterLedger);
        CapabilityResult result = new CapabilityResult(
                LocalNewsSearchCapabilityAdapter.ID,
                "local",
                CapabilityResult.Status.SUCCESS,
                "{\"provider\":\"test\",\"results\":[]}",
                32,
                1
        );
        when(capabilityGateway.invoke(anyString(), any(), any())).thenReturn(result);
        when(evidenceCollector.appendFocusedNews(before, result, "trace-2")).thenReturn(after);

        service.replan(8L, "user-1", 20L, "trace-2", before, () -> { }, stateValue -> { });

        verifyNoInteractions(chatClient);
        verify(capabilityGateway).invoke(
                eq(LocalNewsSearchCapabilityAdapter.ID), any(Map.class), any(CapabilityInvocationContext.class));
        verify(capabilityGateway, never()).invoke(eq("mcp.news.search"), any(), any());
        assertThat(state.getEvidenceReplanState().status()).isEqualTo(EvidenceReplanStatus.REVALIDATED);
        assertThat(state.getEvidenceReplanState().effectKey()).isEqualTo(effectKey);
    }

    @Test
    void strictRevalidatedSaveFailureRestoresDurableBaselineState() throws Exception {
        enable();
        when(tickerResolutionService.resolveExplicitTicker(anyString())).thenReturn("");
        String query = "AAPL regulatory risk";
        String effectKey = "deep-replan:9:v1:focused-news:"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(query.getBytes(StandardCharsets.UTF_8)));
        AnalysisState state = state();
        EvidenceReplanState planned = new EvidenceReplanState(
                1,
                EvidenceReplanStatus.PLANNED,
                EvidenceReplanAction.FOCUSED_NEWS_SEARCH,
                query,
                "FRESHNESS_GAP",
                effectKey,
                List.of(),
                null
        );
        state.setEvidenceReplanState(planned);
        EvidenceLedger baselineLedger = mock(EvidenceLedger.class);
        EvidenceLedger appendedLedger = mock(EvidenceLedger.class);
        when(baselineLedger.usableEvidenceIds()).thenReturn(Set.of("base"));
        when(appendedLedger.usableEvidenceIds()).thenReturn(Set.of("base", "focused"));
        state.setEvidenceLedger(baselineLedger);
        DeepEvidenceCollector.EvidenceCollection before = evidence(state, baselineLedger);
        CapabilityResult result = new CapabilityResult(
                LocalNewsSearchCapabilityAdapter.ID,
                "local",
                CapabilityResult.Status.SUCCESS,
                "{\"provider\":\"test\",\"results\":[]}",
                32,
                1
        );
        when(capabilityGateway.invoke(anyString(), any(), any())).thenReturn(result);
        when(evidenceCollector.appendFocusedNews(before, result, "trace-3"))
                .thenAnswer(call -> {
                    state.setEvidenceLedger(appendedLedger);
                    state.setNewsReport("focused news");
                    return evidence(state, appendedLedger);
                });

        assertThatThrownBy(() -> service.replan(
                9L,
                "user-1",
                20L,
                "trace-3",
                before,
                () -> { },
                checkpointState -> {
                    if (checkpointState.getEvidenceReplanState().status()
                            == EvidenceReplanStatus.REVALIDATED) {
                        throw new IllegalStateException("checkpoint unavailable");
                    }
                }
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("checkpoint unavailable");

        assertThat(state.getEvidenceLedger()).isSameAs(baselineLedger);
        assertThat(state.getNewsReport()).isEqualTo("news");
        assertThat(state.getEvidenceReplanState()).isEqualTo(planned);
    }

    private void enable() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxSearchResults", 5);
        ReflectionTestUtils.setField(service, "toolTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(service, "plannerTimeoutSeconds", 1L);
    }

    private AnalysisState state() {
        return AnalysisState.builder()
                .modelInvocationContext(new ModelInvocationContext(8L, 1, "owner", "trace", "snapshot", System.currentTimeMillis() + 600_000))
                .query("Should I buy AAPL given regulatory risk?")
                .primaryTicker("AAPL")
                .fundamentalsReport("fundamentals")
                .marketReport("market")
                .newsReport("news")
                .build();
    }

    private DeepEvidenceCollector.EvidenceCollection evidence(
            AnalysisState state,
            EvidenceLedger ledger
    ) {
        return new DeepEvidenceCollector.EvidenceCollection(
                "context",
                state,
                true,
                true,
                true,
                true,
                true,
                ledger,
                new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of())
        );
    }
}
