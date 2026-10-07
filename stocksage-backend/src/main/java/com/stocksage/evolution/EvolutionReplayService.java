package com.stocksage.evolution;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.FundamentalsPrompts;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.ModelCompletion;
import com.stocksage.agent.PlanAction;
import com.stocksage.conversation.ChatPromptAssembler;
import com.stocksage.conversation.OrdinaryAnswerReplayService;
import com.stocksage.conversation.OrdinaryAnswerReplayService.PromptMessage;
import com.stocksage.service.ToolPrefetchService;
import com.stocksage.config.RuntimeArtifactIdentity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Registered frozen inputs exercise the actual analyst and final-answer components without business writes. */
@Service
@Profile("evolution-eval")
public class EvolutionReplayService {
    private final FundamentalsAgent analyst;
    private final FundamentalsMethodRegistry methods;
    private final Map<String, AgentPolicyBundle> evaluationMethods;
    private final AgentRuntimeConfiguration runtime;
    private final ChatPromptAssembler assembler;
    private final OrdinaryAnswerReplayService finalReplay;
    private final AsyncTaskExecutor executor;
    private final ObjectMapper mapper;
    private final Map<String, FrozenCase> cases;
    private final Map<String, RegisteredRun> runs;
    private final Set<String> consumedRuns = ConcurrentHashMap.newKeySet();
    private final String executionFileSha256;
    private final long timeoutSeconds;
    private final int promptMaxChars;
    private final Map<String, Object> buildIdentity;
    private final EvaluationContext evaluationContext;

