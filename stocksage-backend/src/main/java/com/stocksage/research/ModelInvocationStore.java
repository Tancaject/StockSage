package com.stocksage.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.config.ModelPricingProperties;
import com.stocksage.config.ModelTokenBudgetProperties;
import com.stocksage.model.dto.ModelInvocationContext;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ModelInvocationStore {
    private final JdbcTemplate jdbc;
    private final ResearchTaskCheckpointRepository checkpoints;
    private final ObjectMapper objectMapper;
    private final ModelPricingProperties pricing;
    private final ModelTokenBudgetProperties tokenBudgetProperties;

    // Commit before making the external call, including when a caller has an ambient transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String begin(ModelInvocationContext context, String role, Map<String, Object> request) {
        if (role == null || role.isBlank() || role.length() > 64) {
            throw new IllegalArgumentException("Model invocation role must contain 1 to 64 characters");
        }
        boolean embedding = "EMBEDDING".equals(request.get("invocationKind"));
        if (embedding != "ollama-embedding".equals(role)) {
            throw new InvocationRejectedException("Embedding invocation kind and role must agree");
        }
        if (checkpoints.lockOwnedRunningTask(context.runId(), context.leaseToken()).isEmpty()
                || !Integer.valueOf(context.attempt()).equals(jdbc.queryForObject(
                        "SELECT attempts FROM research_tasks WHERE id = ?", Integer.class, context.runId()))) {
            throw new InvocationRejectedException("Model invocation rejected: ownership or attempt changed for run "
                    + context.runId());
        }
        var budget = jdbc.queryForObject("SELECT budget_deadline_epoch_ms, max_model_calls FROM research_tasks WHERE id = ?",
                (row, index) -> new BudgetLimits(row.getObject(1, Long.class), row.getObject(2, Integer.class)), context.runId());
        ResearchBudgetExceededException.remainingMillis(context.runId(), budget.deadlineEpochMs());
        if (budget.maxModelCalls() == null || budget.maxModelCalls() <= 0
                || context.deadlineEpochMs() != budget.deadlineEpochMs()) {
            throw new ResearchBudgetExceededException(context.runId(), ResearchBudgetExceededException.Reason.BUDGET_UNAVAILABLE);
        }
        Long calls = jdbc.queryForObject("SELECT COUNT(*) FROM research_model_invocations WHERE run_id = ?",
                Long.class, context.runId());
        if (calls >= budget.maxModelCalls()) {
            throw new ResearchBudgetExceededException(context.runId(), ResearchBudgetExceededException.Reason.MODEL_CALL_LIMIT);
        }
        String id = UUID.randomUUID().toString();
        if (context.evidenceSnapshotId() != null) {
            Integer snapshots = jdbc.queryForObject("""
                SELECT COUNT(*) FROM research_evidence_snapshots WHERE id = ? AND run_id = ? AND attempt = ?
                """, Integer.class, context.evidenceSnapshotId(), context.runId(), context.attempt());
            if (!Integer.valueOf(1).equals(snapshots)) {
                throw new InvocationRejectedException("Model invocation evidence snapshot does not belong to this run/attempt");
            }
        } else if ((!"contextual-gist".equals(role) && !embedding) || context.inputSha256() == null
                || !context.inputSha256().matches("[0-9a-f]{64}")) {
            throw new InvocationRejectedException("Pre-evidence model invocation requires gist or embedding role and input SHA-256");
        }
        Map<String, Object> capturedRequest = new LinkedHashMap<>(request);
        if (context.evidenceSnapshotId() == null) capturedRequest.put("inputAttribution",
                Map.of("kind", embedding ? "EMBEDDING_INPUT" : "PRE_EVIDENCE_INPUT", "sha256", context.inputSha256()));
        Long reservedTokens = reserveTokens(context.runId(), request);
        capturedRequest.put("priceSnapshot", priceSnapshot(context.runId(), request));
        ResearchBudgetExceededException.remainingMillis(context.runId(), budget.deadlineEpochMs());
        jdbc.update("""
                INSERT INTO research_model_invocations
                    (id, run_id, attempt, trace_id, role, request_json, status, evidence_snapshot_id, reserved_tokens, accounted_tokens)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?)
                """, id, context.runId(), context.attempt(), context.traceId(), role, json(capturedRequest),
                context.evidenceSnapshotId(), reservedTokens, reservedTokens);
        return id;
    }

    private record BudgetLimits(Long deadlineEpochMs, Integer maxModelCalls) { }

    private Long reserveTokens(Long runId, Map<String, Object> request) {
        try {
            JsonNode frozen = readObject(jdbc.queryForObject("SELECT token_budget_json FROM research_tasks WHERE id = ?",
                    String.class, runId), runId);
            JsonNode parameters = objectMapper.valueToTree(request);
            JsonNode manifest = readObject(jdbc.queryForObject(
                    "SELECT model_configuration_json FROM research_tasks WHERE id = ?", String.class, runId), runId);
            boolean embedding = "EMBEDDING".equals(request.get("invocationKind"));
            if (embedding) {
                JsonNode configured = manifest.path("rag").path("embedding");
                if (!parameters.path("provider").isObject() || !configured.path("providerIdentity").equals(parameters.path("provider"))
                        || !parameters.path("requestedModel").isTextual()
                        || !configured.path("model").equals(parameters.path("requestedModel"))) {
                    throw new IllegalArgumentException("Embedding provider or model differs from frozen manifest");
                }
            }
            if ("DISABLED".equals(frozen.path("status").asText())) return null;
            requireCurrentTokenContract(frozen, runId);
            JsonNode n = parameters.path("n");
            if (!embedding && (!Boolean.FALSE.equals(request.get("extraBodyPresent")) || !Boolean.FALSE.equals(request.get("toolsPresent"))
                    || (!n.isMissingNode() && (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() != 1)))) {
                throw new IllegalArgumentException("Unbounded model request options");
            }
            String model = request.get("requestedModel") instanceof String value ? value : null;
            long reservation;
            if (embedding) {
                JsonNode count = parameters.path("inputCount");
                JsonNode context = parameters.path("contextTokens");
                if (!count.isIntegralNumber() || !count.canConvertToInt() || !context.isIntegralNumber() || !context.canConvertToLong()) {
                    throw new IllegalArgumentException("Embedding request bounds unavailable");
                }
                JsonNode configuredContext = manifest.path("rag").path("embedding").path("contextTokens");
                if (!configuredContext.isIntegralNumber() || !configuredContext.canConvertToLong()
                        || configuredContext.longValue() != context.longValue()) {
                    throw new IllegalArgumentException("Embedding context differs from frozen manifest");
                }
                reservation = ModelTokenBudgetProperties.reserveEmbedding(frozen, parameters.path("provider"), model,
                        count.intValue(), context.longValue(), Instant.now());
            } else {
                reservation = ModelTokenBudgetProperties.reserve(frozen, manifest.path("chatProvider"), model, Instant.now());
            }
            long accounted = accountedTokens(runId);
            if (reservation > frozen.path("maxTokens").longValue() - accounted) {
                throw new ResearchBudgetExceededException(runId, ResearchBudgetExceededException.Reason.TOKEN_LIMIT);
            }
            return reservation;
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
            ResearchBudgetExceededException.rethrowIfPresent(invalid);
            throw new ResearchBudgetExceededException(runId, ResearchBudgetExceededException.Reason.BUDGET_UNAVAILABLE);
        }
    }

    private long accountedTokens(Long runId) {
        long total = 0;
        for (Long tokens : jdbc.query("SELECT accounted_tokens FROM research_model_invocations WHERE run_id = ?",
                (row, index) -> row.getObject(1, Long.class), runId)) {
            if (tokens == null || tokens < 0) throw new IllegalArgumentException("Unknown invocation token accounting");
            total = Math.addExact(total, tokens);
        }
        return total;
    }

    private void requireCurrentTokenContract(JsonNode frozen, Long runId) {
        if (!frozen.equals(readObject(json(tokenBudgetProperties.snapshot()), runId))) {
            throw new IllegalArgumentException("Frozen token budget differs from current configuration");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String invocationId, String status, Map<String, Object> response) {
        if (status == null || !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("Model invocation requires a terminal status");
        }
        var runs = jdbc.query("SELECT run_id FROM research_model_invocations WHERE id = ?",
                (row, index) -> row.getLong(1), invocationId);
        if (runs.isEmpty()) return;
        // Same lock order as begin; late results can settle even after the run's owner changes.
        jdbc.queryForObject("SELECT id FROM research_tasks WHERE id = ? FOR UPDATE", Long.class, runs.get(0));
        var rows = jdbc.query("SELECT request_json, reserved_tokens, status FROM research_model_invocations WHERE id = ? FOR UPDATE",
                (row, index) -> new Settlement(row.getString(1), row.getObject(2, Long.class), row.getString(3)), invocationId);
        if (rows.isEmpty() || !"RUNNING".equals(rows.get(0).status())) return;
        Settlement invocation = rows.get(0);
        JsonNode request = readObject(invocation.request(), null);
        Map<String, Object> capturedResponse = new LinkedHashMap<>(response);
        capturedResponse.put("costEstimate", estimate(request.path("priceSnapshot"), status,
                objectMapper.valueToTree(response)));
        // A late provider result remains an execution fact after takeover, but cannot alter the run.
        jdbc.update("""
                UPDATE research_model_invocations
                   SET status = ?, response_json = ?, accounted_tokens = ?, completed_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ? AND status = 'RUNNING'
                """, status, json(capturedResponse), settleTokens(invocation.reserved(), status,
                        objectMapper.valueToTree(response)), invocationId);
    }

    private record Settlement(String request, Long reserved, String status) { }

    private static Long settleTokens(Long reserved, String status, JsonNode response) {
        if (reserved == null) return null;
        if (!"PROVIDER".equals(response.path("usageSource").asText())) return reserved;
        Long input = tokenCount(response.path("inputTokens"));
        Long output = tokenCount(response.path("outputTokens"));
        Long total = tokenCount(response.path("totalTokens"));
        // Even incomplete usage is evidence of a lower bound, never permission to release capacity.
        long observed = Math.max(input == null ? 0 : input, output == null ? 0 : output);
        if (total != null) observed = Math.max(observed, total);
        if (input != null && output != null) {
            long sum = input > Long.MAX_VALUE - output ? Long.MAX_VALUE : input + output;
            if (input <= Long.MAX_VALUE - output && total != null && total == sum && "SUCCEEDED".equals(status)) return total;
            observed = Math.max(observed, sum);
        }
        return Math.max(reserved, observed);
    }

    private Map<String, Object> priceSnapshot(Long runId, Map<String, Object> request) {
        if ("EMBEDDING".equals(request.get("invocationKind"))) return unknownCost("EMBEDDING_NOT_PRICED");
        JsonNode manifest = readObject(jdbc.queryForObject(
                "SELECT model_configuration_json FROM research_tasks WHERE id = ?", String.class, runId), runId);
        if (!manifest.has("pricing")) return unknownCost("NO_FROZEN_RATE_CARD");
        if (!manifest.path("pricing").equals(readObject(json(pricing.snapshot()), runId))) {
            return unknownCost("FROZEN_RATE_CARD_MISMATCH");
        }
        Instant selectedAt = Instant.now();
        Map<String, Object> snapshot = new LinkedHashMap<>(pricing.select(manifest.path("chatProvider"),
                request.get("requestedModel") instanceof String model ? model : null, selectedAt));
        snapshot.put("selectedAt", selectedAt.toString());
        return Map.copyOf(snapshot);
    }

    private static Map<String, Object> estimate(JsonNode price, String status, JsonNode response) {
        if (!"KNOWN".equals(price.path("status").asText())) {
            return unknownCost(price.path("reason").asText("NO_PRICE_SNAPSHOT"));
        }
        Long input = tokenCount(response.path("inputTokens"));
        Long output = tokenCount(response.path("outputTokens"));
        if (!"PROVIDER".equals(response.path("usageSource").asText()) || input == null || output == null) {
            return unknownCost("USAGE_UNKNOWN");
        }
        if (input > price.path("maxInputTokens").longValue()) return unknownCost("INPUT_LIMIT_EXCEEDED");
        BigDecimal amount = new BigDecimal(price.path("inputPerMillion").textValue()).multiply(BigDecimal.valueOf(input))
                .add(new BigDecimal(price.path("outputPerMillion").textValue()).multiply(BigDecimal.valueOf(output)))
                .movePointLeft(6);
        return Map.of("status", "SUCCEEDED".equals(status) ? "COMPLETE_RECORDED_ESTIMATE" : "PARTIAL_ESTIMATE",
                "basis", "CONFIGURED_FLAT_RATE_ESTIMATE", "currency", price.path("currency").textValue(),
                "amount", amount.toPlainString(), "rateVersion", price.path("version").textValue());
    }

    private static Map<String, Object> unknownCost(String reason) {
        return Map.of("status", "UNKNOWN", "reason", reason);
    }

    private JsonNode readObject(String value, Long runId) {
        if (value == null) return objectMapper.createObjectNode();
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node != null && node.isObject()) return node;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot read model invocation usage for run " + runId, failure);
        }
        throw new IllegalStateException("Model invocation payload is not an object for run " + runId);
    }

    private static Long tokenCount(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                ? value.longValue() : null;
    }

    private String json(Map<String, Object> value) {
        if (value == null) {
            throw new IllegalArgumentException("Model invocation payload must be an object");
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Model invocation payload cannot be serialized", failure);
        }
    }

    public static class InvocationRejectedException extends IllegalStateException {
        public InvocationRejectedException(String message) {
            super(message);
        }
    }
}
