package com.stocksage.research;

import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.tool.ToolCallContext;

import com.stocksage.service.TickerResolutionService;

import com.stocksage.agent.DeepEvidenceReplanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.capability.CapabilityException;
import com.stocksage.capability.CapabilityGateway;
import com.stocksage.capability.CapabilityInvocationContext;
import com.stocksage.capability.CapabilityResult;
import com.stocksage.capability.LocalNewsSearchCapabilityAdapter;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.AnalysisState.EvidenceReplanAction;
import com.stocksage.model.dto.AnalysisState.EvidenceReplanState;
import com.stocksage.model.dto.AnalysisState.EvidenceReplanStatus;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 在固定 DEEP 证据阶段内执行最多一次问题相关补证。
 *
 * <p>模型只能建议 STOP 或聚焦新闻搜索；Java 固定能力、预算、ticker 和停止条件。
 * exact plan 会在工具调用前严格落库，接管者复用该计划而不会重新请求模型。</p>
 */
@Slf4j
@Service
public class DeepEvidenceReplanService {

    static final int POLICY_VERSION = 1;
    private static final int MAX_QUERY_LENGTH = 180;
    private static final String SKILL_ID = "deep-evidence-replan-v1";
    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "decision", "action", "query", "reasonCode");
    private static final Set<String> ACT_REASONS = Set.of(
            "USER_FOCUS_NOT_COVERED", "CONFLICT_NEEDS_CURRENT_SOURCE", "FRESHNESS_GAP");
    private static final Set<String> STOP_REASONS = Set.of("SUFFICIENT", "NO_SAFE_ACTION");

    private final DeepEvidenceReplanner replanner;
    private final ObjectMapper objectMapper;
    private final CapabilityGateway capabilityGateway;
    private final DeepEvidenceCollector evidenceCollector;
    private final TickerResolutionService tickerResolutionService;
    private final TraceService traceService;
    private final ChatStreamEmitter chatStreamEmitter;
    private final AsyncTaskExecutor agentTaskExecutor;

    @Value("${stocksage.agent.deep-replan.enabled:false}")
    private boolean enabled;

    @Value("${stocksage.chat.tool-prefetch.max-search-results:5}")
    private int maxSearchResults;

    @Value("${stocksage.chat.tool-prefetch.per-tool-timeout-seconds:8}")
    private long toolTimeoutSeconds;

    @Value("${stocksage.agent.prefetch.timeout-seconds:30}")
    private long plannerTimeoutSeconds;

    public Map<String, Object> runtimeConfiguration() {
        return Map.of("policyVersion", POLICY_VERSION, "enabled", enabled,
                "maxSearchResults", Math.max(1, Math.min(5, maxSearchResults)),
                "toolTimeoutSeconds", Math.max(1, toolTimeoutSeconds),
                "plannerTimeoutSeconds", Math.max(1, plannerTimeoutSeconds));
    }

    public DeepEvidenceReplanService(
            DeepEvidenceReplanner replanner,
            ObjectMapper objectMapper,
            CapabilityGateway capabilityGateway,
            DeepEvidenceCollector evidenceCollector,
            TickerResolutionService tickerResolutionService,
            TraceService traceService,
            ChatStreamEmitter chatStreamEmitter,
            AsyncTaskExecutor agentTaskExecutor
    ) {
        this.replanner = replanner;
        this.objectMapper = objectMapper;
        this.capabilityGateway = capabilityGateway;
        this.evidenceCollector = evidenceCollector;
        this.tickerResolutionService = tickerResolutionService;
        this.traceService = traceService;
        this.chatStreamEmitter = chatStreamEmitter;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    /**
     * 运行一次有界补证；调用方提供真实 ownership 检查和 owner-fenced 严格写入。
     */
    public DeepEvidenceCollector.EvidenceCollection replan(
            Long taskId,
            String userId,
            Long conversationId,
            String traceId,
            DeepEvidenceCollector.EvidenceCollection evidence,
            Runnable ownershipCheck,
            Consumer<AnalysisState> strictCheckpoint
    ) {
        if (evidence == null
                || evidence.harnessDecision().outcome() != HarnessOutcome.PASS
                || evidence.state() == null
                || !evidence.tickerResolved()
                || downstreamWorkStarted(evidence.state())) {
            return evidence;
        }
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(ownershipCheck, "ownershipCheck");
        Objects.requireNonNull(strictCheckpoint, "strictCheckpoint");

        AnalysisState state = evidence.state();
        EvidenceReplanState durable = state.getEvidenceReplanState();
        if (durable != null) {
            if (durable.status() == EvidenceReplanStatus.PLANNED) {
                validatePlanState(taskId, state.getPrimaryTicker(), durable);
            } else {
                validateTerminalState(taskId, state.getPrimaryTicker(), durable, evidence);
                return evidence;
            }
        }
        if (!enabled) {
            if (durable != null) {
                EvidenceReplanState skipped = new EvidenceReplanState(
                        POLICY_VERSION,
                        EvidenceReplanStatus.SKIPPED,
                        durable.action(),
                        durable.normalizedQuery(),
                        durable.reasonCode(),
                        durable.effectKey(),
                        List.of(),
                        "DISABLED"
                );
                persist(state, skipped, ownershipCheck, strictCheckpoint);
                recordDecision(traceId, skipped, 0L);
            }
            return evidence;
        }

        EvidenceReplanState planned = durable;
        if (planned == null) {
            ownershipCheck.run();
            Proposal proposal = decide(state);
            if (!proposal.act()) {
                EvidenceReplanState skipped = new EvidenceReplanState(
                        POLICY_VERSION,
                        EvidenceReplanStatus.SKIPPED,
                        null,
                        null,
                        proposal.reasonCode(),
                        null,
                        List.of(),
                        proposal.stopReason()
                );
                persist(state, skipped, ownershipCheck, strictCheckpoint);
                recordDecision(traceId, skipped, proposal.durationMs());
                chatStreamEmitter.emit(traceId, conversationId, "observation",
                        "CAPACITY_UNAVAILABLE".equals(proposal.stopReason())
                                ? "在线执行容量已满，未运行可选补证规划；本轮继续使用已有证据，稍后可重新发起研究。"
                                : "补证规划未追加额外搜索，继续使用已验证的基础证据。");
                return evidence;
            }

            String effectKey = effectKey(taskId, proposal.normalizedQuery());
            planned = new EvidenceReplanState(
                    POLICY_VERSION,
                    EvidenceReplanStatus.PLANNED,
                    EvidenceReplanAction.FOCUSED_NEWS_SEARCH,
                    proposal.normalizedQuery(),
                    proposal.reasonCode(),
                    effectKey,
                    List.of(),
                    null
            );
            persist(state, planned, ownershipCheck, strictCheckpoint);
            recordDecision(traceId, planned, proposal.durationMs());
        }

        ownershipCheck.run();
        chatStreamEmitter.emit(traceId, conversationId, "thought",
                "正在根据基础证据补充与当前问题直接相关的新闻来源。");
        Set<String> before = evidence.evidenceLedger().usableEvidenceIds();
        CapabilityResult result;
        try {
            result = capabilityGateway.invoke(
                    LocalNewsSearchCapabilityAdapter.ID,
                    Map.of(
                            "query", planned.normalizedQuery(),
                            "maxResults", Math.max(1, Math.min(5, maxSearchResults))
                    ),
                    new CapabilityInvocationContext(
                            userId,
                            conversationId,
                            traceId,
                            SKILL_ID,
                            Set.of(LocalNewsSearchCapabilityAdapter.ID),
                            Instant.now().plusSeconds(Math.max(1, toolTimeoutSeconds)),
                            state.getModelInvocationContext() == null ? null : new ToolCallContext.RunDeadline(
                                    state.getModelInvocationContext().runId(), state.getModelInvocationContext().deadlineEpochMs()),
                            state.getQuery(),
                            state.getModelInvocationContext() == null ? null : new ToolCallContext.RunExecution(
                                    state.getModelInvocationContext().runId(), state.getModelInvocationContext().attempt(),
                                    state.getModelInvocationContext().leaseToken(), state.getModelInvocationContext().traceId(),
                                    state.getModelInvocationContext().deadlineEpochMs())
                    )
            );
        } catch (CapabilityException error) {
            ResearchBudgetExceededException.rethrowIfPresent(error);
            String stopReason = "CAPABILITY_" + error.reason().name();
            EvidenceReplanState skipped = new EvidenceReplanState(
                    POLICY_VERSION,
                    EvidenceReplanStatus.SKIPPED,
                    planned.action(),
                    planned.normalizedQuery(),
                    planned.reasonCode(),
                    planned.effectKey(),
                    List.of(),
                    stopReason
            );
            persist(state, skipped, ownershipCheck, strictCheckpoint);
            recordExecution(traceId, skipped, before.size(), before.size(), 0L);
            chatStreamEmitter.emit(traceId, conversationId, "observation",
                    error.reason() == CapabilityException.Reason.CAPACITY_EXCEEDED
                            ? "在线执行容量已满，未运行补充搜索；本轮继续使用已有证据，稍后可重新发起研究。"
                            : "补充搜索未产生可引用新证据，继续使用已验证的基础证据。");
            return evidence;
        }

        EvidenceLedger ledgerBeforeAppend = state.getEvidenceLedger();
        String newsBeforeAppend = state.getNewsReport();
        DeepEvidenceCollector.EvidenceCollection updated;
        Set<String> after;
        List<String> added;
        EvidenceReplanState completed;
        try {
            updated = evidenceCollector.appendFocusedNews(evidence, result, traceId);
            after = updated.evidenceLedger().usableEvidenceIds();
            added = new TreeSet<>(after).stream()
                    .filter(id -> !before.contains(id))
                    .toList();
            String stopReason = added.isEmpty() ? "NO_NEW_USABLE_EVIDENCE" : "COMPLETED";
            completed = new EvidenceReplanState(
                    POLICY_VERSION,
                    EvidenceReplanStatus.REVALIDATED,
                    planned.action(),
                    planned.normalizedQuery(),
                    planned.reasonCode(),
                    planned.effectKey(),
                    added,
                    stopReason
            );
            persist(updated.state(), completed, ownershipCheck, strictCheckpoint);
        } catch (RuntimeException | Error error) {
            state.setEvidenceLedger(ledgerBeforeAppend);
            state.setNewsReport(newsBeforeAppend);
            state.setEvidenceReplanState(planned);
            throw error;
        }
        recordExecution(traceId, completed, before.size(), after.size(), result.durationMs());
        chatStreamEmitter.emit(traceId, conversationId, "observation",
                added.isEmpty()
                        ? "补充搜索未产生新的可引用证据，继续使用已验证的基础证据。"
                        : "问题相关补证完成，新增 " + added.size() + " 条可引用证据。");
        return updated;
    }

    private Proposal decide(AnalysisState state) {
        long startedAt = System.nanoTime();
        var invocation = state.getModelInvocationContext();
        if (invocation == null) {
            throw new ResearchBudgetExceededException(null, ResearchBudgetExceededException.Reason.BUDGET_UNAVAILABLE);
        }
        long waitMs = Math.min(TimeUnit.SECONDS.toMillis(Math.max(1, plannerTimeoutSeconds)), invocation.remainingMillis());
        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(() -> replanner.propose(state), agentTaskExecutor);
        } catch (java.util.concurrent.RejectedExecutionException error) {
            invocation.remainingMillis();
            return Proposal.stop("NO_SAFE_ACTION", "CAPACITY_UNAVAILABLE", elapsedMs(startedAt));
        }
        try {
            String content = future.get(waitMs, TimeUnit.MILLISECONDS);
            invocation.remainingMillis();
            return parseProposal(content, state.getPrimaryTicker(), elapsedMs(startedAt));
        } catch (Exception error) {
            future.cancel(true);
            ResearchBudgetExceededException.rethrowIfPresent(error);
            invocation.remainingMillis();
            try {
                ResearchCapacityExceededException.rethrowIfPresent(error);
            } catch (ResearchCapacityExceededException capacity) {
                return Proposal.stop("NO_SAFE_ACTION", "CAPACITY_UNAVAILABLE", elapsedMs(startedAt));
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("DEEP evidence replanner unavailable; skipping optional replan. errorType={}",
                    error.getClass().getSimpleName());
            return Proposal.stop("NO_SAFE_ACTION", "PLANNER_UNAVAILABLE", elapsedMs(startedAt));
        }
    }

    Proposal parseProposal(String content, String ticker, long durationMs) {
        try {
            JsonNode root = objectMapper.readTree(content == null ? "" : content.strip());
            if (root == null || !root.isObject()) {
                return Proposal.stop("NO_SAFE_ACTION", "INVALID_PLAN", durationMs);
            }
            Set<String> actualFields = new TreeSet<>();
            root.fieldNames().forEachRemaining(actualFields::add);
            if (!actualFields.equals(RESPONSE_FIELDS)) {
                return Proposal.stop("NO_SAFE_ACTION", "INVALID_PLAN", durationMs);
            }
            String decision = root.path("decision").asText("").strip();
            String reasonCode = root.path("reasonCode").asText("").strip();
            if ("STOP".equals(decision)) {
                boolean nullAction = root.get("action") == null || root.get("action").isNull();
                boolean nullQuery = root.get("query") == null || root.get("query").isNull();
                return nullAction && nullQuery && STOP_REASONS.contains(reasonCode)
                        ? Proposal.stop(reasonCode, reasonCode, durationMs)
                        : Proposal.stop("NO_SAFE_ACTION", "INVALID_PLAN", durationMs);
            }
            if (!"ACT".equals(decision)
                    || !"FOCUSED_NEWS_SEARCH".equals(root.path("action").asText(""))
                    || !ACT_REASONS.contains(reasonCode)) {
                return Proposal.stop("NO_SAFE_ACTION", "INVALID_PLAN", durationMs);
            }
            String normalizedQuery = normalizeQuery(ticker, root.path("query").asText(""));
            return normalizedQuery.isBlank()
                    ? Proposal.stop("NO_SAFE_ACTION", "INVALID_PLAN", durationMs)
                    : Proposal.act(normalizedQuery, reasonCode, durationMs);
        } catch (Exception error) {
            return Proposal.stop("NO_SAFE_ACTION", "INVALID_PLAN", durationMs);
        }
    }

    private String normalizeQuery(String ticker, String proposed) {
        String target = ticker == null ? "" : ticker.strip().toUpperCase(Locale.ROOT);
        String focus = proposed == null
                ? ""
                : proposed.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s+", " ").strip();
        if (target.isBlank()
                || focus.isBlank()
                || !tickerResolutionService.resolveExplicitTicker(focus).isBlank()) {
            return "";
        }
        String query = target + " " + focus;
        return query.length() <= MAX_QUERY_LENGTH
                ? query
                : query.substring(0, MAX_QUERY_LENGTH).strip();
    }

    private void validatePlanState(
            Long taskId,
            String ticker,
            EvidenceReplanState state
    ) {
        String target = ticker == null ? "" : ticker.strip().toUpperCase(Locale.ROOT);
        String query = state.normalizedQuery() == null ? "" : state.normalizedQuery();
        String prefix = target + " ";
        String focus = query.startsWith(prefix) ? query.substring(prefix.length()) : "";
        if (state.policyVersion() != POLICY_VERSION
                || state.action() != EvidenceReplanAction.FOCUSED_NEWS_SEARCH
                || !ACT_REASONS.contains(state.reasonCode())
                || target.isBlank()
                || query.isBlank()
                || query.length() > MAX_QUERY_LENGTH
                || !query.equals(query.replaceAll("\\p{Cntrl}+", " ")
                        .replaceAll("\\s+", " ").strip())
                || focus.isBlank()
                || !tickerResolutionService.resolveExplicitTicker(focus).isBlank()
                || (state.status() == EvidenceReplanStatus.PLANNED
                && (!state.addedEvidenceIds().isEmpty() || state.stopReason() != null))
                || !effectKey(taskId, query).equals(state.effectKey())) {
            throw new IllegalStateException("Invalid durable DEEP evidence replan");
        }
    }

    private void validateTerminalState(
            Long taskId,
            String ticker,
            EvidenceReplanState state,
            DeepEvidenceCollector.EvidenceCollection evidence
    ) {
        if (state.policyVersion() != POLICY_VERSION
                || state.status() == null
                || state.status() == EvidenceReplanStatus.PLANNED
                || state.stopReason() == null
                || state.stopReason().isBlank()
                || (state.status() == EvidenceReplanStatus.SKIPPED
                && !state.addedEvidenceIds().isEmpty())) {
            throw new IllegalStateException("Unsupported DEEP evidence replan checkpoint");
        }
        if (state.action() == null) {
            if (state.status() == EvidenceReplanStatus.REVALIDATED
                    || state.normalizedQuery() != null
                    || state.effectKey() != null
                    || !STOP_REASONS.contains(state.reasonCode())) {
                throw new IllegalStateException("Invalid terminal DEEP evidence replan");
            }
        } else {
            validatePlanState(taskId, ticker, state);
        }
        if (state.status() == EvidenceReplanStatus.REVALIDATED
                && !evidence.evidenceLedger().evidenceIds().containsAll(state.addedEvidenceIds())) {
            throw new IllegalStateException("DEEP evidence replan checkpoint references missing evidence");
        }
    }

    private boolean downstreamWorkStarted(AnalysisState state) {
        return state.getInvestmentReport() != null
                || state.getManagerAssessment() != null
                || state.getDebateVerdict() != null
                || (state.getDebateTurns() != null && !state.getDebateTurns().isEmpty());
    }

    private void persist(
            AnalysisState state,
            EvidenceReplanState snapshot,
            Runnable ownershipCheck,
            Consumer<AnalysisState> strictCheckpoint
    ) {
        EvidenceReplanState previous = state.getEvidenceReplanState();
        state.setEvidenceReplanState(snapshot);
        try {
            ownershipCheck.run();
            strictCheckpoint.accept(state);
        } catch (RuntimeException | Error error) {
            state.setEvidenceReplanState(previous);
            throw error;
        }
    }

    private void recordDecision(String traceId, EvidenceReplanState state, long durationMs) {
        recordTrace(traceId, "replan_decision", "deep-evidence-replan", state, 0, 0, durationMs, false);
    }

    private void recordExecution(
            String traceId,
            EvidenceReplanState state,
            int usableBefore,
            int usableAfter,
            long durationMs
    ) {
        recordTrace(
                traceId,
                "replan_execution",
                LocalNewsSearchCapabilityAdapter.ID,
                state,
                usableBefore,
                usableAfter,
                durationMs,
                true
        );
    }

    private void recordTrace(
            String traceId,
            String stepKind,
            String action,
            EvidenceReplanState state,
            int usableBefore,
            int usableAfter,
            long durationMs,
            boolean includeEffectKey
    ) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("stepKind", stepKind);
            attributes.put("replanPolicyVersion", POLICY_VERSION);
            attributes.put("decision", state.action() == null ? "STOP" : "ACT");
            attributes.put("replanAction", state.action() == null ? "" : state.action().name());
            attributes.put("status", state.status().name());
            attributes.put("reasonCode", state.reasonCode());
            attributes.put("stopReason", state.stopReason() == null ? "" : state.stopReason());
            attributes.put("usableEvidenceBefore", usableBefore);
            attributes.put("usableEvidenceAfter", usableAfter);
            attributes.put("addedEvidenceIds", state.addedEvidenceIds());
            attributes.put("tokenUsageAvailable", false);
            if (includeEffectKey && state.effectKey() != null && !state.effectKey().isBlank()) {
                attributes.put("effectKey", state.effectKey());
            }
            traceService.addStep(traceId, AgentStep.builder()
                    .thought("DEEP evidence replan " + state.status().name().toLowerCase())
                    .action(action)
                    .actionInput(includeEffectKey
                            ? "{\"argumentKeys\":[\"query\",\"maxResults\"]}"
                            : "{}")
                    .observation(state.stopReason() == null ? state.reasonCode() : state.stopReason())
                    .durationMs(durationMs)
                    .tokenCount(0)
                    .attributes(Map.copyOf(attributes))
                    .build());
        } catch (Exception error) {
            log.debug("Failed to persist DEEP evidence replan trace, traceId={}", traceId, error);
        }
    }

    private String effectKey(Long taskId, String query) {
        return "deep-replan:" + taskId + ":v" + POLICY_VERSION + ":focused-news:" + sha256(query);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    record Proposal(
            boolean act,
            String normalizedQuery,
            String reasonCode,
            String stopReason,
            long durationMs
    ) {
        static Proposal act(String query, String reasonCode, long durationMs) {
            return new Proposal(true, query, reasonCode, null, durationMs);
        }

        static Proposal stop(String reasonCode, String stopReason, long durationMs) {
            return new Proposal(false, null, reasonCode, stopReason, durationMs);
        }
    }
}