    public EvolutionReplayService(FundamentalsAgent analyst, FundamentalsMethodRegistry methods,
                                  AgentRuntimeConfiguration runtime, ChatPromptAssembler assembler,
                                  OrdinaryAnswerReplayService finalReplay,
                                  @Qualifier("evolutionReplayExecutor") AsyncTaskExecutor executor,
                                  ObjectMapper mapper, RuntimeArtifactIdentity artifactIdentity,
                                  @Value("${stocksage.evolution.eval.build-manifest:}") String buildManifest,
                                  @Value("${stocksage.evolution.eval.case-file:}") String caseFile,
                                  @Value("${stocksage.evolution.eval.bundle-file:}") String bundleFile,
                                  @Value("${stocksage.agent.prefetch.timeout-seconds:120}") long timeoutSeconds,
                                  @Value("${stocksage.chat.prompt.max-text-chars:24000}") int promptMaxChars) {
        this.analyst = analyst;
        this.methods = methods;
        this.runtime = runtime;
        this.assembler = assembler;
        this.finalReplay = finalReplay;
        this.executor = executor;
        this.mapper = mapper;
        this.timeoutSeconds = timeoutSeconds;
        this.promptMaxChars = promptMaxChars;
        this.buildIdentity = readBuildIdentity(artifactIdentity.snapshot(), buildManifest);
        if (caseFile == null || caseFile.isBlank()) {
            throw new IllegalArgumentException("进化回放缺少登记文件，请配置 stocksage.evolution.eval.case-file。");
        }
        if (timeoutSeconds < 1 || promptMaxChars < 1) {
            throw new IllegalArgumentException("进化回放的模型限时和提示词上限必须为正数。");
        }
        try {
            Map<String, AgentPolicyBundle> methodAllowlist = new LinkedHashMap<>();
            methodAllowlist.put(AgentPolicyBundle.BASELINE_ID, methods.require(AgentPolicyBundle.BASELINE_ID));
            if (bundleFile != null && !bundleFile.isBlank()) {
                BundleFile experimental = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(Files.readAllBytes(Path.of(bundleFile)), BundleFile.class);
                if (experimental.schemaVersion() != 1 || experimental.bundles() == null || experimental.bundles().isEmpty()) {
                    throw new IllegalArgumentException("实验方法登记文件必须包含 schemaVersion=1 和非空 bundles。");
                }
                for (AgentPolicyBundle bundle : experimental.bundles()) {
                    if (bundle == null || bundle.parentBundleId() == null
                            || !methodAllowlist.containsKey(bundle.parentBundleId())
                            || methodAllowlist.putIfAbsent(bundle.bundleId(), bundle) != null) {
                        throw new IllegalArgumentException("实验方法必须在父版本之后登记，且不能覆盖基线或重复已有 ID。");
                    }
                }
            }
            evaluationMethods = Map.copyOf(methodAllowlist);
            byte[] bytes = Files.readAllBytes(Path.of(caseFile));
            executionFileSha256 = AgentPolicyBundle.sha256(bytes);
            ExecutionFile registered = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(bytes, ExecutionFile.class);
            if ((registered.schemaVersion() != 1 && registered.schemaVersion() != 2) || registered.cases() == null || registered.cases().isEmpty()) {
                throw new IllegalArgumentException("进化回放登记文件必须包含 schemaVersion=1/2 和非空 cases。");
            }
            evaluationContext = registered.context();
            if (registered.schemaVersion() == 2) {
                if (evaluationContext == null || evaluationContext.evaluatorVersion() == null
                        || !evaluationContext.evaluatorVersion().matches("[a-f0-9]{64}")
                        || evaluationContext.runMode() == null
                        || !Set.of("BASELINE", "DEVELOPMENT", "VALIDATION", "HOLDOUT").contains(evaluationContext.runMode())) {
                    throw new IllegalArgumentException("V2 执行清单需要固定 experimentId、评估器指纹和 runMode。");
                }
                requireId(evaluationContext.experimentId(), "experimentId");
            } else if (evaluationContext != null) {
                throw new IllegalArgumentException("带实验上下文的执行清单必须使用 schemaVersion=2。");
            }
            Map<String, FrozenCase> loaded = new LinkedHashMap<>();
            for (FrozenCase sample : registered.cases()) {
                validateCase(sample);
                if (loaded.putIfAbsent(sample.caseId(), sample) != null) {
                    throw new IllegalArgumentException("进化回放登记文件含重复 caseId，请修正清单。");
                }
            }
            cases = Map.copyOf(loaded);
            if (registered.runs() == null || registered.runs().isEmpty()) {
                throw new IllegalArgumentException("进化回放登记文件缺少 runs 执行计划，请重新导出清单。");
            }
            Map<String, RegisteredRun> planned = new LinkedHashMap<>();
            Set<List<Object>> repetitions = new java.util.HashSet<>();
            for (RegisteredRun run : registered.runs()) {
                if (run == null) throw new IllegalArgumentException("回放执行计划不能包含空行。");
                requireId(run.runId(), "runId");
                requireId(run.caseId(), "caseId");
                requireId(run.bundleId(), "bundleId");
                if (!cases.containsKey(run.caseId()) || run.repeatId() < 1
                        || !repetitions.add(List.of(run.caseId(), run.bundleId(), run.repeatId()))) {
                    throw new IllegalArgumentException("回放执行计划引用了未登记用例或无效 repeatId，请修正清单。");
                }
                requireEvaluationMethod(run.bundleId());
                if (planned.putIfAbsent(run.runId(), run) != null) {
                    throw new IllegalArgumentException("回放执行计划含重复 runId，请重新导出清单。");
                }
            }
            runs = Map.copyOf(planned);
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法读取进化回放登记文件，请检查文件存在、UTF-8 编码及字段契约。", failure);
        }
    }

