package com.stocksage.harness;

import com.stocksage.evidence.EvidenceLedger;

import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;

/**
 * 在研究阶段边界执行的纯完成契约。
 *
 * <p>{@link ResearchHarness} 负责计时、异常边界和观测，具体策略只读取上下文与账本并返回决策。
 * 策略不得调用工具、修改 checkpoint 或发送事件；这样在线运行、恢复和离线 Golden Set
 * 可以复用同一规则。</p>
 */
public interface ResearchCompletionPolicy {

    /** @return 持久化和指标使用的稳定策略 ID */
    String policyId();

    /** @return 当前规则版本 */
    int policyVersion();

    /**
     * 证据收集结束后的纯策略判断。
     *
     * @param context 工作流和已用恢复预算
     * @param evidence 结构化证据账本
     * @return 执行流水线必须遵守的决策
     */
    HarnessDecision afterEvidence(RunContext context, EvidenceLedger evidence);

    /**
     * 报告综合后的纯策略判断；不支持报告门槛的策略保持默认拒绝实现。
     *
     * @param context 工作流和已用恢复预算
     * @param evidence 与报告对应的证据账本
     * @param synthesis 结构化报告及解析状态
     * @return 执行流水线必须遵守的决策
     */
    default HarnessDecision afterReport(
            RunContext context,
            EvidenceLedger evidence,
            SynthesisResult synthesis
    ) {
        throw new UnsupportedOperationException("report completion policy is not implemented yet");
    }
}
