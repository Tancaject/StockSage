package com.stocksage.harness;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocksage.model.dto.InvestmentReport;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded, serializable contracts shared by research completion policies.
 *
 * <p>The contracts intentionally contain metadata rather than raw tool payloads. This keeps policy
 * evaluation deterministic and makes the same objects safe to persist in checkpoints and traces.</p>
 */
public final class HarnessModels {

    private HarnessModels() {
    }

    public enum EvidenceDimension {
        FUNDAMENTALS,
        MARKET,
        NEWS,
        RAG
    }

    public enum EvidenceStatus {
        AVAILABLE,
        EMPTY,
        NO_RESULTS,
        FAILED,
        TIMED_OUT,
        NOT_COLLECTED
    }

    public enum TargetResolutionStatus {
        RESOLVED,
        UNRESOLVED,
        AMBIGUOUS
    }

    public enum HarnessPhase {
        EVIDENCE,
        REPORT
    }

    public enum HarnessOutcome {
        PASS,
        RECOVER,
        DEGRADE,
        BLOCK
    }

    public enum ViolationCode {
        TARGET_UNRESOLVED,
        TARGET_AMBIGUOUS,
        TARGET_MISMATCH,
        FUNDAMENTALS_MISSING,
        MARKET_MISSING,
        NEWS_MISSING,
        RAG_MISSING,
        PROVENANCE_MISSING,
        UNAPPROVED_CAPABILITY,
        REPORT_PARSE_INVALID,
        REPORT_SCHEMA_INVALID,
        REPORT_TARGET_MISMATCH,
        REPORT_EVIDENCE_REFERENCE_MISSING,
        REPORT_EVIDENCE_REFERENCE_UNKNOWN,
        REPORT_EVIDENCE_REFERENCE_UNUSABLE
    }

    public enum RecoveryAction {
        RETRY_FUNDAMENTALS,
        RETRY_MARKET,
        RETRY_NEWS,
        USE_APPROVED_FALLBACK,
        RESYNTHESIZE_REPORT,
        RETURN_NOT_RATED,
        RETURN_SAFE_REFUSAL
    }

    public enum ParseStatus {
        VALID,
        INVALID_JSON,
        INVALID_SCHEMA,
        EMPTY_OUTPUT,
        MODEL_FAILURE
    }

    public record TargetIdentity(
            String canonicalKey,
            String displaySymbol,
            TargetResolutionStatus status
    ) {
        public TargetIdentity {
            canonicalKey = normalizeTarget(canonicalKey);
            displaySymbol = displaySymbol == null ? "" : displaySymbol.strip();
            status = status == null ? TargetResolutionStatus.UNRESOLVED : status;
        }

        public static TargetIdentity resolved(String ticker) {
            String normalized = normalizeTarget(ticker);
            if (normalized.isBlank()) {
                return unresolved();
            }
            return new TargetIdentity(normalized, ticker == null ? "" : ticker.strip(),
                    TargetResolutionStatus.RESOLVED);
        }

        public static TargetIdentity unresolved() {
            return new TargetIdentity("", "", TargetResolutionStatus.UNRESOLVED);
        }

        @JsonIgnore
        public boolean isResolved() {
            return status == TargetResolutionStatus.RESOLVED && !canonicalKey.isBlank();
        }
    }

    public record EvidenceEnvelope(
            String evidenceId,
            EvidenceDimension dimension,
            String capabilityId,
            String targetKey,
            EvidenceStatus status,
            String sourceRef,
            String provider,
            Instant observedAt,
            Instant asOf,
            String payloadHash,
            boolean approvedReadOnly
    ) {
        public EvidenceEnvelope {
            evidenceId = safe(evidenceId);
            dimension = dimension == null ? EvidenceDimension.NEWS : dimension;
            capabilityId = safe(capabilityId);
            targetKey = normalizeTarget(targetKey);
            status = status == null ? EvidenceStatus.NOT_COLLECTED : status;
            sourceRef = safe(sourceRef);
            provider = safe(provider);
            payloadHash = safe(payloadHash);
        }

        public boolean hasUsableData() {
            return status == EvidenceStatus.AVAILABLE;
        }

        public boolean hasProvenance() {
            return hasUsableData()
                    && !evidenceId.isBlank()
                    && !sourceRef.isBlank()
                    && !provider.isBlank()
                    && observedAt != null
                    && !payloadHash.isBlank();
        }
    }

