package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;

/**
 * Pure completion contract evaluated at research phase boundaries.
 *
 * <p>Policies must not call tools, mutate checkpoints, or emit events.</p>
 */
public interface ResearchCompletionPolicy {

    String policyId();

    int policyVersion();

    HarnessDecision afterEvidence(RunContext context, EvidenceLedger evidence);

    default HarnessDecision afterReport(
            RunContext context,
            EvidenceLedger evidence,
            SynthesisResult synthesis
    ) {
        throw new UnsupportedOperationException("report completion policy is not implemented yet");
    }
}
