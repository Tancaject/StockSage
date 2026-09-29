package com.stocksage.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.agent.*;
import com.stocksage.agent.intent.*;
import com.stocksage.evolution.*;
import com.stocksage.evidence.adapter.EvidenceEnvelopeMapper;
import com.stocksage.harness.*;
import com.stocksage.knowledge.ResearchMemoryService;
import com.stocksage.memory.*;
import com.stocksage.model.dto.ChatRequest;
import com.stocksage.model.entity.*;
import com.stocksage.rag.RagService;
import com.stocksage.repository.AgentTraceRepository;
import com.stocksage.service.*;
import com.stocksage.tool.*;
import com.stocksage.trace.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual chat/prefetch/analyst/final-answer/Trace code; model, storage and routing are local test doubles. */
class OrdinaryEvolutionDrillTest {
    private static final String USER = "offline-drill-account";
    private static final String QUERY = "比较AAPL已提供的两个年度收入";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void exportFourFreshOrdinaryRunsAcrossInflightWithdrawal() throws Exception {
        Path output = Files.createTempDirectory(Files.createDirectories(Path.of("target/evolution-drills")), "ordinary-");
        Path controls = Files.createDirectory(output.resolve("controls"));
        var executor = new TaskExecutorAdapter(Runnable::run);
        var ticker = mock(TickerResolutionService.class);
        when(ticker.resolveExplicitTicker(anyString())).thenReturn("AAPL");
        when(ticker.resolvePrimaryTicker(anyString(), anyLong())).thenReturn("AAPL");
        when(ticker.normalizeStructuredTicker(anyString())).thenAnswer(call -> call.getArgument(0));
        var tools = mock(FundamentalsTools.class);
        when(tools.getFinancialReports("AAPL", "annual", 2)).thenReturn(financialFixture());

        var stored = new ConcurrentHashMap<String, AgentTrace>();
        var repository = mock(AgentTraceRepository.class);
        when(repository.save(any(AgentTrace.class))).thenAnswer(call -> {
            var trace = call.<AgentTrace>getArgument(0);
            stored.put(trace.getTraceId(), trace);
            return trace;
        });
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        var traces = new TraceService(repository, json, mock(PhoenixTraceService.class));
        var relay = mock(TraceEventRelay.class);
        var emitter = mock(ChatStreamEmitter.class);
        var channels = new ConcurrentHashMap<String, Sinks.Many<TraceEventStore.StoredEvent>>();
        when(relay.live(anyString(), isNull())).thenAnswer(call -> channels.computeIfAbsent(call.getArgument(0),
                ignored -> Sinks.many().unicast().onBackpressureBuffer()).asFlux());
        doAnswer(call -> {
            String id = call.getArgument(0);
            var channel = channels.get(id);
            channel.tryEmitNext(new TraceEventStore.StoredEvent("1-0", "{\"type\":\"stream-end\"}"));
            channel.tryEmitComplete();
            return null;
        }).when(emitter).emit(anyString(), eq(81L), eq("stream-end"), eq(""));

        var baseline = AgentPolicyBundle.baseline();
        var candidate = AgentPolicyBundle.create("offline-drill-candidate", baseline.bundleId(), "先核对期间与单位，再比较两个已提供年度的收入。");
        var analystCalls = new AtomicInteger();
        var finalCalls = new AtomicInteger();
        var withdrawnAt = new AtomicReference<String>();
        var analystModel = mock(ChatModel.class);
        when(analystModel.call(any(Prompt.class))).thenAnswer(call -> {
            var prompt = call.<Prompt>getArgument(0);
            assertThat(prompt.getOptions().getModel()).isEqualTo("offline-model");
            int index = analystCalls.incrementAndGet();
            if (index == 2) {
                // Separate real clock instants so the exported timeline proves a straddled invocation.
                Thread.sleep(2);
                Files.createFile(controls.resolve("REVOKE." + candidate.bundleId()));
                withdrawnAt.set(Instant.now().toString());
                Thread.sleep(2);
            }
            boolean selectedCandidate = prompt.getInstructions().get(0).getText().contains(candidate.method());
            assertThat(selectedCandidate).isEqualTo(index <= 2);
            return response((selectedCandidate ? "candidate" : "baseline") + "-analysis-" + index + " [E1]");
        });
        var answerModel = mock(ChatModel.class);
        when(answerModel.stream(any(Prompt.class))).thenAnswer(call -> {
            int index = finalCalls.incrementAndGet();
            var prompt = call.<Prompt>getArgument(0);
            String expected = (index <= 2 ? "candidate" : "baseline") + "-analysis-" + index;
            assertThat(prompt.getInstructions()).anyMatch(message -> message.getText().contains(expected));
            return Flux.just(response("fresh-final-" + index + " [E1]"));
        });
        var coordinator = spy(new Coordinator(mock(IntentRecognitionService.class), ChatClient.builder(answerModel).build(),
                ticker, new RoutePlanCatalog()));
        ReflectionTestUtils.setField(coordinator, "standardModel", "offline-model");
        ReflectionTestUtils.setField(coordinator, "modelRoutingTemperature", 0.7);
        ReflectionTestUtils.setField(coordinator, "modelRoutingMaxOutputTokens", 4096);
        var plan = new ExecutionPlan(PlanRoute.FUNDAMENTALS, "fundamentals", "offline drill", List.of(
                PlanAction.GET_FINANCIAL_REPORTS, PlanAction.FUNDAMENTALS_AGENT), "", ModelTier.STANDARD, null, QUERY,
                new ReadRequest("3m", "1d", "annual", 2, false, ""));
        var decision = new IntentDecision(null, null, PlanRoute.FUNDAMENTALS, 1, null, null,
                Map.of(), QUERY, Map.of(), false, List.of());
        var intent = new IntentRecognitionResult(decision, "FUNDAMENTALS", true, "", List.of(), "", 0);
        doReturn(intent).when(coordinator).recognizeIntent(anyString(), anyList(), anyInt(), anyBoolean(), anyList());
        doReturn(plan).when(coordinator).planRecognized(any(), anyString(), anyInt());
        var runtime = mock(AgentRuntimeConfiguration.class);
        when(runtime.snapshot()).thenReturn(Map.of("fundamentals", Map.of("model", "offline-model", "temperature", 0.7, "maxTokens", 4096)));
        when(runtime.chatProviderSnapshot()).thenReturn(Map.of("protocol", "TEST_DOUBLE"));
        var conditions = FundamentalsRuntimeIdentity.conditions(FundamentalsRuntimeIdentity.modelConfiguration(runtime,
                List.of(coordinator.finalAnswerInvocation(coordinator.selectFinalAnswerModel(ModelTier.STANDARD, false, false))), 1, 24000),
                "a".repeat(64), FundamentalsRuntimeIdentity.memoryHash("", "", List.of()));
        var scope = new ApprovedMethodArtifact.Scope(List.of("period-comparison"), List.of("dated-values"),
                List.of("evidence-reading"), "仅比较本轮已提供的完整年度数据");
        var scopeFields = json.convertValue(scope, Map.class);
        String evaluator = System.getProperty("evolution.evaluator-sha256", "c".repeat(64));
        var pack = Map.<String, Object>of("kind", "METHOD_RELEASE_PACKAGE", "schemaVersion", 1,
                "evaluatorSha256", evaluator, "bundle", json.convertValue(candidate, Map.class), "expectedComparisonIdentity", conditions,
                "scope", scopeFields, "rollback", Map.of("bundleId", baseline.bundleId(), "bundleSha256", baseline.contentSha256()));
        String packageHash = FundamentalsRuntimeIdentity.hash(pack);
        var approvedPayload = Map.<String, Object>of("kind", "APPROVED_METHOD_PACKAGE", "schemaVersion", 1,
                "status", "APPROVED_PENDING_VALIDATION", "packageSha256", packageHash, "approvalSha256", "d".repeat(64), "package", pack);
        String approvedHash = FundamentalsRuntimeIdentity.hash(approvedPayload);
        Instant approvedAt = Instant.now().minusSeconds(30), expiresAt = Instant.now().plusSeconds(600);
        var drillPayload = new LinkedHashMap<String, Object>();
        drillPayload.putAll(Map.of("kind", "METHOD_ACTIVATION", "schemaVersion", 1, "mode", "DRILL",
                "activationId", "offline-drill", "approvedArtifactSha256", approvedHash, "packageSha256", packageHash,
                "approvalSha256", "d".repeat(64), "scope", scopeFields, "expectedComparisonIdentity", conditions));
        drillPayload.putAll(Map.of("approver", "offline-fixture-publisher", "internalAccountIds", List.of(USER),
                "approvedAt", approvedAt.toString(), "expiresAt", expiresAt.toString()));
        drillPayload.put("rollbackAcceptanceSha256", null);
        String authorizationHash = FundamentalsRuntimeIdentity.hash(drillPayload);
        var registry = new FundamentalsMethodRegistry("baseline-v1");
        // Loader/signature faults have separate checks; these explicitly fictional inputs isolate the ordinary execution chain.
        ReflectionTestUtils.setField(registry, "staged", new ApprovedMethodArtifact(candidate, approvedHash, packageHash,
                "d".repeat(64), conditions, scope, "e".repeat(64), 1));
        ReflectionTestUtils.setField(registry, "controlDirectory", controls);
        ReflectionTestUtils.setField(registry, "activation", new MethodActivation(authorizationHash, "offline-drill", "DRILL",
                Set.of(USER), approvedAt, expiresAt));
        var analyst = new FundamentalsAgent(ChatClient.builder(analystModel).defaultOptions(OpenAiChatOptions.builder()
                .model("offline-model").temperature(0.7).maxTokens(4096).build()).build(), registry, runtime);
        var prefetch = new ToolPrefetchService(mock(MarketTools.class), mock(com.stocksage.capability.CapabilityGateway.class), tools,
                analyst, mock(MarketAgent.class), mock(NewsAgent.class), ticker, new EvidenceEnvelopeMapper(ticker),
                mock(com.stocksage.research.ResearchSubmissionService.class), mock(com.stocksage.knowledge.SearchResultIngestionService.class),
                mock(com.stocksage.skill.SkillExecutionService.class), emitter, json, executor,
                new ResearchHarness(mock(HarnessObserver.class)), new OrdinaryCompletionPolicy(), traces);
        ReflectionTestUtils.setField(prefetch, "agentPrefetchTimeoutSeconds", 1L);
        var messages = mock(ConversationMessageService.class);
        var conversation = new Conversation();
        conversation.setId(81L);
        conversation.setUserId(USER);
        when(messages.getConversationForUser(81L, USER)).thenReturn(conversation);
        var question = new Message();
        question.setId(91L);
        question.setContent(QUERY);
        question.setRole("user");
        question.setCreatedAt(LocalDateTime.now());
        when(messages.appendUserTurn(USER, 81L, QUERY, false)).thenReturn(
                new ConversationMessageService.UserTurn(question, List.of(), false, List.of()));
        when(messages.listMessages(USER, 81L)).thenReturn(List.of(question));
        var images = mock(ImageAttachmentService.class);
        when(images.normalizeUserMessage(QUERY, false)).thenReturn(QUERY);
        var memory = mock(ResearchMemoryService.class);
        when(memory.retrieve(any())).thenReturn(ResearchMemoryService.RetrievalResult.empty());
        var longMemory = mock(LongTermMemory.class);
        when(longMemory.buildPromptContext(USER)).thenReturn("");
        var assembler = new ChatPromptAssembler();
        ReflectionTestUtils.setField(assembler, "promptMaxTextChars", 24000);
        var chat = new ChatService(mock(ConversationTitleService.class), json, traces, mock(RagService.class), mock(ToolCallEventBus.class),
                relay, emitter, coordinator, mock(ShortTermMemory.class), longMemory, executor, images, prefetch, messages,
                mock(RoutingDecisionObserver.class), memory, ticker, assembler, mock(com.stocksage.research.ResearchTaskService.class),
                mock(com.stocksage.research.ResearchTaskObservationService.class));
        ReflectionTestUtils.setField(chat, "streamHeartbeatSeconds", 20L);
        var runs = new LinkedHashMap<String, Object>();
        int index = 0;
        for (String phase : List.of("candidate-before", "candidate-inflight", "candidate-denied", "baseline-after")) {
            var request = new ChatRequest();
            request.setUserId(USER);
            request.setConversationId(81L);
            request.setMessage(QUERY);
            List<String> chunks = chat.streamChat(request).collectList().block(Duration.ofSeconds(10));
            assertThat(chunks).anyMatch(chunk -> chunk.contains("fresh-final-" + (finalCalls.get())));
            var meta = chunks.stream().map(chunk -> { try { return json.readTree(chunk); } catch (Exception error) { throw new AssertionError(error); } })
                    .filter(node -> "meta".equals(node.path("type").asText())).findFirst().orElseThrow();
            String runId = meta.path("traceId").asText();
            AgentTrace trace = traces.getTraceForUser(runId, USER);
            assertThat(trace.getStatus()).isEqualTo("success");
            byte[] raw = json.writeValueAsBytes(trace);
            Files.write(output.resolve(phase + ".json"), raw, StandardOpenOption.CREATE_NEW);
            Files.write(output.resolve(phase + "-events.json"), json.writeValueAsBytes(chunks), StandardOpenOption.CREATE_NEW);
            var method = json.readTree(trace.getSteps()).findValues("attributes").stream()
                    .filter(node -> "ordinary-evidence".equals(node.path("kind").asText())).findFirst().orElseThrow();
            assertThat(method.path("methodBundle").path("bundleId").asText()).isEqualTo(index++ < 2 ? candidate.bundleId() : baseline.bundleId());
            runs.put(phase, Map.of("runId", runId, "traceFile", phase + ".json", "traceSha256", AgentPolicyBundle.sha256(raw),
                    "caseSha256", method.path("methodSelection").path("caseSha256").asText()));
        }
        assertThat(analystCalls.get()).isEqualTo(4);
        assertThat(finalCalls.get()).isEqualTo(4);
        assertThat(stored).hasSize(4);
        assertThat(Files.exists(controls.resolve("REVOKE." + candidate.bundleId()))).isTrue();
        Files.write(output.resolve("offline-drill.json"), json.writeValueAsBytes(Map.of("kind", "OFFLINE_ORDINARY_DRILL_EXPORT",
                "realAcceptance", false, "approvedPayload", approvedPayload, "drillPayload", drillPayload,
                "withdrawnAt", withdrawnAt.get(), "reviewedAt", Instant.now().toString(), "runs", runs)), StandardOpenOption.CREATE_NEW);
        System.out.println("OFFLINE_DRILL_EXPORT=" + output.toAbsolutePath());
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String financialFixture() throws Exception {
        var fixture = (ObjectNode) json.readTree(Files.readString(Path.of("../stocksage-data-service/tests/fixtures/sec-financials-v1.json")));
        var revenue = (ObjectNode) fixture.path("metrics").path("Revenue").deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) revenue.path("data")).add(
                ((ObjectNode) revenue.path("data").get(0)).deepCopy().put("start", "2024-02-01").put("end", "2025-01-31"));
        fixture.putObject("metrics").set("Revenue", revenue);
        return fixture.put("ticker", "AAPL").put("metric_count", 1).put("requestedPeriod", "annual").put("requestedYears", 2).toString();
    }
}
