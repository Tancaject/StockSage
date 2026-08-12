package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.ViolationCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NEWS 路由的确定性完成契约。
 *
 * <p>提供方成功执行但明确返回 NO_RESULTS 是合法结果，不会被误判为系统失败；传输/提供方失败可恢复一次，
 * 再失败则安全降级。任何未批准能力直接阻断。</p>
 */
@Component
public class NewsCompletionPolicy implements ResearchCompletionPolicy {

    /** Trace/checkpoint 使用的稳定策略 ID。 */
    public static final String POLICY_ID = "news-read-v1";

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public int policyVersion() {
        return 1;
    }

    /**
     * 验收新闻证据，区分“没有新闻”和“搜索失败”。
     *
     * @param context NEWS 重试已用次数
     * @param evidence 本轮证据账本
     * @return PASS、一次 RETRY_NEWS、DEGRADE 或 BLOCK
     */
    @Override
    public HarnessDecision afterEvidence(RunContext context, EvidenceLedger evidence) {
        EvidenceLedger ledger = evidence == null ? EvidenceLedger.empty() : evidence;
        if (ledger.hasUnapprovedCapability()) {
            return decision(HarnessOutcome.BLOCK, ViolationCode.UNAPPROVED_CAPABILITY,
                    RecoveryAction.RETURN_SAFE_REFUSAL);
        }
        // NO_RESULTS 只有在调用获批、提供方和观察时间完整时才算可信的显式空结果。
        boolean validNoResults = ledger.evidence().stream()
                .filter(item -> item.dimension() == EvidenceDimension.NEWS)
                .anyMatch(item -> item.status() == EvidenceStatus.NO_RESULTS
                        && item.approvedReadOnly()
                        && item.observedAt() != null
                        && !item.provider().isBlank());
        if (validNoResults || (ledger.hasUsable(EvidenceDimension.NEWS)
                && !ledger.hasMissingProvenance(EvidenceDimension.NEWS))) {
            return new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        }
        int attempts = context == null ? 0 : context.attempts(RecoveryAction.RETRY_NEWS);
        return decision(
                attempts < 1 ? HarnessOutcome.RECOVER : HarnessOutcome.DEGRADE,
                ViolationCode.NEWS_MISSING,
                attempts < 1 ? RecoveryAction.RETRY_NEWS : RecoveryAction.RETURN_NOT_RATED
        );
    }

    private HarnessDecision decision(
            HarnessOutcome outcome,
            ViolationCode code,
            RecoveryAction action
    ) {
        return new HarnessDecision(
                outcome,
                List.of(new HarnessViolation(code, EvidenceDimension.NEWS)),
                List.of(action)
        );
    }
}
