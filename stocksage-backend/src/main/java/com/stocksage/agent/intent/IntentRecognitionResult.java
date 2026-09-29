package com.stocksage.agent.intent;

import java.util.List;

/**
 * 意图融合结果及其有限诊断信息。
 *
 * <p>{@link IntentDecision} 是业务决定；原始 LLM 路由只用于 Eval/Trace 判断模型自身是否
 * 输出了合法五选一，不会参与动作执行。</p>
 */
public record IntentRecognitionResult(
        IntentDecision decision,
        String rawRoute,
        boolean rawRouteValid,
        String rationale,
        List<IntentSignal> signals,
        String degradationReason,
        long durationMs
) {
    public IntentRecognitionResult {
        rawRoute = IntentBounds.text(rawRoute, IntentBounds.MAX_RAW_ROUTE_CHARS);
        rationale = IntentBounds.text(rationale, IntentBounds.MAX_RATIONALE_CHARS);
        signals = signals == null ? List.of() : signals.stream().filter(java.util.Objects::nonNull).toList();
        degradationReason = IntentBounds.text(degradationReason, 64)
                .toUpperCase(java.util.Locale.ROOT)
                .replace(' ', '_');
        durationMs = Math.max(0L, durationMs);
    }

    /** @return 是否至少有一个真实识别信号，而不是空输入的默认决定 */
    public boolean hasUsableSignal() {
        return signals.stream().anyMatch(signal -> signal.source() != IntentSignalSource.FALLBACK);
    }

    /** @return 是否只有 LLM 一种来源参与，供兼容旧的 ROUTING_LLM 指标 */
    public boolean llmOnly() {
        return signals.size() == 1 && signals.get(0).source() == IntentSignalSource.LLM;
    }

    /** 与融合策略共用候选选择；每个来源取最高置信信号，同分保留先到的信号。 */
    public List<IntentSignal> bestSignals() {
        return List.copyOf(IntentFusionPolicy.bestBySource(signals).values());
    }
}
