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
 * MARKET 路由的确定性完成契约。
 *
 * <p>要求标的已解析、能力均获批准，并至少有一条来源完整且标的一致的行情证据。
 * 首次缺失允许重试一次 MARKET，之后降级为 NOT_RATED；标的或权限问题直接阻断。</p>
 */
@Component
public class MarketCompletionPolicy implements ResearchCompletionPolicy {

    /** Trace/checkpoint 使用的稳定策略 ID。 */
    public static final String POLICY_ID = "market-read-v1";

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public int policyVersion() {
        return 1;
    }

    /**
     * 检查行情路由证据并返回有限恢复决策。
     *
     * @param context MARKET 重试已用次数
     * @param evidence 本轮证据账本
     * @return PASS、一次 RETRY_MARKET、DEGRADE 或 BLOCK
     */
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
