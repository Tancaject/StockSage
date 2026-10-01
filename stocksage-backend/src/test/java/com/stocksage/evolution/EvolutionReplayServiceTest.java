package com.stocksage.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.FundamentalsPrompts;
import com.stocksage.agent.ModelTier;
import com.stocksage.config.RuntimeArtifactIdentity;
import com.stocksage.conversation.ChatPromptAssembler;
import com.stocksage.conversation.OrdinaryAnswerReplayService;
import com.stocksage.conversation.OrdinaryAnswerReplayService.PromptMessage;
import com.stocksage.service.ToolPrefetchService;
import com.stocksage.util.PromptText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvolutionReplayServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ChatModel model = mock(ChatModel.class);
    private final Coordinator coordinator = mock(Coordinator.class);
    private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
    private final AgentRuntimeConfiguration runtime = mock(AgentRuntimeConfiguration.class);
    private final RuntimeArtifactIdentity artifact = mock(RuntimeArtifactIdentity.class);
    private final FundamentalsMethodRegistry methods = new FundamentalsMethodRegistry("baseline-v1");
    private final ChatPromptAssembler assembler = new ChatPromptAssembler();
    private final FundamentalsAgent analyst = new FundamentalsAgent(ChatClient.builder(model).build(), methods);
    private final EvolutionReplayService.Request request = new EvolutionReplayService.Request("case-one", "baseline-v1", "run-one");

    @BeforeEach
    void configure() {
        when(artifact.snapshot()).thenReturn(Map.of("status", "KNOWN", "scope", "CODE_SOURCE_FILE", "sha256", "c".repeat(64),
                "dependencyScope", "ONLY_BYTES_IN_SOURCE_FILE", "javaRuntime", "unit-test"));
        ReflectionTestUtils.setField(assembler, "promptMaxTextChars", 24000);
        when(runtime.chatProviderSnapshot()).thenReturn(Map.of("protocol", "TEST_DOUBLE", "unknownProviderRevision", true));
        when(runtime.snapshot()).thenReturn(Map.of("fundamentals", Map.of(
                "scope", "CLIENT_DEFAULTS", "model", "test-standard", "temperature", 0.7,
                "maxTokens", 4096, "systemPromptHash", AgentPolicyBundle.sha256(FundamentalsPrompts.system(methods.active().method())),
                "unknownProviderRevision", true)));
        when(executor.submit(any(Callable.class))).thenAnswer(call -> {
            FutureTask<FundamentalsAgent.Analysis> future = new FutureTask<>(call.getArgument(0));
            future.run();
            return future;
        });
    }

    @Test
    void usesRealAnalystAndFinalReplayWithSeparateOriginalQuestionAndActualHashes() throws Exception {
        var sample = sample();
        Path file = manifest(sample);
        when(model.call(any(Prompt.class))).thenReturn(response("经营质量存在反向证据 [E1]。"));
        List<Message> finalMessages = new ArrayList<>();
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any()))
                .thenAnswer(call -> {
                    finalMessages.addAll(call.<List<Message>>getArgument(0));
                    Consumer<Map<String, Object>> observer = call.getArgument(4);
                    return Flux.just("最终回答 [E1]").doOnSubscribe(ignored -> {
                        observer.accept(Map.of("kind", "model-invocation", "scope", "final-answer", "modelName", "test-standard"));
                        observer.accept(Map.of("kind", "model-usage", "scope", "final-answer", "usageSource", "NO_DATA"));
                    });
                });
        EvolutionReplayService service = service(file, 120);

        var result = service.replay(request);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.runId()).isEqualTo("run-one");
        assertThat(result.repeatId()).isEqualTo(1);
        assertThat(result.executionScope()).isEqualTo("SYNTHETIC_COMPONENT_CHAIN");
        assertThat(result.methodBundle()).isEqualTo(methods.identity());
        assertThat(result.executionFileSha256()).isEqualTo(AgentPolicyBundle.sha256(Files.readString(file)));
        assertThat(result.caseSha256()).isEqualTo(sample.caseSha256());
        assertThat(result.query()).isEqualTo("那它呢？");
        assertThat(result.resolvedQuery()).isEqualTo("分析 SYNTH001 的财务质量");
        assertThat(result.analysis().context()).isEqualTo(sample.preAnalystContext());
        assertThat(result.analysis().contextSha256()).isEqualTo(sample.preAnalystContextSha256());
        assertThat(result.analysis().evidenceSha256()).isEqualTo(sample.contextSha256());
        assertThat(result.analysis().messages()).containsExactly(
                new PromptMessage("system", FundamentalsPrompts.system(methods.active().method())),
                new PromptMessage("user", FundamentalsPrompts.task(sample.resolvedQuery(), sample.preAnalystContext())));
        ArgumentCaptor<Prompt> captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captured.capture());
        assertThat(captured.getValue().getInstructions()).extracting(Message::getText)
                .containsExactlyElementsOf(result.analysis().messages().stream().map(PromptMessage::text).toList());
        assertThat(result.finalAnswer().messages().get(result.finalAnswer().messages().size() - 1))
                .isEqualTo(new PromptMessage("user", sample.query()));
        assertThat(result.finalAnswer().messages()).contains(new PromptMessage("assistant", "上一轮已确认 SYNTH001"));
        assertThat(finalMessages).extracting(Message::getText)
                .containsExactlyElementsOf(result.finalAnswer().messages().stream().map(PromptMessage::text).toList());
        assertThat(result.finalAnswer().context()).contains("## Fundamentals Agent\n经营质量存在反向证据 [E1]。\n\n",
                "最终执行验收：DEGRADED");
        assertThat(result.finalAnswer().messages().stream().map(PromptMessage::text)).anyMatch(text -> text.contains("2026-04-01"));
        assertThat(result.finalAnswer().evidenceContext()).isEqualTo(sample.context());
        assertThat(result.taskOutcome()).isEqualTo("DEGRADED");
        assertThat(result.comparisonIdentity()).containsEntry("modelConfigurationComplete", true)
                .containsEntry("evidenceSnapshotSha256", sample.contextSha256());
        assertThat(result.comparisonIdentity().get("fixedFinalPromptSha256")).asString().hasSize(64);
        for (var stage : List.of(result.analysis(), result.finalAnswer())) {
            assertThat(stage.promptSha256()).isEqualTo(AgentPolicyBundle.sha256(mapper.writeValueAsString(stage.messages())));
            assertThat(stage.contextSha256()).isEqualTo(AgentPolicyBundle.sha256(stage.context()));
            assertThat(stage.evidenceSha256()).isEqualTo(AgentPolicyBundle.sha256(stage.evidenceContext()));
        }
        assertThatThrownBy(() -> service.replay(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已提交");
        verify(model, times(1)).call(any(Prompt.class));
        verify(coordinator, times(1)).streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any());
    }

    @Test
    void finalAnswerUsesTheProduction4500CharacterDraftRatherThanTheRawReport() throws Exception {
        String report = "a".repeat(4501);
        when(model.call(any(Prompt.class))).thenReturn(response(report));
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any())).thenReturn(Flux.just("回答 [E1]"));

        var result = service(manifest(sample()), 120).replay(request);

        assertThat(result.analysis().answer()).hasSize(4501);
        assertThat(result.analystStatus()).isEqualTo("TRUNCATED");
        assertThat(result.finalAnswer().context()).contains("## Fundamentals Agent\n" + PromptText.truncate(report, 4500) + "\n\n");
        assertThat(result.finalAnswer().context()).doesNotContain(report);
        assertThat(result.finalAnswer().evidenceSha256()).isEqualTo(sample().contextSha256());
    }

    @Test
    void rejectedAndFailedRunsDoNotCallFinalModelOrRetryTheAnalyst() throws Exception {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider secret"));
        var service = service(manifest(sample()), 120);
        assertThatThrownBy(() -> service.replay(new EvolutionReplayService.Request("case-one", "baseline-v1", "unknown-run")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replay(new EvolutionReplayService.Request("case-other", "baseline-v1", "run-one")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replay(new EvolutionReplayService.Request("case-one", "unregistered", "run-one")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(coordinator);

        var failed = service.replay(request);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("ANALYST_INVOCATION_FAILED");
        assertThat(failed.finalAnswer()).isNull();
        assertThat(failed.analysis().observations()).anyMatch(row -> "NO_DATA".equals(row.get("usageSource")));
        assertThat(mapper.writeValueAsString(failed)).doesNotContain("provider secret");
        assertThatThrownBy(() -> service.replay(request)).isInstanceOf(IllegalArgumentException.class);
        verify(model, times(1)).call(any(Prompt.class));
        verifyNoInteractions(coordinator);
    }

    @Test
    void timeoutConsumesTheRegisteredRunWithoutAutomaticRetry() throws Exception {
        FutureTask<FundamentalsAgent.Analysis> pending = new FutureTask<>(() -> null);
        when(executor.submit(any(Callable.class))).thenReturn(pending);
        var service = service(manifest(sample()), 1);

        var failed = service.replay(request);

        assertThat(failed.errorCode()).isEqualTo("TIMEOUT");
        assertThat(failed.analysis().timeoutSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> service.replay(request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pending.isCancelled()).isTrue();
        verify(executor, times(1)).submit(any(Callable.class));
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(coordinator);
    }

    @Test
    void rejectedReplayWorkReportsCapacityAndNeverCallsTheModel() throws Exception {
        when(executor.submit(any(Callable.class))).thenThrow(new java.util.concurrent.RejectedExecutionException("unit capacity"));
        var replay = service(manifest(sample()), 120);
        var result = replay.replay(request);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("REPLAY_CAPACITY_EXHAUSTED");
        assertThatThrownBy(() -> replay.replay(request)).isInstanceOf(IllegalArgumentException.class);
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(coordinator);
    }

    @Test
    void registeredManifestRejectsHiddenFieldsCorruptEvidenceAndWrongInputAssembly() throws Exception {
        Path original = manifest(sample());
        ObjectNode document = (ObjectNode) mapper.readTree(original.toFile());
        ObjectNode caseRow = (ObjectNode) document.path("cases").get(0);
        for (String field : List.of("gold", "rawResponse", "contextSha256", "preAnalystContext")) {
            ObjectNode changed = document.deepCopy();
            ((ObjectNode) changed.path("cases").get(0)).put(field, "not valid");
            Path invalid = directory.resolve(field + ".json");
            mapper.writeValue(invalid.toFile(), changed);
            assertThatThrownBy(() -> service(invalid, 120)).isInstanceOf(IllegalArgumentException.class);
        }
        caseRow.put("origin", "REAL");
        mapper.writeValue(original.toFile(), document);
        assertThatThrownBy(() -> service(original, 120)).isInstanceOf(IllegalArgumentException.class);
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(coordinator);
    }

    @Test
    void acceptsTheCheckedInSyntheticExecutionContractWithoutRunningModels() {
        Path checkedIn = Path.of(System.getProperty("basedir", "."), "..", "evals", "evolution", "synthetic-smoke", "execution.json");
        assertThat(service(checkedIn, 120)).isNotNull();
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(coordinator);
    }

    @Test
    void authorizedSourceContractPreservesMetadataButNeverImportsRawOrGold() throws Exception {
        // Fictional Python-exported fixture tests the wire contract, not source authorization or live quality.
        Path input = Path.of(System.getProperty("basedir", "."), "src", "test", "resources", "evolution", "authorized-contract.json");
        when(model.call(any(Prompt.class))).thenReturn(response("收入同比增长 25% [E1]。"));
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any()))
                .thenReturn(Flux.just("收入同比增长 25% [E1]。"));
        var result = service(input, 120).replay(new EvolutionReplayService.Request(
                "authorized-contract", "baseline-v1", "test-authorized-run"));
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.executionScope()).isEqualTo("FROZEN_COMPONENT_CHAIN");
        assertThat(result.origin()).isEqualTo("PUBLIC_AUTHORIZED");
        assertThat(result.analysis().context()).contains("reportPeriod=quarterly, barsOnly=false, reportCount=2",
                "\"currency\":\"USD\"", "\"periodStart\":\"2025-10-01\"");
        assertThat(mapper.writeValueAsString(result.analysis().messages()))
                .doesNotContain("HIDDEN RAW ONLY", "unit-test-only-not-a-real-authorization", "gold");
        for (String field : List.of("availableAt", "visibleSha256", "rawSha256", "periodStart")) {
            ObjectNode document = (ObjectNode) mapper.readTree(input.toFile());
            ObjectNode evidence = (ObjectNode) document.path("cases").get(0).path("evidenceMetadata").get(0);
            evidence.put(field, field.equals("availableAt") ? "2026-02-02T00:00:00Z" : "invalid");
            Path invalid = directory.resolve("authorized-" + field + ".json");
            mapper.writeValue(invalid.toFile(), document);
            assertThatThrownBy(() -> service(invalid, 120)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void experimentalRegistrationChangesOnlyTheEvaluatedMethod() throws Exception {
        var candidate = AgentPolicyBundle.create("candidate-one", "baseline-v1", "先核对期间与币种，再分析可比数据。");
        Path bundles = directory.resolve("bundles.json");
        mapper.writeValue(bundles.toFile(), new EvolutionReplayService.BundleFile(1, List.of(candidate)));
        Path execution = directory.resolve("candidate-execution.json");
        mapper.writeValue(execution.toFile(), new EvolutionReplayService.ExecutionFile(1, List.of(sample()), List.of(
                new EvolutionReplayService.RegisteredRun("candidate-run", "case-one", "candidate-one", 1)), null));
        when(model.call(any(Prompt.class))).thenReturn(response("候选草稿 [E1]"));
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any()))
                .thenReturn(Flux.just("最终回答 [E1]"));
        var replay = new EvolutionReplayService(analyst, methods, runtime, assembler,
                new OrdinaryAnswerReplayService(coordinator, mapper), executor, mapper, artifact, "", execution.toString(),
                bundles.toString(), 120, 24000);
        var result = replay.replay(new EvolutionReplayService.Request("case-one", "candidate-one", "candidate-run"));
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.methodBundle()).isEqualTo(candidate.identity());
        assertThat(result.analysis().messages().get(0).text()).isEqualTo(FundamentalsPrompts.system(candidate.method()));
        assertThat(methods.active().bundleId()).isEqualTo("baseline-v1");
        assertThatThrownBy(() -> methods.require("candidate-one")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(execution, 120)).isInstanceOf(IllegalArgumentException.class);
        var changed = (ObjectNode) mapper.readTree(bundles.toFile());
        ((ObjectNode) changed.path("bundles").get(0)).put("method", "被替换的方法");
        mapper.writeValue(bundles.toFile(), changed);
        assertThatThrownBy(() -> new EvolutionReplayService(analyst, methods, runtime, assembler,
                new OrdinaryAnswerReplayService(coordinator, mapper), executor, mapper, artifact, "", execution.toString(),
                bundles.toString(), 120, 24000)).isInstanceOf(IllegalArgumentException.class);
    }

    private EvolutionReplayService service(Path file, long timeout) {
        return new EvolutionReplayService(analyst, methods, runtime, assembler,
                new OrdinaryAnswerReplayService(coordinator, mapper), executor, mapper, artifact, "", file.toString(), "", timeout, 24000);
    }

    @Test
    void runContextBindsTheRegisteredExperimentToTheActualPackagedBuild() throws Exception {
        Path execution = directory.resolve("v2-execution.json");
        mapper.writeValue(execution.toFile(), new EvolutionReplayService.ExecutionFile(2, List.of(sample()), List.of(
                new EvolutionReplayService.RegisteredRun("run-one", "case-one", "baseline-v1", 1)),
                new EvolutionReplayService.EvaluationContext("experiment-one", "e".repeat(64), "BASELINE")));
        Path build = directory.resolve("build.json");
        mapper.writeValue(build.toFile(), Map.of("schema", "fundamentals_evolution_build_v1", "gitSha", "a".repeat(40),
                "sourceTreeSha256", "b".repeat(64), "sourceState", "WORKTREE_SNAPSHOT", "artifactSha256", "c".repeat(64),
                "artifactFormat", "SPRING_BOOT_JAR", "builtAt", "2026-09-28T00:00:00Z"));
        when(model.call(any(Prompt.class))).thenReturn(response("报告 [E1]"));
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any())).thenReturn(Flux.just("回答 [E1]"));
        var service = new EvolutionReplayService(analyst, methods, runtime, assembler,
                new OrdinaryAnswerReplayService(coordinator, mapper), executor, mapper, artifact, build.toString(), execution.toString(), "", 120, 24000);
        var result = service.replay(request);
        assertThat(result.runContext()).containsEntry("experimentId", "experiment-one")
                .containsEntry("evaluatorVersion", "e".repeat(64)).containsEntry("runMode", "BASELINE")
                .containsEntry("gitSha", "a".repeat(40)).containsEntry("runId", "run-one")
                .containsEntry("runtimeBuildSha256", result.comparisonIdentity().get("runtimeBuildSha256"));
        assertThat(result.comparisonIdentity().get("runtimeBuild")).asString().contains("VERIFIED_ARTIFACT");
        var changed = (ObjectNode) mapper.readTree(build.toFile());
        changed.put("artifactSha256", "d".repeat(64));
        mapper.writeValue(build.toFile(), changed);
        assertThatThrownBy(() -> new EvolutionReplayService(analyst, methods, runtime, assembler,
                new OrdinaryAnswerReplayService(coordinator, mapper), executor, mapper, artifact, build.toString(), execution.toString(), "", 120, 24000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("实际运行 JAR");
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void shadowRequiresLoadedApprovalAndReportsChangedConditionsWithoutChangingServingMethod() throws Exception {
        Path sourceFile = Path.of(System.getProperty("basedir", "."), "src", "test", "resources", "evolution", "authorized-contract.json");
        Path build = directory.resolve("shadow-build.json");
        mapper.writeValue(build.toFile(), Map.of("schema", "fundamentals_evolution_build_v1", "gitSha", "a".repeat(40),
                "sourceTreeSha256", "b".repeat(64), "sourceState", "WORKTREE_SNAPSHOT", "artifactSha256", "c".repeat(64),
                "artifactFormat", "SPRING_BOOT_JAR", "builtAt", "2026-09-28T00:00:00Z"));
        when(model.call(any(Prompt.class))).thenReturn(response("报告 [E1]"));
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any())).thenAnswer(call -> {
            Consumer<Map<String, Object>> observer = call.getArgument(4);
            return Flux.just("回答 [E1]").doOnSubscribe(ignored -> observer.accept(Map.of(
                    "kind", "model-invocation", "scope", "final-answer", "modelName", "test-standard")));
        });
        var original = new EvolutionReplayService(analyst, methods, runtime, assembler,
                new OrdinaryAnswerReplayService(coordinator, mapper), executor, mapper, artifact,
                build.toString(), sourceFile.toString(), "", 120, 24000).replay(
                new EvolutionReplayService.Request("authorized-contract", "baseline-v1", "test-authorized-run"));
        var candidate = AgentPolicyBundle.create("candidate-shadow", "baseline-v1", "先比较期间与单位，再核对证据支持。");
        Path bundles = directory.resolve("shadow-bundles.json");
        mapper.writeValue(bundles.toFile(), new EvolutionReplayService.BundleFile(1, List.of(candidate)));
        var source = mapper.readValue(sourceFile.toFile(), EvolutionReplayService.ExecutionFile.class);
        Path execution = directory.resolve("shadow-execution.json");
        mapper.writeValue(execution.toFile(), new EvolutionReplayService.ExecutionFile(2, source.cases(), List.of(
                new EvolutionReplayService.RegisteredRun("shadow-baseline", "authorized-contract", "baseline-v1", 1),
                new EvolutionReplayService.RegisteredRun("shadow-candidate", "authorized-contract", candidate.bundleId(), 1),
                new EvolutionReplayService.RegisteredRun("shadow-candidate-next", "authorized-contract", candidate.bundleId(), 2)),
                new EvolutionReplayService.EvaluationContext("experiment-shadow", "e".repeat(64), "SHADOW")));
        clearInvocations(model, coordinator);
        assertThatThrownBy(() -> new EvolutionReplayService(analyst, methods, runtime, assembler,
                new OrdinaryAnswerReplayService(coordinator, mapper), executor, mapper, artifact,
                build.toString(), execution.toString(), bundles.toString(), 120, 24000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已批准待验证");
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(coordinator);
        var expected = new java.util.LinkedHashMap<String, String>();
        for (String name : List.of("runtimeBuildSha256", "modelConfigSha256", "fixedFinalPromptSha256", "memorySnapshotSha256")) {
            expected.put(name, (String) original.comparisonIdentity().get(name));
        }
        for (String scenario : List.of("matching", "model-drift", "stop", "withdraw", "unavailable", "withdraw-during", "cancel-during")) {
            boolean drift = scenario.equals("model-drift");
            var comparison = new java.util.LinkedHashMap<>(expected);
            if (drift) comparison.put("modelConfigSha256", "f".repeat(64));
            // A supplied approved object exercises the shadow boundary; signature verification has separate tests.
            var approved = new ApprovedMethodArtifact(candidate, "1".repeat(64), "2".repeat(64), "3".repeat(64),
                    Map.copyOf(comparison), new ApprovedMethodArtifact.Scope(List.of("comparison"), List.of("dated-values"),
                    List.of("evidence-reading"), "仅比较已提供期间的指标"), "4".repeat(64), 1);
            Path controls = directory.resolve(scenario);
            if (!scenario.equals("unavailable")) java.nio.file.Files.createDirectory(controls);
            if (scenario.equals("stop")) java.nio.file.Files.createFile(controls.resolve("STOP_SHADOW"));
            if (scenario.equals("withdraw")) java.nio.file.Files.createFile(controls.resolve("REVOKE." + candidate.bundleId()));
            doAnswer(call -> {
                if (scenario.equals("withdraw-during")) java.nio.file.Files.writeString(controls.resolve("REVOKE." + candidate.bundleId()), "");
                if (scenario.equals("cancel-during")) java.nio.file.Files.writeString(controls.resolve("CANCEL_SHADOW"), "");
                return response("报告 [E1]");
            }).when(model).call(any(Prompt.class));
            clearInvocations(model, coordinator);
            var registry = new FundamentalsMethodRegistry("baseline-v1", approved, controls);
            var shadow = new EvolutionReplayService(analyst, registry, runtime, assembler,
                    new OrdinaryAnswerReplayService(coordinator, mapper), executor, mapper, artifact,
                    build.toString(), execution.toString(), bundles.toString(), 120, 24000);
            var result = shadow.replay(new EvolutionReplayService.Request("authorized-contract", candidate.bundleId(), "shadow-candidate"));
            String error = switch (scenario) {
                case "model-drift" -> "SHADOW_APPROVED_CONDITIONS_CHANGED";
                case "stop" -> "SHADOW_STOPPED_BY_OPERATOR";
                case "withdraw" -> "SHADOW_BUNDLE_WITHDRAWN";
                case "unavailable" -> "SHADOW_CONTROL_UNAVAILABLE";
                case "cancel-during" -> "SHADOW_CANCELLED_BY_OPERATOR";
                default -> null;
            };
            assertThat(result.status()).as(scenario).isEqualTo(error == null ? "COMPLETED" : "FAILED");
            assertThat(result.errorCode()).as(scenario).isEqualTo(error);
            assertThat(result.methodBundle()).isEqualTo(candidate.identity());
            boolean beforeCall = List.of("stop", "withdraw", "unavailable").contains(scenario);
            verify(model, times(beforeCall ? 0 : 1)).call(any(Prompt.class));
            if (beforeCall || scenario.equals("cancel-during")) {
                verifyNoInteractions(coordinator);
                assertThat(result.finalAnswer()).isNull();
            }
            assertThatThrownBy(() -> shadow.replay(new EvolutionReplayService.Request("authorized-contract", candidate.bundleId(), "shadow-candidate")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能重试");
            if (scenario.equals("withdraw-during")) {
                java.nio.file.Files.delete(controls.resolve("REVOKE." + candidate.bundleId()));
                var next = shadow.replay(new EvolutionReplayService.Request("authorized-contract", candidate.bundleId(), "shadow-candidate-next"));
                assertThat(next.errorCode()).isEqualTo("SHADOW_BUNDLE_WITHDRAWN");
                clearInvocations(model, coordinator);
                var restored = shadow.replay(new EvolutionReplayService.Request("authorized-contract", "baseline-v1", "shadow-baseline"));
                assertThat(restored.status()).isEqualTo("COMPLETED");
                assertThat(restored.methodBundle()).isEqualTo(AgentPolicyBundle.baseline().identity());
                ArgumentCaptor<Prompt> restoredPrompt = ArgumentCaptor.forClass(Prompt.class);
                verify(model).call(restoredPrompt.capture());
                assertThat(restoredPrompt.getValue().getInstructions().get(0).getText())
                        .isEqualTo(FundamentalsPrompts.system(AgentPolicyBundle.baseline().method()));
            }
            assertThat(((Map<?, ?>) result.comparisonIdentity().get("shadow")).get("approvedArtifactSha256")).isEqualTo("3".repeat(64));
            assertThat(result.analysis().evidenceContext()).isEqualTo(original.analysis().evidenceContext());
            assertThat(registry.active()).isEqualTo(AgentPolicyBundle.baseline());
            assertThatThrownBy(() -> registry.require(candidate.bundleId())).isInstanceOf(IllegalArgumentException.class);
            if (scenario.equals("matching")) {
                var authorization = new MethodActivation("5".repeat(64), "unit-serving", "SERVING", java.util.Set.of("internal-one"),
                        java.time.Instant.now().minusSeconds(5), java.time.Instant.now().plusSeconds(300));
                var servingRegistry = new FundamentalsMethodRegistry("baseline-v1", approved, controls, authorization);
                var servingAgent = new FundamentalsAgent(ChatClient.builder(model).build(), servingRegistry, runtime);
                var finalInvocation = original.finalAnswer().observations().stream()
                        .filter(row -> "model-invocation".equals(row.get("kind"))).findFirst().orElseThrow();
                var servingRequest = new FundamentalsRuntimeIdentity.Request(original.query(), finalInvocation,
                        FundamentalsRuntimeIdentity.memoryHash("", "", List.of()), 24000, true);
                var pinned = servingAgent.selectMethod("internal-one", java.util.Set.of("comparison"), java.util.Set.of("dated-values"),
                        original.analysis().evidenceContext(), servingRequest, 120);
                assertThat(pinned.bundle()).isEqualTo(candidate);
                assertThat(pinned.attributes().get("comparisonIdentity")).isEqualTo(expected);
                assertThat(servingAgent.analyzeObserved(original.query(), original.analysis().context(), pinned.bundle()).methodBundle())
                        .isEqualTo(candidate.identity());
                var memoryRequest = new FundamentalsRuntimeIdentity.Request(original.query(), finalInvocation,
                        FundamentalsRuntimeIdentity.memoryHash("a different memory", "", List.of()), 24000, true);
                assertThat(servingAgent.selectMethod("internal-one", java.util.Set.of("comparison"), java.util.Set.of("dated-values"),
                        original.analysis().evidenceContext(), memoryRequest, 120).attributes())
                        .containsEntry("reason", "REQUEST_MEMORY_OUTSIDE_SCOPE");
                assertThat(servingAgent.selectMethod("internal-one", java.util.Set.of("comparison"), java.util.Set.of("dated-values"),
                        original.analysis().evidenceContext(), servingRequest, 120).bundle()).isEqualTo(candidate);
            }
        }
    }

    private Path manifest(EvolutionReplayService.FrozenCase sample) throws Exception {
        Path path = directory.resolve("execution.json");
        mapper.writeValue(path.toFile(), new EvolutionReplayService.ExecutionFile(1, List.of(sample), List.of(
                new EvolutionReplayService.RegisteredRun("run-one", sample.caseId(), "baseline-v1", 1)), null));
        return path;
    }

    private EvolutionReplayService.FrozenCase sample() {
        String evidence = "[E1] SYNTHETIC SYNTH001 2025-FY 现金流为零；synthetic://report/one";
        String context = ToolPrefetchService.ordinaryEvidenceContext("SYNTH001", Map.of(), "HISTORICAL", "DEGRADED", evidence);
        return new EvolutionReplayService.FrozenCase("case-one", "SYNTHETIC", "FUNDAMENTALS", "a".repeat(64),
                "那它呢？", "分析 SYNTH001 的财务质量", List.of(new PromptMessage("user", "SYNTH001"),
                new PromptMessage("assistant", "上一轮已确认 SYNTH001")), LocalDate.of(2026, 4, 1), evidence,
                AgentPolicyBundle.sha256(evidence), "SYNTHETIC_COMPONENT_CHAIN", "SYNTH001", Map.of(),
                "HISTORICAL", "DEGRADED", List.of("E1"), context, AgentPolicyBundle.sha256(context), null, null);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
