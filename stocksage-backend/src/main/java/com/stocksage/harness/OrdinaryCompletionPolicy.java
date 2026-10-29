package com.stocksage.harness;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.ordinary.DataRequirement;
import com.stocksage.agent.ordinary.PlanStep;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 按本题需求验收已纳入上下文的证据；缺口只允许使用计划中已有的备用取数步骤补一轮。 */
@Component
public class OrdinaryCompletionPolicy implements ResearchCompletionPolicy {
    @Override public String policyId() { return "ordinary-read-v1"; }
    @Override public int policyVersion() { return 4; }

    @Override
    public HarnessDecision afterEvidence(RunContext context, EvidenceLedger ledger) {
        boolean news = "NEWS".equals(context.workflow());
        if (!news && !ledger.target().isResolved()) return decision(HarnessOutcome.BLOCK, ViolationCode.TARGET_UNRESOLVED);
        if (ledger.hasUnapprovedCapability()) return decision(HarnessOutcome.BLOCK, ViolationCode.UNAPPROVED_CAPABILITY);
        if (ledger.target().isResolved() && !ledger.targetConsistent()) return decision(HarnessOutcome.BLOCK, ViolationCode.TARGET_MISMATCH);
        OrdinaryEvidenceContext facts = context.ordinaryEvidence();
        if (facts == null) return decision(HarnessOutcome.DEGRADE, ViolationCode.DATA_REQUIREMENT_UNMET);

        List<Map<String, Object>> available = availableRows(facts);
        List<Map<String, Object>> delivered = available.stream().filter(OrdinaryCompletionPolicy::delivered).toList();
        List<Map<String, Object>> complete = delivered.stream().filter(OrdinaryCompletionPolicy::current).toList();
        List<HarnessViolation> violations = new ArrayList<>();
        if (!facts.plan().requirements().isEmpty()) {
            for (DataRequirement requirement : facts.plan().requirements().stream().sorted().toList()) {
                if (!requirement.isSatisfied(available, facts.filingTextRetrieved())) {
                    add(violations, ViolationCode.DATA_REQUIREMENT_UNMET, null);
                } else if (!requirement.isSatisfied(delivered, facts.filingTextRetrieved())) {
                    add(violations, ViolationCode.EVIDENCE_CONTEXT_INCOMPLETE, null);
                } else if (!requirement.isSatisfied(complete, facts.filingTextRetrieved())) {
                    add(violations, ViolationCode.EVIDENCE_TIME_REQUIREMENT_UNMET, null);
                }
            }
        } else {
            // 没有明确的数据类别时，仍须覆盖本轮计划实际取证的各维度；一次失败可由同维度成功证据替代。
            List<EvidenceDimension> dimensions = dimensions(facts);
            if (dimensions.isEmpty()) add(violations, ViolationCode.PROVENANCE_MISSING, null);
            for (EvidenceDimension dimension : dimensions) {
                if (!hasDimension(available, dimension)) add(violations, ViolationCode.PROVENANCE_MISSING, dimension);
                else if (!hasDimension(delivered, dimension)) add(violations, ViolationCode.EVIDENCE_CONTEXT_INCOMPLETE, dimension);
                else if (!hasDimension(complete, dimension)) add(violations, ViolationCode.EVIDENCE_TIME_REQUIREMENT_UNMET, dimension);
            }
        }
        if (violations.isEmpty()) return new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        boolean recoverable = !fallbackSteps(context).isEmpty();
        return new HarnessDecision(recoverable ? HarnessOutcome.RECOVER : HarnessOutcome.DEGRADE, violations,
                recoverable ? List.of(RecoveryAction.USE_APPROVED_FALLBACK) : List.of());
    }

    /** Trace 和多标的比较资格与完成策略使用同一份需求判断；时效缺口单独降级，不冒充数据缺失。 */
    public List<DataRequirement> unmetRequirements(OrdinaryEvidenceContext facts) {
        List<Map<String, Object>> delivered = availableRows(facts).stream().filter(OrdinaryCompletionPolicy::delivered).toList();
        return facts.plan().requirements().stream()
                .filter(requirement -> !requirement.isSatisfied(delivered, facts.filingTextRetrieved())).sorted().toList();
    }

