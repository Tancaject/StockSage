package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.TargetIdentity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Metadata-only ledger for all evidence collected during one research run.
 */
public record EvidenceLedger(
        TargetIdentity target,
        List<EvidenceEnvelope> evidence
) {
    public EvidenceLedger {
        target = target == null ? TargetIdentity.unresolved() : target;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static EvidenceLedger empty() {
        return new EvidenceLedger(TargetIdentity.unresolved(), List.of());
    }

    public boolean hasUsable(EvidenceDimension dimension) {
        return evidence.stream()
                .anyMatch(item -> item.dimension() == dimension && item.hasUsableData());
    }

    public boolean hasMissingProvenance(EvidenceDimension dimension) {
        return evidence.stream()
                .filter(item -> item.dimension() == dimension)
                .filter(EvidenceEnvelope::hasUsableData)
                .anyMatch(item -> !item.hasProvenance());
    }

    public boolean targetConsistent() {
        if (!target.isResolved()) {
            return false;
        }
        return evidence.stream()
                .map(EvidenceEnvelope::targetKey)
                .filter(key -> key != null && !key.isBlank())
                .allMatch(target.canonicalKey()::equals);
    }

    public boolean hasUnapprovedCapability() {
        return evidence.stream().anyMatch(item -> !item.approvedReadOnly());
    }

    public Set<String> evidenceIds() {
        return evidence.stream()
                .map(EvidenceEnvelope::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Evidence IDs that may support a synthesized conclusion.
     *
     * <p>Membership alone is insufficient: failed/empty calls, missing provenance,
     * unapproved capabilities, and cross-target evidence must not become report support.</p>
     */
    public Set<String> usableEvidenceIds() {
        if (!target.isResolved()) {
            return Set.of();
        }
        return evidence.stream()
                .filter(EvidenceEnvelope::hasUsableData)
                .filter(EvidenceEnvelope::hasProvenance)
                .filter(EvidenceEnvelope::approvedReadOnly)
                .filter(item -> target.canonicalKey().equals(item.targetKey()))
                .map(EvidenceEnvelope::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns bounded dimension/status counts suitable for trace attributes.
     */
    public Map<String, Long> boundedCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (EvidenceEnvelope item : evidence) {
            String key = item.dimension().name() + "_" + item.status().name();
            counts.merge(key, 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }
}