    public Result replay(Request request) {
        if (request == null) throw new IllegalArgumentException("进化回放缺少 caseId、bundleId 和 runId。");
        requireId(request.runId(), "runId");
        requireId(request.caseId(), "caseId");
        requireId(request.bundleId(), "bundleId");
        RegisteredRun planned = runs.get(request.runId());
        if (planned == null || !planned.caseId().equals(request.caseId()) || !planned.bundleId().equals(request.bundleId())) {
            throw new IllegalArgumentException("runId、caseId 和 bundleId 与登记执行计划不匹配，请使用清单中的完整组合。");
        }
        FrozenCase sample = cases.get(request.caseId());
        if (sample == null) throw new IllegalArgumentException("未登记的 caseId，请使用当前执行清单中的用例。");
        AgentPolicyBundle bundle = requireEvaluationMethod(request.bundleId());
        List<PromptMessage> analysisMessages = List.of(
                new PromptMessage("system", FundamentalsPrompts.system(bundle.method())),
                new PromptMessage("user", FundamentalsPrompts.task(sample.resolvedQuery(), sample.preAnalystContext())));
        if (analysisMessages.stream().mapToInt(row -> row.text().length()).sum() > promptMaxChars) {
            throw new IllegalArgumentException("基本面回放输入超过当前 " + promptMaxChars + " 字符上限，请缩小已登记用例。");
        }
        Map<String, Object> defaults = analystDefaults();
        // A timed-out provider may still finish remotely. Never retry the same registered run in this process.
        if (!consumedRuns.add(request.runId())) {
            throw new IllegalArgumentException("此 runId 已提交（含失败或超时），不能重试；请由操作员登记新的执行计划。进程重启不提供 exactly-once 保证。");
        }
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("kind", "model-invocation");
        invocation.put("scope", "fundamentals-analysis");
        invocation.put("modelTier", "STANDARD");
        invocation.put("modelName", defaults.get("model"));
        invocation.put("configurationSource", "CLIENT_DEFAULTS");
        invocation.put("clientDefaults", defaults);
        invocation.put("actualSystemPromptSha256", AgentPolicyBundle.sha256(analysisMessages.get(0).text()));
        List<Map<String, Object>> observations = new ArrayList<>();
        observations.add(Map.copyOf(invocation));
        long started = System.nanoTime();
        Future<FundamentalsAgent.Analysis> future = null;
        FundamentalsAgent.Analysis observed;
        try {
            future = executor.submit(
                    () -> analyst.analyzeObserved(sample.resolvedQuery(), sample.preAnalystContext(), bundle));
            observed = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (!observed.systemPrompt().equals(analysisMessages.get(0).text())
                    || !observed.userPrompt().equals(analysisMessages.get(1).text())
                    || !observed.methodBundle().equals(bundle.identity())) {
                throw new IllegalStateException("分析师回放实际输入或方法版本发生变化。");
            }
            Map<String, Object> usage = new LinkedHashMap<>(observed.responseMetadata());
            usage.put("kind", "model-usage");
            usage.put("scope", "fundamentals-analysis");
            observations.add(Map.copyOf(usage));
            observations.add(observed.completion().attributes("fundamentals-analysis"));
        } catch (Exception failure) {
            if (future != null) future.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            observations.add(Map.of("kind", "model-usage", "scope", "fundamentals-analysis", "usageSource", "NO_DATA",
                    "providerCompletionUnknown", failure instanceof TimeoutException || failure instanceof InterruptedException));
            String code = failure instanceof TimeoutException ? "TIMEOUT"
                    : failure instanceof java.util.concurrent.RejectedExecutionException ? "REPLAY_CAPACITY_EXHAUSTED" : "ANALYST_INVOCATION_FAILED";
            StageResult stage = stage("FUNDAMENTALS_ANALYSIS", "FAILED", "", analysisMessages,
                    sample.preAnalystContext(), sample.context(), true, observations, elapsed(started), timeoutSeconds, code);
            return result(request, sample, bundle, "FAILED", code, "FAILED", "DEGRADED", stage, null);
        }
        String report = observed.content() == null ? "" : observed.content();
        ModelCompletion analystCompletion = observed.completion();
        StageResult analysis = stage("FUNDAMENTALS_ANALYSIS", analystCompletion.complete() ? "COMPLETED" : "FAILED", report,
                analysisMessages, sample.preAnalystContext(), sample.context(), true, observations,
                elapsed(started), timeoutSeconds, analystCompletion.complete() ? null : analystCompletion.errorCode());

        ToolPrefetchService.AnalystDraft draft = ToolPrefetchService.appendAnalystDraft(sample.preAnalystContext(),
                PlanAction.FUNDAMENTALS_AGENT.label(), report, sample.initialTaskOutcome(), analystCompletion);
        var prepared = new ToolPrefetchService.PreparedToolContext(
                ToolPrefetchService.finishOrdinaryContext(draft.context(), draft.taskOutcome()), "", null, null,
                draft.taskOutcome(), sample.citationIds(), sample.context(), draft.section());
        try {
            List<Message> history = new ArrayList<>();
            for (PromptMessage row : sample.history()) {
                history.add("user".equals(row.role()) ? new UserMessage(row.text()) : new AssistantMessage(row.text()));
            }
            history.add(new UserMessage(sample.query()));
            var assembly = assembler.assemble(history, prepared, List.of(), "", "", false, false, sample.asOf());
            List<PromptMessage> finalMessages = assembly.messages().stream()
                    .map(message -> new PromptMessage(message.getMessageType().getValue(), message.getText())).toList();
            var answer = finalReplay.replay(new OrdinaryAnswerReplayService.Request(finalMessages, ModelTier.STANDARD));
            StageResult finalStage = stage("FINAL_ANSWER", answer.status().name(), answer.answer(), finalMessages,
                    assembly.context(), assembly.evidenceContext(), assembly.evidenceCaptureComplete(), answer.observations(),
                    answer.durationMs(), answer.timeoutSeconds(), answer.errorCode());
            if (!finalStage.promptSha256().equals(answer.promptSha256())) {
                return result(request, sample, bundle, "FAILED", "FINAL_INPUT_HASH_MISMATCH", draft.status(),
                        draft.taskOutcome(), analysis, finalStage);
            }
            return result(request, sample, bundle, answer.status().name(), answer.errorCode(), draft.status(),
                    prepared.outcomeForAnswer(answer.answer(), answer.completion()), analysis, finalStage);
        } catch (RuntimeException failure) {
            return result(request, sample, bundle, "FAILED", "FINAL_ASSEMBLY_OR_REPLAY_FAILED", draft.status(),
                    draft.taskOutcome(), analysis, null);
        }
    }

