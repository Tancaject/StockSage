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

/** Deterministic completion contract for market-data routes. */
@Component
public class MarketCompletionPolicy implements ResearchCompletionPolicy {

    public static final String POLICY_ID = "market-read-v1";

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
        if (!ledger.target().isResolved()) {
            return decision(HarnessOutcome.BLOCK, ViolationCode.TARGET_UNRESOLVED, null,
                    RecoveryAction.RETURN_SAFE_REFUSAL);
        }
        if (ledger.hasUnapprovedCapability()) {
            return decision(HarnessOutcome.BLOCK, ViolationCode.UNAPPROVED_CAPABILITY, null,
                    RecoveryAction.RETURN_SAFE_REFUSAL);
        }
        if (ledger.hasUsable(EvidenceDimension.MARKET)
                && !ledger.hasMissingProvenance(EvidenceDimension.MARKET)
                && ledger.targetConsistent()) {
            return new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        }
        int attempts = context == null ? 0 : context.attempts(RecoveryAction.RETRY_MARKET);
        return decision(
                attempts < 1 ? HarnessOutcome.RECOVER : HarnessOutcome.DEGRADE,
                ledger.hasMissingProvenance(EvidenceDimension.MARKET)
                        ? ViolationCode.PROVENANCE_MISSING : ViolationCode.MARKET_MISSING,
                EvidenceDimension.MARKET,
                attempts < 1 ? RecoveryAction.RETRY_MARKET : RecoveryAction.RETURN_NOT_RATED
        );
    }

    private HarnessDecision decision(
            HarnessOutcome outcome,
            ViolationCode code,
            EvidenceDimension dimension,
            RecoveryAction action
    ) {
        return new HarnessDecision(
                outcome,
                List.of(new HarnessViolation(code, dimension)),
                List.of(action)
        );
    }
}
