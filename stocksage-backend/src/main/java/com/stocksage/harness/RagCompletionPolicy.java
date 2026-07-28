package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.ViolationCode;
import org.springframework.stereotype.Component;

import java.util.List;

/** Deterministic provenance contract for retrieval-only routes. */
@Component
public class RagCompletionPolicy implements ResearchCompletionPolicy {

    public static final String POLICY_ID = "rag-retrieval-v1";

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public int policyVersion() {
        return 1;
    }

    @Override
    public HarnessDecision afterEvidence(RunContext context, EvidenceLedger evidence) {
        EvidenceLedger ledger = evidence == null ? EvidenceLedger.empty() : evidence;
        if (ledger.hasUnapprovedCapability()) {
            return decision(HarnessOutcome.BLOCK, ViolationCode.UNAPPROVED_CAPABILITY,
                    RecoveryAction.RETURN_SAFE_REFUSAL);
        }
        if (ledger.hasUsable(EvidenceDimension.RAG)
                && !ledger.hasMissingProvenance(EvidenceDimension.RAG)) {
            return new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        }
        int attempts = context == null ? 0 : context.attempts(RecoveryAction.USE_APPROVED_FALLBACK);
        return decision(
                attempts < 1 ? HarnessOutcome.RECOVER : HarnessOutcome.DEGRADE,
                ledger.hasMissingProvenance(EvidenceDimension.RAG)
                        ? ViolationCode.PROVENANCE_MISSING : ViolationCode.RAG_MISSING,
                attempts < 1 ? RecoveryAction.USE_APPROVED_FALLBACK : RecoveryAction.RETURN_NOT_RATED
        );
    }

    private HarnessDecision decision(
            HarnessOutcome outcome,
            ViolationCode code,
            RecoveryAction action
    ) {
        return new HarnessDecision(
                outcome,
                List.of(new HarnessViolation(code, EvidenceDimension.RAG)),
                List.of(action)
        );
    }
}