    private StageResult stage(String scope, String status, String answer, List<PromptMessage> messages,
                              String context, String evidence, boolean complete, List<Map<String, Object>> observations,
                              long durationMs, long timeout, String errorCode) {
        List<Map<String, String>> ordered = messages.stream().map(message -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("role", message.role());
            row.put("text", message.text());
            return row;
        }).toList();
        try {
            return new StageResult(scope, status, answer, messages, AgentPolicyBundle.sha256(mapper.writeValueAsString(ordered)),
                    context, AgentPolicyBundle.sha256(context), evidence, AgentPolicyBundle.sha256(evidence), complete,
                    List.copyOf(observations), durationMs, timeout, errorCode);
        } catch (IOException impossible) {
            throw new IllegalStateException("进化回放无法序列化文本消息。", impossible);
        }
    }

    private Result result(Request request, FrozenCase sample, AgentPolicyBundle bundle, String status, String error,
                          String analystStatus, String taskOutcome, StageResult analysis, StageResult finalAnswer) {
        var identity = comparisonIdentity(sample, finalAnswer);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schemaVersion", 1);
        context.put("runId", request.runId());
        context.put("caseId", sample.caseId());
        context.put("repeatId", runs.get(request.runId()).repeatId());
        context.put("experimentId", evaluationContext == null ? "UNREGISTERED" : evaluationContext.experimentId());
        context.put("evaluatorVersion", evaluationContext == null ? "UNKNOWN" : evaluationContext.evaluatorVersion());
        context.put("runMode", evaluationContext == null ? "MECHANISM" : evaluationContext.runMode());
        context.put("gitSha", ((Map<?, ?>) buildIdentity.get("manifest")).getOrDefault("gitSha", null));
        context.put("runtimeBuildSha256", identity.get("runtimeBuildSha256"));
        context.put("bundleHash", bundle.contentSha256());
        context.put("modelConfigHash", identity.get("modelConfigSha256"));
        context.put("evidenceSnapshotHash", sample.contextSha256());
        context.put("historySnapshotHash", identity.get("historySnapshotSha256"));
        context.put("memorySnapshotHash", identity.get("memorySnapshotSha256"));
        context.put("fixedFinalPromptHash", identity.get("fixedFinalPromptSha256"));
        return new Result("fundamentals_evolution_replay_v1", request.runId(), runs.get(request.runId()).repeatId(), sample.caseId(), sample.caseSha256(),
                executionFileSha256, sample.origin(), sample.executionScope(), sample.query(), sample.resolvedQuery(),
                bundle.identity(), identity, status, error, analystStatus, taskOutcome, analysis, finalAnswer,
                java.util.Collections.unmodifiableMap(context));
    }

    private Map<String, Object> comparisonIdentity(FrozenCase sample, StageResult finalAnswer) {
        List<Map<String, Object>> finalConfig = finalAnswer == null ? List.of() : finalAnswer.observations().stream()
                .filter(row -> "model-invocation".equals(row.get("kind"))).toList();
        Map<String, Object> modelConfiguration = FundamentalsRuntimeIdentity.modelConfiguration(runtime, finalConfig, timeoutSeconds, promptMaxChars);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("modelConfiguration", modelConfiguration);
        identity.put("modelConfigurationJson", canonicalJson(modelConfiguration));
        identity.put("modelConfigSha256", canonicalHash(modelConfiguration));
        identity.put("modelConfigurationComplete", !finalConfig.isEmpty());
        identity.put("fixedFinalPromptSha256", canonicalHash(ChatPromptAssembler.ordinaryFixedRules()));
        identity.put("evidenceSnapshotSha256", sample.contextSha256());
        identity.put("historySnapshotSha256", canonicalHash(sample.history()));
        identity.put("memorySnapshotSha256", canonicalHash(Map.of("researchMemory", "", "userMemory", "", "rag", List.of())));
        identity.put("runtimeBuild", buildIdentity);
        identity.put("runtimeBuildSha256", canonicalHash(buildIdentity));
        return Map.copyOf(identity);
    }

    private Map<String, Object> readBuildIdentity(Map<String, Object> actual, String path) {
        if (path == null || path.isBlank()) {
            return Map.of("status", "UNATTESTED", "runtimeArtifact", actual, "manifest", Map.of());
        }
        try {
            Map<String, Object> manifest = mapper.readValue(Files.readAllBytes(Path.of(path)),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (!manifest.keySet().equals(Set.of("schema", "gitSha", "sourceTreeSha256", "sourceState", "artifactSha256", "artifactFormat", "builtAt"))
                    || !"fundamentals_evolution_build_v1".equals(manifest.get("schema"))
                    || !"WORKTREE_SNAPSHOT".equals(manifest.get("sourceState"))
                    || !"SPRING_BOOT_JAR".equals(manifest.get("artifactFormat"))
                    || !(manifest.get("gitSha") instanceof String git) || !git.matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")
                    || !(manifest.get("sourceTreeSha256") instanceof String source) || !source.matches("[a-f0-9]{64}")
                    || !"KNOWN".equals(actual.get("status")) || !"CODE_SOURCE_FILE".equals(actual.get("scope"))
                    || !actual.get("sha256").equals(manifest.get("artifactSha256")) || !(manifest.get("builtAt") instanceof String)) {
                throw new IllegalArgumentException("构建清单与实际运行 JAR 不匹配；请使用同一次隔离构建生成的 JAR 和 build.json。");
            }
            OffsetDateTime.parse((String) manifest.get("builtAt"));
            return Map.of("status", "VERIFIED_ARTIFACT", "runtimeArtifact", actual, "manifest", Map.copyOf(manifest));
        } catch (IOException | java.time.DateTimeException | ClassCastException invalid) {
            throw new IllegalArgumentException("无法校验评测构建清单，请检查路径、字段和构建时间。", invalid);
        }
    }

    private AgentPolicyBundle requireEvaluationMethod(String id) {
        AgentPolicyBundle bundle = evaluationMethods.get(id);
        if (bundle == null) throw new IllegalArgumentException("实验执行引用了未登记的方法包，请检查评测启动清单。");
        return bundle;
    }

    private String canonicalHash(Object value) {
        return FundamentalsRuntimeIdentity.hash(value);
    }

    private String canonicalJson(Object value) {
        return FundamentalsRuntimeIdentity.json(value);
    }

    private Map<String, Object> analystDefaults() {
        return FundamentalsRuntimeIdentity.analystDefaults(runtime);
    }

    private void validateCase(FrozenCase sample) {
        if (sample == null) throw new IllegalArgumentException("回放用例不能为空。");
        requireId(sample.caseId(), "caseId");
        boolean real = "PUBLIC_AUTHORIZED".equals(sample.origin()) && "FROZEN_COMPONENT_CHAIN".equals(sample.executionScope());
        boolean synthetic = "SYNTHETIC".equals(sample.origin()) && "SYNTHETIC_COMPONENT_CHAIN".equals(sample.executionScope());
        if ((!real && !synthetic) || !"FUNDAMENTALS".equals(sample.route()) || sample.caseSha256() == null
                || !sample.caseSha256().matches("[a-f0-9]{64}") || sample.query() == null || sample.query().isBlank()
                || sample.query().length() > 12000
                || sample.resolvedQuery() == null || sample.resolvedQuery().isBlank() || sample.asOf() == null
                || sample.history() == null || sample.history().size() > 127 || sample.context() == null || sample.context().isBlank()
                || !AgentPolicyBundle.sha256(sample.context()).equals(sample.contextSha256())
                || sample.ticker() == null || sample.ticker().isBlank() || sample.requestAttributes() == null
                || sample.timeSensitivity() == null || !Set.of("NONE", "REAL_TIME", "RECENT", "HISTORICAL", "UNSPECIFIED").contains(sample.timeSensitivity())
                || sample.initialTaskOutcome() == null || !Set.of("COMPLETED", "DEGRADED").contains(sample.initialTaskOutcome()) || sample.citationIds() == null
                || sample.citationIds().isEmpty() || sample.citationIds().stream().anyMatch(id -> id == null || !id.matches("E[1-9][0-9]*"))) {
            throw new IllegalArgumentException("回放用例不符合已登记来源、时间或输入契约，请修正清单。");
        }
        if (sample.requestAttributes().entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || !(entry.getValue() instanceof String || entry.getValue() instanceof Integer
                || entry.getValue() instanceof Long || entry.getValue() instanceof Boolean))) {
            throw new IllegalArgumentException("回放请求参数只允许现有 ReadRequest 的文本、整数和布尔类型。");
        }
        if (synthetic && (!sample.requestAttributes().isEmpty() || !"HISTORICAL".equals(sample.timeSensitivity())
                || !"DEGRADED".equals(sample.initialTaskOutcome()) || sample.snapshotProvenance() != null || sample.evidenceMetadata() != null)) {
            throw new IllegalArgumentException("合成用例不能携带真实来源声明或改变固定测试场景。");
        }
        if (real) validateSourceSnapshot(sample);
        if (sample.history().stream().anyMatch(row -> row == null || row.role() == null
                || !Set.of("user", "assistant").contains(row.role()) || row.text() == null)) {
            throw new IllegalArgumentException("回放历史只接受完整的 user/assistant 文本消息。");
        }
        String expected = ToolPrefetchService.ordinaryEvidenceContext(sample.ticker(), sample.requestAttributes(),
                sample.timeSensitivity(), sample.initialTaskOutcome(), sample.context());
        if (!expected.equals(sample.preAnalystContext())
                || !AgentPolicyBundle.sha256(expected).equals(sample.preAnalystContextSha256())) {
            throw new IllegalArgumentException("回放分析师上下文与生产装配规则不一致，请重新导出登记用例。");
        }
    }

    private void validateSourceSnapshot(FrozenCase sample) {
        var provenance = sample.snapshotProvenance();
        if (provenance == null || sample.evidenceMetadata() == null || sample.evidenceMetadata().isEmpty()
                || java.util.stream.Stream.of(provenance.authorizationRef(), provenance.authorizedBy(), provenance.reviewedBy())
                    .anyMatch(value -> value == null || value.isBlank())
                || provenance.sourceManifestSha256() == null || !provenance.sourceManifestSha256().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("真实快照缺少来源授权、独立复核或导入清单指纹。");
        }
        try {
            var asOf = OffsetDateTime.parse(provenance.asOfInstant());
            var captured = OffsetDateTime.parse(provenance.capturedAt()).toInstant();
            OffsetDateTime.parse(provenance.reviewedAt());
            if (!asOf.toLocalDate().equals(sample.asOf())) throw new IllegalArgumentException("研究日期与冻结时间不一致。");
            List<String> sections = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (var row : sample.evidenceMetadata()) {
                if (row == null || row.evidenceId() == null || !row.evidenceId().matches("E[1-9][0-9]*") || ids.contains(row.evidenceId())
                        || java.util.stream.Stream.of(row.target(), row.sourceRef(), row.sourceVersion(), row.visibleText())
                            .anyMatch(value -> value == null || value.isBlank())
                        || row.rawSha256() == null || !row.rawSha256().matches("[a-f0-9]{64}")
                        || !AgentPolicyBundle.sha256(row.visibleText()).equals(row.visibleSha256())) {
                    throw new IllegalArgumentException("真实快照证据身份或可见正文哈希无效。");
                }
                var published = OffsetDateTime.parse(row.publishedAt()).toInstant();
                var available = OffsetDateTime.parse(row.availableAt()).toInstant();
                if (published.isAfter(available) || available.isAfter(asOf.toInstant())
                        || OffsetDateTime.parse(row.retrievedAt()).toInstant().isAfter(captured)) {
                    throw new IllegalArgumentException("真实快照证据跨越冻结时间边界。");
                }
                if (row.periodStart() != null) LocalDate.parse(row.periodStart());
                if (row.periodEnd() != null) LocalDate.parse(row.periodEnd());
                if (row.periodStart() != null && row.periodEnd() != null && row.periodStart().compareTo(row.periodEnd()) > 0) {
                    throw new IllegalArgumentException("证据业务期间起止颠倒。");
                }
                Map<String, Object> header = mapper.convertValue(row, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                header.remove("visibleText");
                sections.add("[" + row.evidenceId() + "] " + canonicalJson(header) + "\n" + row.visibleText());
                ids.add(row.evidenceId());
            }
            if (!ids.equals(sample.citationIds()) || !String.join("\n\n", sections).equals(sample.context())) {
                throw new IllegalArgumentException("真实快照的可见证据与来源元数据不匹配，请重新导出。");
            }
        } catch (java.time.DateTimeException | NullPointerException invalid) {
            throw new IllegalArgumentException("真实快照必须保留带时区的来源时间及有效业务期间。", invalid);
        }
    }

    private static void requireId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException(field + " 必须为 1 至 120 位字母、数字、点、下划线或连字符。");
        }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    public record Request(String caseId, String bundleId, String runId) {}
    public record ExecutionFile(int schemaVersion, List<FrozenCase> cases, List<RegisteredRun> runs, EvaluationContext context) {}
    public record EvaluationContext(String experimentId, String evaluatorVersion, String runMode) {}
    public record BundleFile(int schemaVersion, List<AgentPolicyBundle> bundles) {}
    public record RegisteredRun(String runId, String caseId, String bundleId, int repeatId) {}
    public record FrozenCase(String caseId, String origin, String route, String caseSha256, String query,
                             String resolvedQuery, List<PromptMessage> history, LocalDate asOf, String context,
                             String contextSha256, String executionScope, String ticker, Map<String, Object> requestAttributes,
                             String timeSensitivity, String initialTaskOutcome, List<String> citationIds,
                             String preAnalystContext, String preAnalystContextSha256,
                             SnapshotProvenance snapshotProvenance, List<EvidenceMetadata> evidenceMetadata) {}
    public record SnapshotProvenance(String authorizationRef, String authorizedBy, String capturedAt, String asOfInstant,
                                     String reviewedBy, String reviewedAt, String sourceManifestSha256) {}
    public record EvidenceMetadata(String evidenceId, String target, String sourceRef, String sourceVersion,
                                   String publishedAt, String availableAt, String rawSha256, String visibleSha256,
                                   String periodStart, String periodEnd, String unit, String currency, String retrievedAt,
                                   String visibleText) {}
    public record StageResult(String scope, String status, String answer, List<PromptMessage> messages, String promptSha256,
                              String context, String contextSha256, String evidenceContext, String evidenceSha256,
                              boolean evidenceCaptureComplete, List<Map<String, Object>> observations,
                              long durationMs, long timeoutSeconds, String errorCode) {}
    public record Result(String schema, String runId, int repeatId, String caseId, String caseSha256, String executionFileSha256,
                         String origin, String executionScope, String query, String resolvedQuery,
                         Map<String, String> methodBundle, Map<String, Object> comparisonIdentity,
                         String status, String errorCode, String analystStatus,
                         String taskOutcome, StageResult analysis, StageResult finalAnswer, Map<String, Object> runContext) {}
}
