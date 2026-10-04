package com.stocksage.harness;

import com.stocksage.evidence.EvidenceLedger;

import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 连接纯完成策略与有副作用观测的阶段边界适配器。
 *
 * <p>深度研究流水线通过本类调用策略，而不直接调用 Observer。策略异常失败关闭为 BLOCK；
 * Trace/指标观测异常失败开放，不能改变已得出的权威决策。该不对称边界保证“安全判断失败时拒绝，
 * 监控失败时业务仍按判断继续”。</p>
 */
@Slf4j
@Component
public class ResearchHarness {

    /** 把有限决策写入 Trace 和指标；其失败不影响业务决策。 */
    private final HarnessObserver observer;
    public ResearchHarness(HarnessObserver observer) {
        this.observer = observer;
    }

    /**
     * 执行并观测证据阶段策略。
     *
     * @param traceId 当前链路 ID，可为空
     * @param policy 要执行的纯策略
     * @param context 工作流和恢复预算
     * @param ledger 证据账本
     * @return 权威 Harness 决策；策略异常时为安全 BLOCK
     */
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
            // 观测是旁路副作用；失败只能丢失可观测数据，不能推翻安全决策。
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

    /**
     * 执行并观测报告阶段策略。
     *
     * @param traceId 当前链路 ID，可为空
     * @param policy 要执行的纯策略
     * @param context 工作流和恢复预算
     * @param ledger 报告对应的证据账本
     * @param synthesis Research Manager 的结构化产物
     * @return 权威 Harness 决策；策略异常时为安全 BLOCK
     */
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
