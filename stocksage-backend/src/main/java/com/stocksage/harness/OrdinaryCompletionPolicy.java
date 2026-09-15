package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.*;
import org.springframework.stereotype.Component;
import java.util.List;

/** 普通路线验收全部必需取证；不以一个成功工具掩盖其他证据缺口，也不另行重试。 */
@Component
public class OrdinaryCompletionPolicy implements ResearchCompletionPolicy {
    @Override public String policyId() { return "ordinary-read-v1"; }
    @Override public int policyVersion() { return 1; }

    @Override
    public HarnessDecision afterEvidence(RunContext context, EvidenceLedger ledger) {
        boolean news = "NEWS".equals(context.workflow());
        if (!news && !ledger.target().isResolved()) return decision(HarnessOutcome.BLOCK, ViolationCode.TARGET_UNRESOLVED);
        if (ledger.hasUnapprovedCapability()) return decision(HarnessOutcome.BLOCK, ViolationCode.UNAPPROVED_CAPABILITY);
        if (ledger.target().isResolved() && !ledger.targetConsistent()) return decision(HarnessOutcome.BLOCK, ViolationCode.TARGET_MISMATCH);
        boolean complete = !ledger.evidence().isEmpty() && ledger.evidence().stream().allMatch(item ->
                item.hasProvenance() || (news && item.status() == EvidenceStatus.NO_RESULTS
                        && !item.provider().isBlank() && item.observedAt() != null));
        return complete ? new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of())
                : decision(HarnessOutcome.DEGRADE, ViolationCode.PROVENANCE_MISSING);
    }

    private HarnessDecision decision(HarnessOutcome outcome, ViolationCode code) {
        return new HarnessDecision(outcome, List.of(new HarnessViolation(code, null)), List.of());
    }
}
