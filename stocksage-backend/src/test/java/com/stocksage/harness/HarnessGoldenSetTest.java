package com.stocksage.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.agent.DebateDecisionPolicy;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.ParseStatus;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.harness.HarnessModels.TargetResolutionStatus;
import com.stocksage.harness.HarnessModels.ViolationCode;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.DebateModels.ArgumentAssessment;
import com.stocksage.model.dto.DebateModels.AssessmentReasonCode;
import com.stocksage.model.dto.DebateModels.DebateVerdict;
import com.stocksage.model.dto.DebateModels.LeadingSide;
import com.stocksage.model.dto.InvestmentReport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the repository Golden Set against the same Java policy used by the production pipeline.
 *
 * <p>The fixture schema is deliberately strict. A case must describe a unique production-policy
 * input and the complete expected decision contract: outcome, violation codes, recovery actions,
 * and whether a recommendation is allowed.</p>
 */
class HarnessGoldenSetTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-24T00:00:00Z");
    private static final String DATA_SNAPSHOT_HASH = "golden-snapshot-v1";
    private static final String CASE_SCHEMA_VERSION = "harness_golden_case_v2";
    private static final int MIN_CASES = 60;
    private static final int MIN_EVIDENCE_CASES = 40;
    private static final int MIN_REPORT_CASES = 20;
    private static final Pattern CASE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{2,95}");

    private static final Set<ViolationCode> ACTIVE_VIOLATIONS =
            EnumSet.complementOf(EnumSet.of(
                    ViolationCode.RAG_MISSING,
                    ViolationCode.DEBATE_CONTRACT_INVALID,
                    ViolationCode.DEBATE_ASSESSMENT_INVALID
            ));
    private static final Set<RecoveryAction> ACTIVE_RECOVERY_ACTIONS = EnumSet.of(
            RecoveryAction.RETRY_FUNDAMENTALS,
            RecoveryAction.RETRY_MARKET,
            RecoveryAction.RESYNTHESIZE_REPORT,
            RecoveryAction.RETURN_NOT_RATED,
            RecoveryAction.RETURN_SAFE_REFUSAL
    );

    @Test
    void productionPolicyMatchesBroadGoldenSetWithoutUnsafePasses() throws Exception {
        Path casesPath = resolveCasesPath();
        List<GoldenCase> cases = loadCases(casesPath);
        String datasetSha256 = sha256(Files.readAllBytes(casesPath));
        DeepResearchCompletionPolicy policy = new DeepResearchCompletionPolicy();
        long startedAt = System.nanoTime();
        List<Map<String, Object>> rows = new ArrayList<>();
        int outcomeMatches = 0;
        int violationMatches = 0;
        int recoveryMatches = 0;
        int recommendationMatches = 0;
        int contractMatches = 0;
        int unsafePasses = 0;

        for (GoldenCase goldenCase : cases) {
            HarnessDecision decision = goldenCase.evaluate(policy);
            List<ViolationCode> actualViolations = decision.violations().stream()
                    .map(HarnessModels.HarnessViolation::code)
                    .toList();
            List<RecoveryAction> actualRecoveries = decision.recoveryActions();
            boolean outcomeMatched = goldenCase.expected().outcome() == decision.outcome();
            boolean violationsMatched = goldenCase.expected().violations().equals(actualViolations);
            boolean recoveriesMatched =
                    goldenCase.expected().recoveryActions().equals(actualRecoveries);
            boolean recommendationMatched =
                    goldenCase.expected().allowsRecommendation() == decision.allowsRecommendation();
            boolean matched = outcomeMatched
                    && violationsMatched
                    && recoveriesMatched
                    && recommendationMatched;

            outcomeMatches += outcomeMatched ? 1 : 0;
            violationMatches += violationsMatched ? 1 : 0;
            recoveryMatches += recoveriesMatched ? 1 : 0;
            recommendationMatches += recommendationMatched ? 1 : 0;
            contractMatches += matched ? 1 : 0;
            unsafePasses += decision.outcome() == HarnessOutcome.PASS
                    && (!goldenCase.expected().allowsRecommendation()
                    || goldenCase.expected().outcome() != HarnessOutcome.PASS) ? 1 : 0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", goldenCase.id());
            row.put("phase", goldenCase.phase().name());
            row.put("category", goldenCase.category());
            row.put("tags", goldenCase.tags());
            row.put("expected_outcome", goldenCase.expected().outcome().name());
            row.put("actual_outcome", decision.outcome().name());
            row.put("expected_violations", enumNames(goldenCase.expected().violations()));
            row.put("actual_violations", enumNames(actualViolations));
            row.put("actual_violation_details", decision.violations().stream()
                    .map(violation -> {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("code", violation.code().name());
                        detail.put("dimension",
                                violation.dimension() == null ? null : violation.dimension().name());
                        return detail;
                    })
                    .toList());
            row.put("expected_recovery_actions",
                    enumNames(goldenCase.expected().recoveryActions()));
            row.put("actual_recovery_actions", enumNames(actualRecoveries));
            row.put("expected_allows_recommendation",
                    goldenCase.expected().allowsRecommendation());
            row.put("actual_allows_recommendation", decision.allowsRecommendation());
            row.put("outcome_matched", outcomeMatched);
            row.put("violations_matched", violationsMatched);
            row.put("recoveries_matched", recoveriesMatched);
            row.put("allows_recommendation_matched", recommendationMatched);
            row.put("matched", matched);
            rows.add(row);
        }

        CoverageSummary coverage = coverage(cases);
        double contractRate = rate(contractMatches, cases.size());
        double violationRate = rate(violationMatches, cases.size());
        double recoveryRate = rate(recoveryMatches, cases.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "harness_eval_v1");
        result.put("case_schema_version", CASE_SCHEMA_VERSION);
        result.put("engine", "java-production-policy");
        result.put("policy_id", policy.policyId());
        result.put("policy_version", policy.policyVersion());
        result.put("dataset_sha256", datasetSha256);
        result.put("status", contractRate == 1.0
                && unsafePasses == 0
                && coverage.unmetRequirements().isEmpty() ? "pass" : "fail");
        result.put("case_count", cases.size());
        result.put("exact_accuracy", contractRate);
        result.put("decision_contract_exact_match_rate", contractRate);
        result.put("outcome_exact_match_rate", rate(outcomeMatches, cases.size()));
        result.put("violation_exact_match_rate", violationRate);
        result.put("recovery_exact_match_rate", recoveryRate);
        result.put("allows_recommendation_exact_match_rate",
                rate(recommendationMatches, cases.size()));
        result.put("wrong_violation_count", cases.size() - violationMatches);
        result.put("wrong_recovery_count", cases.size() - recoveryMatches);
        result.put("unsafe_pass_count", unsafePasses);
        result.put("coverage", coverage.asMap());
        result.put("duration_ms", (System.nanoTime() - startedAt) / 1_000_000);
        result.put("rows", rows);
        writeResultIfRequested(result);

        assertThat(cases).hasSizeGreaterThanOrEqualTo(MIN_CASES);
        assertThat(coverage.unmetRequirements())
                .as("Golden Set coverage requirements")
                .isEmpty();
        assertThat(unsafePasses).as("unsafe PASS decisions").isZero();
        assertThat(rows)
                .filteredOn(row -> !Boolean.TRUE.equals(row.get("matched")))
                .as("Golden cases that diverged from the complete production decision contract")
                .isEmpty();
    }

    private Path resolveCasesPath() {
        String configured = System.getProperty("harness.cases", "").strip();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        List<Path> candidates = List.of(
                Path.of("..", "rag-eval", "harness_golden_set.jsonl"),
                Path.of("rag-eval", "harness_golden_set.jsonl")
        );
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot find rag-eval/harness_golden_set.jsonl"
                ));
    }

    private List<GoldenCase> loadCases(Path path) throws IOException {
        List<GoldenCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> fixtureFingerprints = new HashSet<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = OBJECT_MAPPER.readTree(line);
            requireObject(node, "case", lineNumber);
            requireAllowedFields(node, lineNumber, "case",
                    "id", "phase", "category", "tags", "fixture", "expected");
            String id = requiredText(node, "id", lineNumber);
            if (!CASE_ID.matcher(id).matches()) {
                throw invalid(lineNumber, "id must match " + CASE_ID.pattern());
            }
            if (!ids.add(id)) {
                throw invalid(lineNumber, "duplicate id: " + id);
            }
            HarnessPhase phase = requiredEnum(node, "phase", HarnessPhase.class, lineNumber);
            String category = requiredText(node, "category", lineNumber);
            List<String> tags = requiredTextArray(node, "tags", lineNumber, false);
            JsonNode fixture = requiredObjectField(node, "fixture", lineNumber);
            validateFixture(phase, fixture, lineNumber);
            ExpectedDecision expected = expectedDecision(
                    requiredObjectField(node, "expected", lineNumber), lineNumber);

            ObjectNode semanticInput = OBJECT_MAPPER.createObjectNode();
            semanticInput.put("phase", phase.name());
            semanticInput.set("fixture", canonicalize(fixture));
            String fingerprint = sha256(
                    OBJECT_MAPPER.writeValueAsBytes(canonicalize(semanticInput)));
            if (!fixtureFingerprints.add(fingerprint)) {
                throw invalid(lineNumber,
                        "duplicate semantic fixture; changing only id/category/tags is not coverage");
            }
            cases.add(new GoldenCase(id, phase, category, tags, fixture, expected));
        }
        if (cases.size() < MIN_CASES) {
            throw new IllegalArgumentException(
                    "Harness Golden Set must contain at least " + MIN_CASES
                            + " cases, found " + cases.size());
        }
        return List.copyOf(cases);
    }

    private static void validateFixture(
            HarnessPhase phase,
            JsonNode fixture,
            int lineNumber
    ) {
        if (phase == HarnessPhase.EVIDENCE) {
            requireAllowedFields(fixture, lineNumber, "evidence fixture",
                    "workflow", "target_status", "target_key", "evidence", "attempts");
            requiredEnum(fixture, "target_status", TargetResolutionStatus.class, lineNumber);
            requirePresent(fixture, "target_key", lineNumber);
            validateNestedEvidence(
                    requiredObjectField(fixture, "evidence", lineNumber), lineNumber);
            validateAttempts(requiredObjectField(fixture, "attempts", lineNumber), lineNumber);
            return;
        }
        requireAllowedFields(fixture, lineNumber, "report fixture",
                "workflow", "ledger", "attempts", "synthesis");
        JsonNode ledger = requirePresent(fixture, "ledger", lineNumber);
        if (ledger.isTextual()) {
            if (!"COMPLETE".equals(ledger.asText())) {
                throw invalid(lineNumber, "ledger text value must be COMPLETE");
            }
        } else {
            validateLedgerObject(ledger, lineNumber);
        }
        validateAttempts(requiredObjectField(fixture, "attempts", lineNumber), lineNumber);
        validateSynthesis(requiredObjectField(fixture, "synthesis", lineNumber), lineNumber);
    }

    private static void validateNestedEvidence(JsonNode evidence, int lineNumber) {
        evidence.fieldNames().forEachRemaining(field -> {
            parseEnum(EvidenceDimension.class, field, lineNumber, "evidence dimension");
            JsonNode envelopes = evidence.get(field);
            if (!envelopes.isArray()) {
                throw invalid(lineNumber, "evidence." + field + " must be an array");
            }
            for (JsonNode envelope : envelopes) {
                validateEnvelope(envelope, lineNumber, false);
            }
        });
    }

    private static void validateLedgerObject(JsonNode ledger, int lineNumber) {
        requireObject(ledger, "ledger", lineNumber);
        requireAllowedFields(ledger, lineNumber, "ledger", "target", "evidence");
        JsonNode target = requiredObjectField(ledger, "target", lineNumber);
        requireAllowedFields(target, lineNumber, "ledger target",
                "canonical_key", "display_symbol", "status");
        requirePresent(target, "canonical_key", lineNumber);
        requirePresent(target, "display_symbol", lineNumber);
        requiredEnum(target, "status", TargetResolutionStatus.class, lineNumber);
        JsonNode evidence = requirePresent(ledger, "evidence", lineNumber);
        if (!evidence.isArray()) {
            throw invalid(lineNumber, "ledger.evidence must be an array");
        }
        for (JsonNode envelope : evidence) {
            validateEnvelope(envelope, lineNumber, true);
        }
    }

    private static void validateEnvelope(
            JsonNode envelope,
            int lineNumber,
            boolean dimensionRequired
    ) {
        requireObject(envelope, "evidence envelope", lineNumber);
        requireAllowedFields(envelope, lineNumber, "evidence envelope",
                "valid", "evidence_id", "dimension", "capability_id", "target_key", "status",
                "source_ref", "provider", "observed_at", "as_of", "payload_hash", "approved");
        requiredEnum(envelope, "status", EvidenceStatus.class, lineNumber);
        if (dimensionRequired) {
            requiredEnum(envelope, "dimension", EvidenceDimension.class, lineNumber);
        } else if (envelope.has("dimension")) {
            requiredEnum(envelope, "dimension", EvidenceDimension.class, lineNumber);
        }
        if (envelope.has("valid") && !envelope.get("valid").isBoolean()) {
            throw invalid(lineNumber, "evidence envelope valid must be boolean");
        }
        if (envelope.has("approved") && !envelope.get("approved").isBoolean()) {
            throw invalid(lineNumber, "evidence envelope approved must be boolean");
        }
        validateInstantIfPresent(envelope, "observed_at", lineNumber);
        validateInstantIfPresent(envelope, "as_of", lineNumber);
    }

    private static void validateAttempts(JsonNode attempts, int lineNumber) {
        attempts.fields().forEachRemaining(entry -> {
            parseEnum(RecoveryAction.class, entry.getKey(), lineNumber, "recovery action");
            if (!entry.getValue().canConvertToInt() || entry.getValue().asInt() < 0) {
                throw invalid(lineNumber,
                        "attempt count for " + entry.getKey() + " must be a non-negative integer");
            }
        });
    }

    private static void validateSynthesis(JsonNode synthesis, int lineNumber) {
        requireAllowedFields(synthesis, lineNumber, "synthesis",
                "mode", "parse_status", "report");
        String mode = requiredText(synthesis, "mode", lineNumber).toUpperCase(Locale.ROOT);
        if (!Set.of("REPORT", "NULL_SYNTHESIS", "NULL_REPORT").contains(mode)) {
            throw invalid(lineNumber, "unsupported synthesis mode: " + mode);
        }
        requiredEnum(synthesis, "parse_status", ParseStatus.class, lineNumber);
        if ("REPORT".equals(mode)) {
            validateReport(requiredObjectField(synthesis, "report", lineNumber), lineNumber);
        } else if (synthesis.has("report") && !synthesis.get("report").isNull()) {
            throw invalid(lineNumber, "report must be absent or null for synthesis mode " + mode);
        }
    }

    private static void validateReport(JsonNode report, int lineNumber) {
        requireAllowedFields(report, lineNumber, "report",
                "ticker", "recommendation", "analyst_summary", "data_freshness",
                "rationale", "risk_factors", "unknowns", "evidence_items",
                "decision_audit");
        for (String field : List.of(
                "ticker", "recommendation", "analyst_summary", "data_freshness",
                "rationale", "risk_factors", "unknowns", "evidence_items")) {
            requirePresent(report, field, lineNumber);
        }
        for (String field : List.of("rationale", "risk_factors", "unknowns")) {
            JsonNode value = report.get(field);
            if (!value.isNull() && !value.isArray()) {
                throw invalid(lineNumber, "report." + field + " must be an array or null");
            }
        }
        JsonNode items = report.get("evidence_items");
        if (!items.isNull() && !items.isArray()) {
            throw invalid(lineNumber, "report.evidence_items must be an array or null");
        }
        if (items.isArray()) {
            for (JsonNode item : items) {
                requireObject(item, "evidence item", lineNumber);
                requireAllowedFields(item, lineNumber, "evidence item",
                        "dimension", "source_evidence_ids");
                requirePresent(item, "dimension", lineNumber);
                JsonNode ids = requirePresent(item, "source_evidence_ids", lineNumber);
                if (!ids.isNull() && !ids.isArray()) {
                    throw invalid(lineNumber,
                            "evidence item source_evidence_ids must be an array or null");
                }
            }
        }
        if (report.has("decision_audit")) {
            String mode = requiredText(report, "decision_audit", lineNumber)
                    .toUpperCase(Locale.ROOT);
            if (!Set.of("VALID", "MISSING", "INVALID").contains(mode)) {
                throw invalid(lineNumber, "unsupported decision_audit mode: " + mode);
            }
        }
    }

    private static ExpectedDecision expectedDecision(JsonNode node, int lineNumber) {
        requireAllowedFields(node, lineNumber, "expected",
                "outcome", "violations", "recovery_actions", "allows_recommendation");
        HarnessOutcome outcome = requiredEnum(node, "outcome", HarnessOutcome.class, lineNumber);
        List<ViolationCode> violations = requiredEnumArray(
                node, "violations", ViolationCode.class, lineNumber);
        List<RecoveryAction> recoveries = requiredEnumArray(
                node, "recovery_actions", RecoveryAction.class, lineNumber);
        JsonNode allows = requirePresent(node, "allows_recommendation", lineNumber);
        if (!allows.isBoolean()) {
            throw invalid(lineNumber, "expected.allows_recommendation must be boolean");
        }
        if (allows.asBoolean() != (outcome == HarnessOutcome.PASS)) {
            throw invalid(lineNumber,
                    "expected.allows_recommendation must agree with expected outcome");
        }
        return new ExpectedDecision(outcome, violations, recoveries, allows.asBoolean());
    }

    private static CoverageSummary coverage(List<GoldenCase> cases) {
        Map<String, Integer> phaseCounts = new TreeMap<>();
        Map<String, Integer> categoryCounts = new TreeMap<>();
        Map<String, Integer> outcomeCounts = new TreeMap<>();
        Map<String, Integer> violationCounts = new TreeMap<>();
        Map<String, Integer> recoveryCounts = new TreeMap<>();
        Map<String, Integer> evidenceStatusCounts = new TreeMap<>();
        Map<String, Integer> parseStatusCounts = new TreeMap<>();

        for (GoldenCase goldenCase : cases) {
            increment(phaseCounts, goldenCase.phase().name());
            increment(categoryCounts, goldenCase.category());
            increment(outcomeCounts, goldenCase.expected().outcome().name());
            goldenCase.expected().violations().forEach(code ->
                    increment(violationCounts, code.name()));
            goldenCase.expected().recoveryActions().forEach(action ->
                    increment(recoveryCounts, action.name()));
            goldenCase.declaredEvidenceStatuses().forEach(status ->
                    increment(evidenceStatusCounts, status.name()));
            goldenCase.declaredParseStatuses().forEach(status ->
                    increment(parseStatusCounts, status.name()));
        }

        List<String> unmet = new ArrayList<>();
        requireCoverage(unmet, cases.size() >= MIN_CASES,
                "case_count must be >= " + MIN_CASES);
        requireCoverage(unmet,
                phaseCounts.getOrDefault(HarnessPhase.EVIDENCE.name(), 0) >= MIN_EVIDENCE_CASES,
                "EVIDENCE case_count must be >= " + MIN_EVIDENCE_CASES);
        requireCoverage(unmet,
                phaseCounts.getOrDefault(HarnessPhase.REPORT.name(), 0) >= MIN_REPORT_CASES,
                "REPORT case_count must be >= " + MIN_REPORT_CASES);
        requireCoverage(unmet, categoryCounts.size() >= 8,
                "at least 8 semantic categories are required");
        requireCoverage(unmet,
                outcomeCounts.keySet().containsAll(enumNames(EnumSet.allOf(HarnessOutcome.class))),
                "all HarnessOutcome values must be covered");
        requireCoverage(unmet,
                evidenceStatusCounts.keySet()
                        .containsAll(enumNames(EnumSet.allOf(EvidenceStatus.class))),
                "all EvidenceStatus values must be covered");
        requireCoverage(unmet,
                parseStatusCounts.keySet().containsAll(enumNames(EnumSet.allOf(ParseStatus.class))),
                "all ParseStatus values must be covered");
        requireCoverage(unmet,
                violationCounts.keySet().containsAll(enumNames(ACTIVE_VIOLATIONS)),
                "all active ViolationCode values must be covered");
        requireCoverage(unmet,
                recoveryCounts.keySet().containsAll(enumNames(ACTIVE_RECOVERY_ACTIONS)),
                "all active RecoveryAction values must be covered");

        return new CoverageSummary(
                phaseCounts,
                categoryCounts,
                outcomeCounts,
                violationCounts,
                recoveryCounts,
                evidenceStatusCounts,
                parseStatusCounts,
                List.of(
                        ViolationCode.RAG_MISSING.name(),
                        ViolationCode.DEBATE_CONTRACT_INVALID.name(),
                        ViolationCode.DEBATE_ASSESSMENT_INVALID.name()
                ),
                List.of(
                        RecoveryAction.RETRY_NEWS.name(),
                        RecoveryAction.USE_APPROVED_FALLBACK.name()
                ),
                List.copyOf(unmet)
        );
    }

    private static void requireCoverage(
            List<String> unmet,
            boolean condition,
            String message
    ) {
        if (!condition) {
            unmet.add(message);
        }
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static List<String> enumNames(Iterable<? extends Enum<?>> values) {
        List<String> names = new ArrayList<>();
        values.forEach(value -> names.add(value.name()));
        return List.copyOf(names);
    }

    private static void writeResultIfRequested(Map<String, Object> result) throws IOException {
        String configured = System.getProperty("harness.output", "").strip();
        if (configured.isEmpty()) {
            return;
        }
        Path output = Path.of(configured).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private static RunContext runContext(JsonNode fixture) {
        EnumMap<RecoveryAction, Integer> attempts = new EnumMap<>(RecoveryAction.class);
        fixture.path("attempts").fields().forEachRemaining(entry -> attempts.put(
                RecoveryAction.valueOf(entry.getKey().toUpperCase(Locale.ROOT)),
                entry.getValue().asInt()
        ));
        String workflow = textOrDefault(fixture, "workflow", "DEEP");
        return new RunContext(workflow, attempts);
    }

    private static EvidenceLedger evidenceLedger(JsonNode fixture, HarnessPhase phase) {
        if (phase == HarnessPhase.REPORT) {
            JsonNode ledger = fixture.get("ledger");
            if (ledger.isTextual()) {
                return completeLedger();
            }
            JsonNode targetNode = ledger.get("target");
            TargetIdentity target = targetIdentity(
                    TargetResolutionStatus.valueOf(
                            targetNode.get("status").asText().toUpperCase(Locale.ROOT)),
                    nullableText(targetNode, "canonical_key"),
                    nullableText(targetNode, "display_symbol")
            );
            List<EvidenceEnvelope> evidence = new ArrayList<>();
            int index = 0;
            for (JsonNode envelope : ledger.get("evidence")) {
                EvidenceDimension dimension = EvidenceDimension.valueOf(
                        envelope.get("dimension").asText().toUpperCase(Locale.ROOT));
                evidence.add(evidenceEnvelope(envelope, dimension, index++, target));
            }
            return new EvidenceLedger(target, evidence);
        }

        TargetResolutionStatus status = TargetResolutionStatus.valueOf(
                fixture.get("target_status").asText().toUpperCase(Locale.ROOT));
        String targetKey = nullableText(fixture, "target_key");
        TargetIdentity target = targetIdentity(status, targetKey, targetKey);
        List<EvidenceEnvelope> evidence = new ArrayList<>();
        JsonNode evidenceNode = fixture.get("evidence");
        evidenceNode.fields().forEachRemaining(entry -> {
            EvidenceDimension dimension = EvidenceDimension.valueOf(
                    entry.getKey().toUpperCase(Locale.ROOT));
            int index = 0;
            for (JsonNode envelope : entry.getValue()) {
                evidence.add(evidenceEnvelope(envelope, dimension, index++, target));
            }
        });
        return new EvidenceLedger(target, evidence);
    }

    private static TargetIdentity targetIdentity(
            TargetResolutionStatus status,
            String canonicalKey,
            String displaySymbol
    ) {
        return switch (status) {
            case RESOLVED -> TargetIdentity.resolved(canonicalKey);
            case UNRESOLVED -> TargetIdentity.unresolved();
            case AMBIGUOUS -> new TargetIdentity(canonicalKey, displaySymbol, status);
        };
    }

    private static EvidenceEnvelope evidenceEnvelope(
            JsonNode node,
            EvidenceDimension dimension,
            int index,
            TargetIdentity target
    ) {
        boolean valid = !node.has("valid") || node.get("valid").asBoolean();
        String suffix = dimension.name().toLowerCase(Locale.ROOT) + "-" + index;
        String targetDefault = target.canonicalKey().isBlank() ? "AAPL" : target.canonicalKey();
        return new EvidenceEnvelope(
                textOrDefault(node, "evidence_id", valid ? "e-" + suffix : ""),
                dimension,
                textOrDefault(node, "capability_id", "golden." + dimension.name().toLowerCase(
                        Locale.ROOT)),
                textOrDefault(node, "target_key", targetDefault),
                EvidenceStatus.valueOf(node.get("status").asText().toUpperCase(Locale.ROOT)),
                textOrDefault(node, "source_ref", valid ? "fixture:" + suffix : ""),
                textOrDefault(node, "provider", valid ? "golden-provider" : ""),
                instantOrDefault(node, "observed_at", valid ? OBSERVED_AT : null),
                instantOrDefault(node, "as_of", valid ? OBSERVED_AT : null),
                textOrDefault(node, "payload_hash", valid ? "hash-" + suffix : ""),
                booleanOrDefault(node, "approved", true)
        );
    }

    private static EvidenceLedger completeLedger() {
        TargetIdentity target = TargetIdentity.resolved("AAPL");
        List<EvidenceEnvelope> evidence = new ArrayList<>();
        for (EvidenceDimension dimension : List.of(
                EvidenceDimension.FUNDAMENTALS,
                EvidenceDimension.MARKET,
                EvidenceDimension.NEWS)) {
            ObjectNode envelope = OBJECT_MAPPER.createObjectNode();
            envelope.put("status", EvidenceStatus.AVAILABLE.name());
            evidence.add(evidenceEnvelope(envelope, dimension, 0, target));
        }
        return new EvidenceLedger(target, evidence);
    }

    private static SynthesisResult synthesisResult(JsonNode fixture, EvidenceLedger ledger) {
        JsonNode synthesis = fixture.get("synthesis");
        String mode = synthesis.get("mode").asText().toUpperCase(Locale.ROOT);
        ParseStatus parseStatus = ParseStatus.valueOf(
                synthesis.get("parse_status").asText().toUpperCase(Locale.ROOT));
        if ("NULL_SYNTHESIS".equals(mode)) {
            return null;
        }
        if ("NULL_REPORT".equals(mode)) {
            return new SynthesisResult(null, parseStatus, List.of("golden null report"));
        }
        JsonNode reportNode = synthesis.get("report");
        String rawRecommendation = nullableText(reportNode, "recommendation");
        String recommendation = rawRecommendation == null
                ? null
                : rawRecommendation.strip().toUpperCase(Locale.ROOT);
        AnalysisHorizon horizon = AnalysisHorizon.UNSPECIFIED;
        InvestmentReport report = InvestmentReport.builder()
                .ticker(nullableText(reportNode, "ticker"))
                .dataSnapshotHash(DATA_SNAPSHOT_HASH)
                .recommendation(recommendation)
                .analysisHorizon(horizon)
                .decisionAudit(decisionAudit(reportNode, ledger, recommendation, horizon))
                .analystSummary(nullableText(reportNode, "analyst_summary"))
                .dataFreshness(nullableText(reportNode, "data_freshness"))
                .rationale(nullableTextList(reportNode, "rationale"))
                .riskFactors(nullableTextList(reportNode, "risk_factors"))
                .unknowns(nullableTextList(reportNode, "unknowns"))
                .evidenceItems(evidenceItems(reportNode.get("evidence_items")))
                .build();
        return new SynthesisResult(report, parseStatus, List.of());
    }

    private static DebateVerdict decisionAudit(
            JsonNode reportNode,
            EvidenceLedger ledger,
            String recommendation,
            AnalysisHorizon horizon
    ) {
        String mode = textOrDefault(reportNode, "decision_audit", "VALID")
                .toUpperCase(Locale.ROOT);
        if ("MISSING".equals(mode)) {
            return null;
        }
        boolean invalid = "INVALID".equals(mode);
        String evidenceId = invalid
                ? "e-unknown"
                : ledger.usableEvidenceIds().stream().sorted().findFirst().orElse("");
        List<ArgumentAssessment> assessments = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new ArgumentAssessment(
                        "golden-thesis-" + index,
                        4, 4, 4, 3, 3,
                        List.of(evidenceId),
                        List.of(),
                        List.of(AssessmentReasonCode.SUPPORTED),
                        "Golden assessment"
                ))
                .toList();
        return new DebateVerdict(
                invalid ? "stale-decision-policy" : DebateDecisionPolicy.POLICY_ID,
                invalid ? DebateDecisionPolicy.POLICY_VERSION + 1
                        : DebateDecisionPolicy.POLICY_VERSION,
                invalid ? "" : "golden-input-hash",
                invalid ? "stale-snapshot" : DATA_SNAPSHOT_HASH,
                70.0,
                70.0,
                invalid ? LeadingSide.INSUFFICIENT : LeadingSide.BALANCED,
                0.0,
                invalid ? "HOLD" : recommendation,
                invalid ? AnalysisHorizon.SHORT_TERM : horizon,
                List.of(),
                List.of(),
                assessments
        );
    }

    private static List<InvestmentReport.EvidenceItem> evidenceItems(JsonNode items) {
        if (items == null || items.isNull()) {
            return null;
        }
        List<InvestmentReport.EvidenceItem> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(InvestmentReport.EvidenceItem.builder()
                    .dimension(nullableText(item, "dimension"))
                    .evidence("Golden evidence")
                    .implication("Golden implication")
                    .source("fixture")
                    .sourceEvidenceIds(nullableTextList(item, "source_evidence_ids"))
                    .build());
        }
        return result;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOrDefault(JsonNode node, String field, String defaultValue) {
        if (!node.has(field)) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static boolean booleanOrDefault(JsonNode node, String field, boolean defaultValue) {
        return node.has(field) ? node.get(field).asBoolean() : defaultValue;
    }

    private static Instant instantOrDefault(
            JsonNode node,
            String field,
            Instant defaultValue
    ) {
        if (!node.has(field)) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return Instant.parse(value.asText());
    }

    private static List<String> nullableTextList(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        value.forEach(item -> values.add(item.isNull() ? null : item.asText()));
        return values;
    }

    private static List<String> requiredTextArray(
            JsonNode node,
            String field,
            int lineNumber,
            boolean allowEmpty
    ) {
        JsonNode array = requirePresent(node, field, lineNumber);
        if (!array.isArray()) {
            throw invalid(lineNumber, field + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid(lineNumber, field + " must contain non-blank strings");
            }
            values.add(item.asText());
        }
        if (!allowEmpty && values.isEmpty()) {
            throw invalid(lineNumber, field + " must not be empty");
        }
        return List.copyOf(values);
    }

    private static <E extends Enum<E>> List<E> requiredEnumArray(
            JsonNode node,
            String field,
            Class<E> enumType,
            int lineNumber
    ) {
        JsonNode array = requirePresent(node, field, lineNumber);
        if (!array.isArray()) {
            throw invalid(lineNumber, "expected." + field + " must be an array");
        }
        List<E> values = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw invalid(lineNumber, "expected." + field + " must contain strings");
            }
            values.add(parseEnum(enumType, item.asText(), lineNumber, "expected." + field));
        }
        return List.copyOf(values);
    }

    private static JsonNode requiredObjectField(
            JsonNode node,
            String field,
            int lineNumber
    ) {
        JsonNode value = requirePresent(node, field, lineNumber);
        requireObject(value, field, lineNumber);
        return value;
    }

    private static JsonNode requirePresent(JsonNode node, String field, int lineNumber) {
        if (node == null || !node.has(field)) {
            throw invalid(lineNumber, "missing required field: " + field);
        }
        return node.get(field);
    }

    private static String requiredText(JsonNode node, String field, int lineNumber) {
        JsonNode value = requirePresent(node, field, lineNumber);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid(lineNumber, field + " must be a non-blank string");
        }
        return value.asText().strip();
    }

    private static <E extends Enum<E>> E requiredEnum(
            JsonNode node,
            String field,
            Class<E> enumType,
            int lineNumber
    ) {
        return parseEnum(
                enumType,
                requiredText(node, field, lineNumber),
                lineNumber,
                field
        );
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> enumType,
            String raw,
            int lineNumber,
            String field
    ) {
        try {
            return Enum.valueOf(enumType, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw invalid(lineNumber,
                    "invalid " + field + " '" + raw + "', allowed: "
                            + Arrays.toString(enumType.getEnumConstants()));
        }
    }

    private static void requireObject(JsonNode node, String label, int lineNumber) {
        if (node == null || !node.isObject()) {
            throw invalid(lineNumber, label + " must be an object");
        }
    }

    private static void requireAllowedFields(
            JsonNode node,
            int lineNumber,
            String label,
            String... allowed
    ) {
        requireObject(node, label, lineNumber);
        Set<String> allowedSet = Set.of(allowed);
        node.fieldNames().forEachRemaining(field -> {
            if (!allowedSet.contains(field)) {
                throw invalid(lineNumber, "unknown " + label + " field: " + field);
            }
        });
    }

    private static void validateInstantIfPresent(
            JsonNode node,
            String field,
            int lineNumber
    ) {
        if (!node.has(field) || node.get(field).isNull() || node.get(field).asText().isBlank()) {
            return;
        }
        try {
            Instant.parse(node.get(field).asText());
        } catch (RuntimeException error) {
            throw invalid(lineNumber, field + " must be an ISO-8601 instant or null");
        }
    }

    private static IllegalArgumentException invalid(int lineNumber, String message) {
        return new IllegalArgumentException(
                "Invalid harness case at line " + lineNumber + ": " + message);
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = OBJECT_MAPPER.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field ->
                    result.set(field, canonicalize(node.get(field))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = OBJECT_MAPPER.createArrayNode();
            node.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return node.deepCopy();
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record ExpectedDecision(
            HarnessOutcome outcome,
            List<ViolationCode> violations,
            List<RecoveryAction> recoveryActions,
            boolean allowsRecommendation
    ) {
    }

    private record GoldenCase(
            String id,
            HarnessPhase phase,
            String category,
            List<String> tags,
            JsonNode fixture,
            ExpectedDecision expected
    ) {

        private HarnessDecision evaluate(DeepResearchCompletionPolicy policy) {
            EvidenceLedger ledger = evidenceLedger(fixture, phase);
            if (phase == HarnessPhase.REPORT) {
                return policy.afterReport(
                        runContext(fixture), ledger, synthesisResult(fixture, ledger));
            }
            return policy.afterEvidence(runContext(fixture), ledger);
        }

        private Set<EvidenceStatus> declaredEvidenceStatuses() {
            EnumSet<EvidenceStatus> statuses = EnumSet.noneOf(EvidenceStatus.class);
            if (phase == HarnessPhase.REPORT) {
                JsonNode ledger = fixture.get("ledger");
                if (ledger.isTextual()) {
                    statuses.add(EvidenceStatus.AVAILABLE);
                } else {
                    ledger.get("evidence").forEach(item -> statuses.add(
                            EvidenceStatus.valueOf(
                                    item.get("status").asText().toUpperCase(Locale.ROOT))));
                }
                return statuses;
            }
            fixture.get("evidence").fields().forEachRemaining(entry ->
                    entry.getValue().forEach(item -> statuses.add(
                            EvidenceStatus.valueOf(
                                    item.get("status").asText().toUpperCase(Locale.ROOT)))));
            return statuses;
        }

        private Set<ParseStatus> declaredParseStatuses() {
            if (phase != HarnessPhase.REPORT) {
                return Set.of();
            }
            return Set.of(ParseStatus.valueOf(
                    fixture.get("synthesis").get("parse_status").asText()
                            .toUpperCase(Locale.ROOT)));
        }
    }

    private record CoverageSummary(
            Map<String, Integer> phaseCounts,
            Map<String, Integer> categoryCounts,
            Map<String, Integer> outcomeCounts,
            Map<String, Integer> violationCounts,
            Map<String, Integer> recoveryActionCounts,
            Map<String, Integer> evidenceStatusCounts,
            Map<String, Integer> parseStatusCounts,
            List<String> exemptViolationCodes,
            List<String> exemptRecoveryActions,
            List<String> unmetRequirements
    ) {
        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", unmetRequirements.isEmpty() ? "pass" : "fail");
            result.put("minimum_case_count", MIN_CASES);
            result.put("minimum_evidence_case_count", MIN_EVIDENCE_CASES);
            result.put("minimum_report_case_count", MIN_REPORT_CASES);
            result.put("phase_counts", phaseCounts);
            result.put("category_counts", categoryCounts);
            result.put("outcome_counts", outcomeCounts);
            result.put("violation_counts", violationCounts);
            result.put("recovery_action_counts", recoveryActionCounts);
            result.put("evidence_status_counts", evidenceStatusCounts);
            result.put("parse_status_counts", parseStatusCounts);
            result.put("exempt_violation_codes", exemptViolationCodes);
            result.put("exempt_recovery_actions", exemptRecoveryActions);
            result.put("duplicate_fixture_count", 0);
            result.put("unmet_requirements", unmetRequirements);
            return result;
        }
    }
}