    /** 返回已有计划中确实能补当前缺口的步骤；不增加工具，不重试已执行步骤。 */
    public List<PlanStep> fallbackSteps(RunContext context) {
        OrdinaryEvidenceContext facts = context.ordinaryEvidence();
        if (facts == null || context.attempts(RecoveryAction.USE_APPROVED_FALLBACK) >= 1) return List.of();
        List<Map<String, Object>> complete = availableRows(facts).stream()
                .filter(OrdinaryCompletionPolicy::delivered).filter(OrdinaryCompletionPolicy::current).toList();
        if (!facts.plan().requirements().isEmpty()) {
            List<DataRequirement> missing = facts.plan().requirements().stream()
                    .filter(requirement -> !requirement.isSatisfied(complete, facts.filingTextRetrieved())).toList();
            return facts.plan().fallbackSteps().stream()
                    .filter(step -> missing.stream().anyMatch(requirement -> requirement.coveredBy(List.of(step.action())))).toList();
        }
        List<EvidenceDimension> missing = dimensions(facts).stream()
                .filter(dimension -> !hasDimension(complete, dimension)).toList();
        return facts.plan().fallbackSteps().stream().filter(step -> missing.contains(dimensionOf(step.action()))).toList();
    }

    private static List<Map<String, Object>> availableRows(OrdinaryEvidenceContext facts) {
        return facts.observations().stream().filter(row -> {
            boolean checkedNews = "NEWS".equals(row.get("dimension")) && "NO_RESULTS".equals(row.get("status"))
                    && !String.valueOf(row.getOrDefault("provider", "")).isBlank()
                    && !String.valueOf(row.getOrDefault("observedAt", "")).isBlank();
            Object gap = row.get("gap");
            return (Boolean.TRUE.equals(row.get("citable")) || checkedNews)
                    && ("".equals(gap) || gap != null && gap.equals(row.get("freshnessReason")));
        }).toList();
    }

    private static boolean delivered(Map<String, Object> row) {
        // 压缩可能只删掉可选内容；实际未交付由 included 和已有 gap 表示。
        return Boolean.TRUE.equals(row.get("included"));
    }

    private static boolean current(Map<String, Object> row) {
        return "NO_RESULTS".equals(row.get("status")) || "".equals(row.get("gap"))
                && !"UNKNOWN".equals(row.get("freshnessStatus")) && !"STALE".equals(row.get("freshnessStatus"));
    }

    private static List<EvidenceDimension> dimensions(OrdinaryEvidenceContext facts) {
        return facts.observations().stream().map(row -> EvidenceDimension.valueOf(String.valueOf(row.get("dimension"))))
                .distinct().toList();
    }

    private static boolean hasDimension(List<Map<String, Object>> rows, EvidenceDimension dimension) {
        return rows.stream().anyMatch(row -> dimension.name().equals(row.get("dimension")));
    }

    private static EvidenceDimension dimensionOf(PlanAction action) {
        return switch (action) {
            case GET_FINANCIAL_REPORTS, GET_STRUCTURED_FINANCIALS, SEARCH_COMPANY_REPORTS -> EvidenceDimension.FUNDAMENTALS;
            case SEARCH_NEWS, WEB_SEARCH -> EvidenceDimension.NEWS;
            default -> EvidenceDimension.MARKET;
        };
    }

    private static void add(List<HarnessViolation> violations, ViolationCode code, EvidenceDimension dimension) {
        HarnessViolation violation = new HarnessViolation(code, dimension);
        if (!violations.contains(violation)) violations.add(violation);
    }

    private static HarnessDecision decision(HarnessOutcome outcome, ViolationCode code) {
        return new HarnessDecision(outcome, List.of(new HarnessViolation(code, null)), List.of());
    }
}
