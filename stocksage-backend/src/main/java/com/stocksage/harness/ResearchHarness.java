package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stage-boundary adapter around pure policies and side-effecting observation.
 *
 * <p>The policy decision is authoritative; observation remains fail-open and cannot change it.</p>
 */
@Slf4j
@Component
public class ResearchHarness {

    private final HarnessObserver observer;
    public ResearchHarness(HarnessObserver observer) {
        this.observer = observer;
    }

    public HarnessDecision observeEvidence(
            String traceId,
            ResearchCompletionPolicy policy,
            RunContext context,
            EvidenceLedger ledger
    ) {
        long startedAt = System.nanoTime();
        HarnessDecision decision;
        try {
            decision = policy.afterEvidence(context, ledger);
        } catch (RuntimeException error) {
            log.warn("Research harness evidence policy failed closed, policy={}, errorType={}",
                    safePolicyId(policy), error.getClass().getSimpleName());
            return new HarnessDecision(
                    HarnessOutcome.BLOCK,
                    List.of(),
                    List.of(RecoveryAction.RETURN_SAFE_REFUSAL)
            );
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        try {
            observer.evidenceDecision(
                    traceId,
                    policy,
                    decision,
                    ledger,
                    durationMs
            );
        } catch (RuntimeException error) {
            log.warn("Research harness observation failed open, policy={}, errorType={}",
                    safePolicyId(policy), error.getClass().getSimpleName());
        }
        return decision;
    }

    public HarnessDecision evaluateReport(
            String traceId,
            ResearchCompletionPolicy policy,
            RunContext context,
            EvidenceLedger ledger,
            SynthesisResult synthesis
    ) {
        long startedAt = System.nanoTime();
        HarnessDecision decision;
        try {
            decision = policy.afterReport(context, ledger, synthesis);
        } catch (RuntimeException error) {
            log.warn("Research harness report policy failed closed, policy={}, errorType={}",
                    safePolicyId(policy), error.getClass().getSimpleName());
            return new HarnessDecision(
                    HarnessOutcome.BLOCK,
                    List.of(),
                    List.of(RecoveryAction.RETURN_SAFE_REFUSAL)
            );
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        try {
            observer.reportDecision(traceId, policy, decision, ledger, durationMs);
        } catch (RuntimeException error) {
            log.warn("Research harness report observation failed open, policy={}, errorType={}",
                    safePolicyId(policy), error.getClass().getSimpleName());
        }
        return decision;
    }

    private String safePolicyId(ResearchCompletionPolicy policy) {
        try {
            return policy == null ? "unknown" : policy.policyId();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }
}
