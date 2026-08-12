package com.stocksage.harness;

import com.stocksage.agent.AgentStep;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.trace.TraceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 持久化有限 Harness 元数据并记录低基数指标。
 *
 * <p>{@link ResearchHarness} 在纯策略得出决策后调用本组件。它把 outcome、违规代码、恢复动作和证据计数
 * 写成 AgentStep，并向 Micrometer 记录次数/耗时；不保存原始证据或 prompt。观测失败由 Harness
 * 失败开放处理，不能反向改变已经得出的业务决策。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessObserver {

    /** 将 Harness 决策追加到同一业务 Trace。 */
    private final TraceService traceService;
    /** 记录按策略、阶段和结果聚合的指标。 */
    private final MeterRegistry meterRegistry;

    /** 记录证据阶段决策；空策略或空决策时跳过。 */
    public void evidenceDecision(
            String traceId,
            ResearchCompletionPolicy policy,
            HarnessDecision decision,
            EvidenceLedger ledger,
            long durationMs
    ) {
        if (policy == null || decision == null) {
            return;
        }
        recordMetrics(policy, decision, durationMs, HarnessPhase.EVIDENCE);
        recordTrace(traceId, policy, decision, ledger, durationMs, HarnessPhase.EVIDENCE);
    }

    /** 记录报告阶段决策；空策略或空决策时跳过。 */
    public void reportDecision(
            String traceId,
            ResearchCompletionPolicy policy,
            HarnessDecision decision,
            EvidenceLedger ledger,
            long durationMs
    ) {
        if (policy == null || decision == null) {
            return;
        }
        recordMetrics(policy, decision, durationMs, HarnessPhase.REPORT);
        recordTrace(traceId, policy, decision, ledger, durationMs, HarnessPhase.REPORT);
    }

    private void recordMetrics(
            ResearchCompletionPolicy policy,
            HarnessDecision decision,
            long durationMs,
            HarnessPhase phase
    ) {
        String policyTag = policy.policyId();
        String phaseTag = phase.name().toLowerCase(Locale.ROOT);
        String outcomeTag = decision.outcome().name().toLowerCase(Locale.ROOT);
        Counter.builder("stocksage.harness.decisions")
                .description("Number of StockSage harness decisions")
                .tags("policy", policyTag, "phase", phaseTag, "outcome", outcomeTag)
                .register(meterRegistry)
                .increment();
        Timer.builder("stocksage.harness.evaluation.duration")
                .description("StockSage harness evaluation latency")
                .tags("policy", policyTag, "phase", phaseTag)
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));

        for (HarnessViolation violation : decision.violations()) {
            Counter.builder("stocksage.harness.violations")
                    .description("Number of StockSage harness violations")
                    .tags("policy", policyTag, "code",
                            violation.code().name().toLowerCase(Locale.ROOT))
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void recordTrace(
            String traceId,
            ResearchCompletionPolicy policy,
            HarnessDecision decision,
            EvidenceLedger ledger,
            long durationMs,
            HarnessPhase phase
    ) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            List<String> violationCodes = decision.violations().stream()
                    .map(violation -> violation.code().name())
                    .toList();
            List<String> recoveryActions = decision.recoveryActions().stream()
                    .map(Enum::name)
                    .toList();
            // 属性只含枚举、版本和计数，前端/Eval 可复用且不会泄露原始证据。
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("schemaVersion", 1);
            attributes.put("policyId", policy.policyId());
            attributes.put("policyVersion", policy.policyVersion());
            attributes.put("phase", phase.name());
            attributes.put("decision", decision.outcome().name());
            attributes.put("policyAllowsRecommendation", decision.allowsRecommendation());
            attributes.put("enforcementActive", true);
            attributes.put("violationCodes", violationCodes);
            attributes.put("recoveryActions", recoveryActions);
            attributes.put("evidenceCounts",
                    ledger == null ? Map.of() : ledger.boundedCounts());

            traceService.addStep(traceId, AgentStep.builder()
                    .thought("Research completion policy evaluation")
                    .action("harness:" + policy.policyId())
                    .actionInput("{\"phase\":\"" + phase.name() + "\"}")
                    .observation("policy=" + decision.outcome().name())
                    .durationMs(Math.max(0, durationMs))
                    .tokenCount(0)
                    .attributes(Map.copyOf(attributes))
                    .build());
        } catch (Exception error) {
            log.debug("Failed to persist harness trace step, traceId={}, policy={}",
                    traceId, policy.policyId(), error);
        }
    }
}
