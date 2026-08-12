package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.TargetIdentity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 单次研究运行的只含元数据证据账本。
 *
 * <p>上游工具预取/深度研究流水线把证据包装为 {@code EvidenceEnvelope}；完成策略与
 * {@code ResearchManager} 只据此判断可用性和绑定来源。账本不保存原始 payload，避免 checkpoint
 * 和 Trace 携带大文本或敏感内容。</p>
 *
 * @param target 本轮统一解析后的研究标的
 * @param evidence 按收集顺序保存的证据元数据
 */
public record EvidenceLedger(
        TargetIdentity target,
        List<EvidenceEnvelope> evidence
) {
    /** 对空值设安全默认值，并复制证据列表为不可变快照。 */
    public EvidenceLedger {
        target = target == null ? TargetIdentity.unresolved() : target;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** @return 标的未解析且没有证据的空账本 */
    public static EvidenceLedger empty() {
        return new EvidenceLedger(TargetIdentity.unresolved(), List.of());
    }

    /** @return 指定维度是否至少有一条 AVAILABLE 证据 */
    public boolean hasUsable(EvidenceDimension dimension) {
        return evidence.stream()
                .anyMatch(item -> item.dimension() == dimension && item.hasUsableData());
    }

    /** @return 指定维度是否出现“有数据但缺来源”的证据 */
    public boolean hasMissingProvenance(EvidenceDimension dimension) {
        return evidence.stream()
                .filter(item -> item.dimension() == dimension)
                .filter(EvidenceEnvelope::hasUsableData)
                .anyMatch(item -> !item.hasProvenance());
    }

    /**
     * 检查所有非空证据 targetKey 是否与统一标的一致。
     *
     * @return 标的已解析且没有跨标的证据时为 {@code true}
     */
    public boolean targetConsistent() {
        if (!target.isResolved()) {
            return false;
        }
        return evidence.stream()
                .map(EvidenceEnvelope::targetKey)
                .filter(key -> key != null && !key.isBlank())
                .allMatch(target.canonicalKey()::equals);
    }

    /** @return 是否包含未经批准的非只读能力结果 */
    public boolean hasUnapprovedCapability() {
        return evidence.stream().anyMatch(item -> !item.approvedReadOnly());
    }

    /** @return 账本中所有非空证据 ID，不代表这些证据可支撑结论 */
    public Set<String> evidenceIds() {
        return evidence.stream()
                .map(EvidenceEnvelope::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 返回允许支撑最终结论的证据 ID。
     *
     * <p>只“存在于账本”并不够：失败/空结果、缺失来源、未批准能力以及跨标的证据都会被排除。</p>
     */
    public Set<String> usableEvidenceIds() {
        if (!target.isResolved()) {
            return Set.of();
        }
        return evidence.stream()
                .filter(EvidenceEnvelope::hasUsableData)
                .filter(EvidenceEnvelope::hasProvenance)
                .filter(EvidenceEnvelope::approvedReadOnly)
                .filter(item -> target.canonicalKey().equals(item.targetKey()))
                .map(EvidenceEnvelope::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 按“维度_状态”汇总有限计数，适合写入 Trace 属性和指标。
     *
     * @return 不含原始证据内容的不可变计数表
     */
    public Map<String, Long> boundedCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (EvidenceEnvelope item : evidence) {
            String key = item.dimension().name() + "_" + item.status().name();
            counts.merge(key, 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }
}
