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

/**
 * 纯检索/RAG 路由的确定性来源契约。
 *
 * <p>至少需要一条可用且来源完整的 RAG 证据。首次缺失时请求受批准的 fallback 检索，
 * fallback 后仍不足则返回 NOT_RATED；未经批准的能力始终直接阻断。</p>
 */
@Component
public class RagCompletionPolicy implements ResearchCompletionPolicy {

    /** Trace/checkpoint 使用的稳定策略 ID。 */
    public static final String POLICY_ID = "rag-retrieval-v1";

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public int policyVersion() {
        return 1;
    }

    /**
     * 验收 RAG 证据及其来源。
     *
     * @param context fallback 已用次数
     * @param evidence 本轮证据账本
     * @return PASS、一次 USE_APPROVED_FALLBACK、DEGRADE 或 BLOCK
     */
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
