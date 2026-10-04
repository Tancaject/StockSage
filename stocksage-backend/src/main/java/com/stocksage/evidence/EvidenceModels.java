package com.stocksage.evidence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Locale;

/** 证据事实契约；普通回答与研究策略共同消费，独立于 Harness 的恢复和完成决策。 */
public final class EvidenceModels {
    private EvidenceModels() { }

    /** 证据在研究结论中承担的业务维度。 */
    public enum EvidenceDimension {
        FUNDAMENTALS,
        MARKET,
        NEWS,
        RAG
    }

    /** 一次证据收集的稳定终态；只有 AVAILABLE 被视为有可用数据。 */
    public enum EvidenceStatus {
        AVAILABLE,
        EMPTY,
        NO_RESULTS,
        FAILED,
        TIMED_OUT,
        NOT_COLLECTED
    }

    /** 标的解析结果；模糊和未解析都不能生成确定评级。 */
    public enum TargetResolutionStatus {
        RESOLVED,
        UNRESOLVED,
        AMBIGUOUS
    }

    /**
     * 规范化后的研究标的身份。
     *
     * @param canonicalKey 用于严格比较的标准大写键
     * @param displaySymbol 面向界面的原始/展示代码
     * @param status 解析状态
     */
    public record TargetIdentity(
            String canonicalKey,
            String displaySymbol,
            TargetResolutionStatus status
    ) {
        public TargetIdentity {
            canonicalKey = normalizeTarget(canonicalKey);
            displaySymbol = displaySymbol == null ? "" : displaySymbol.strip();
            status = status == null ? TargetResolutionStatus.UNRESOLVED : status;
        }

        /** 从 ticker 构造已解析身份；空值安全降级为 unresolved。 */
        public static TargetIdentity resolved(String ticker) {
            String normalized = normalizeTarget(ticker);
            if (normalized.isBlank()) {
                return unresolved();
            }
            return new TargetIdentity(normalized, ticker == null ? "" : ticker.strip(),
                    TargetResolutionStatus.RESOLVED);
        }

        /** @return 标准的未解析身份 */
        public static TargetIdentity unresolved() {
            return new TargetIdentity("", "", TargetResolutionStatus.UNRESOLVED);
        }

        /** @return 状态为 RESOLVED 且标准键非空时为 {@code true} */
        @JsonIgnore
        public boolean isResolved() {
            return status == TargetResolutionStatus.RESOLVED && !canonicalKey.isBlank();
        }
    }

    /**
     * 一次工具/RAG 证据的审计元数据。
     *
     * @param evidenceId 单轮研究内稳定证据 ID
     * @param dimension 业务证据维度
     * @param capabilityId 产生证据的能力 ID
     * @param targetKey 证据所属标准标的
     * @param status 收集终态
     * @param sourceRef 可追溯来源引用
     * @param provider 实际数据提供方
     * @param observedAt 系统观察到结果的时间
     * @param asOf 数据自身的业务时点，可为空
     * @param payloadHash 原始结果摘要哈希，不保存正文
     * @param approvedReadOnly 是否来自批准的只读能力
     * @param timing 经过领域契约校验的时间事实；旧记录或未迁移能力保持为空
     */
    public record EvidenceEnvelope(
            String evidenceId,
            EvidenceDimension dimension,
            String capabilityId,
            String targetKey,
            EvidenceStatus status,
            String sourceRef,
            String provider,
            Instant observedAt,
            Instant asOf,
            String payloadHash,
            boolean approvedReadOnly,
            EvidenceTiming timing
    ) {
        public EvidenceEnvelope {
            evidenceId = safe(evidenceId);
            dimension = dimension == null ? EvidenceDimension.NEWS : dimension;
            capabilityId = safe(capabilityId);
            targetKey = normalizeTarget(targetKey);
            status = status == null ? EvidenceStatus.NOT_COLLECTED : status;
            sourceRef = safe(sourceRef);
            provider = safe(provider);
            payloadHash = safe(payloadHash);
        }

        /** 旧调用和未迁移来源不能仅凭 asOf 猜测时间精度或延迟口径。 */
        public EvidenceEnvelope(String evidenceId, EvidenceDimension dimension, String capabilityId, String targetKey,
                                EvidenceStatus status, String sourceRef, String provider, Instant observedAt,
                                Instant asOf, String payloadHash, boolean approvedReadOnly) {
            this(evidenceId, dimension, capabilityId, targetKey, status, sourceRef, provider, observedAt,
                    asOf, payloadHash, approvedReadOnly, null);
        }

        /** @return 仅当状态为 AVAILABLE 时为 {@code true} */
        public boolean hasUsableData() {
            return status == EvidenceStatus.AVAILABLE;
        }

        /** @return 可用数据是否同时具有完整最小来源字段 */
        public boolean hasProvenance() {
            return hasUsableData()
                    && !evidenceId.isBlank()
                    && !sourceRef.isBlank()
                    && !provider.isBlank()
                    && observedAt != null
                    && !payloadHash.isBlank();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizeTarget(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