    public record RunContext(
            String workflow,
            Map<RecoveryAction, Integer> recoveryAttempts
    ) {
        public RunContext {
            workflow = safe(workflow);
            EnumMap<RecoveryAction, Integer> bounded = new EnumMap<>(RecoveryAction.class);
            if (recoveryAttempts != null) {
                recoveryAttempts.forEach((action, attempts) -> {
                    if (action != null) {
                        bounded.put(action, Math.max(0, attempts == null ? 0 : attempts));
                    }
                });
            }
            recoveryAttempts = Map.copyOf(bounded);
        }

        public static RunContext deepResearch() {
            return new RunContext("DEEP", Map.of());
        }

        public int attempts(RecoveryAction action) {
            return recoveryAttempts.getOrDefault(action, 0);
        }
    }

    public record HarnessViolation(
            ViolationCode code,
            EvidenceDimension dimension
    ) {
        public HarnessViolation {
            if (code == null) {
                throw new IllegalArgumentException("violation code is required");
            }
        }
    }

    public record HarnessDecision(
            HarnessOutcome outcome,
            List<HarnessViolation> violations,
            List<RecoveryAction> recoveryActions
    ) {
        public HarnessDecision {
            if (outcome == null) {
                throw new IllegalArgumentException("harness outcome is required");
            }
            violations = violations == null ? List.of() : List.copyOf(violations);
            recoveryActions = recoveryActions == null ? List.of() : List.copyOf(recoveryActions);
        }

        public boolean allowsRecommendation() {
            return outcome == HarnessOutcome.PASS;
        }
    }

    /**
     * Durable policy state written before any recovery side effect.
     *
     * <p>Only bounded decision metadata is persisted; raw prompts and tool payloads stay out of
     * checkpoints.</p>
     */
    public record HarnessSnapshot(
            String policyId,
            String policyVersion,
            HarnessPhase phase,
            HarnessOutcome outcome,
            List<ViolationCode> violations,
            Map<RecoveryAction, Integer> recoveryAttempts
    ) {
        public HarnessSnapshot {
            policyId = safe(policyId);
            policyVersion = safe(policyVersion);
            phase = phase == null ? HarnessPhase.EVIDENCE : phase;
            outcome = outcome == null ? HarnessOutcome.BLOCK : outcome;
            violations = violations == null ? List.of() : List.copyOf(violations);
            EnumMap<RecoveryAction, Integer> bounded = new EnumMap<>(RecoveryAction.class);
            if (recoveryAttempts != null) {
                recoveryAttempts.forEach((action, attempts) -> {
                    if (action != null) {
                        bounded.put(action, Math.max(0, attempts == null ? 0 : attempts));
                    }
                });
            }
            recoveryAttempts = Map.copyOf(bounded);
        }

        public static HarnessSnapshot from(
                String policyId,
                String policyVersion,
                HarnessPhase phase,
                HarnessDecision decision,
                Map<RecoveryAction, Integer> recoveryAttempts
        ) {
            HarnessDecision safeDecision = decision == null
                    ? new HarnessDecision(HarnessOutcome.BLOCK, List.of(), List.of())
                    : decision;
            return new HarnessSnapshot(
                    policyId,
                    policyVersion,
                    phase,
                    safeDecision.outcome(),
                    safeDecision.violations().stream().map(HarnessViolation::code).toList(),
                    recoveryAttempts
            );
        }
    }

    public record SynthesisResult(
            InvestmentReport report,
            ParseStatus parseStatus,
            List<String> validationIssues
    ) {
        public SynthesisResult {
            parseStatus = parseStatus == null ? ParseStatus.MODEL_FAILURE : parseStatus;
            validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizeTarget(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
