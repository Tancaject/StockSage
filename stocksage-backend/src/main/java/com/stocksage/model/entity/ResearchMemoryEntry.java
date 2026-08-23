package com.stocksage.model.entity;

import com.stocksage.model.dto.AnalysisHorizon;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可跨会话检索的用户研究记忆真相记录。
 *
 * <p>只有通过当前证据门禁的持久化投资报告才会生成此实体。MySQL 保存来源、正文、
 * 撤销状态和索引状态，向量库只是可重建的检索副本；所有查询都必须按 {@link #userId} 隔离。</p>
 */
@Data
@Entity
@Table(
        name = "research_memory_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_research_memory_source",
                columnNames = {"user_id", "source_type", "source_id"}
        ),
        indexes = {
                @Index(name = "idx_research_memory_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_research_memory_user_ticker_status",
                        columnList = "user_id, ticker, vector_status"),
                @Index(name = "idx_research_memory_conflict",
                        columnList = "user_id, conflict_key, resolution_status"),
                @Index(name = "idx_research_memory_superseded_by",
                        columnList = "superseded_by_id")
        }
)
public class ResearchMemoryEntry {

    /** 数据库自增记忆主键，也是向量元数据回查真相行的标识。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 记忆所属用户，检索和撤销操作的租户边界。 */
    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    /** 已归一化的报告标的代码。 */
    @Column(length = 32, nullable = false)
    private String ticker;

    /** 该报告结论适用的分析期限；旧数据安全回退为 UNSPECIFIED。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_horizon", length = 16, nullable = false)
    private AnalysisHorizon analysisHorizon;

    /** 来源报告的五档投资建议；旧报告或无评级报告允许为 null。 */
    @Column(length = 32)
    private String recommendation;

    /** 用户内结构化冲突键；尚未完成旧数据回填时允许为 null。 */
    @Column(name = "conflict_key", length = 128)
    private String conflictKey;

    /** 当前业务冲突裁决状态，与 {@link #vectorStatus} 的索引生命周期分离。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", length = 16, nullable = false)
    private ResolutionStatus resolutionStatus;

    /** 取代当前记忆的更新记忆主键；非 SUPERSEDED 状态通常为 null。 */
    @Column(name = "superseded_by_id")
    private Long supersededById;

    /** 当前记忆被取代的业务时间。 */
    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    /** 冲突裁决的稳定原因码，不保存自由文本模型判断。 */
    @Column(name = "resolution_reason", length = 64)
    private String resolutionReason;

    /** 来源类型；当前报告捕获路径使用 INVESTMENT_REPORT_VERSION。 */
    @Column(name = "source_type", length = 32, nullable = false)
    private String sourceType;

    /** 来源在其类型内的稳定标识；报告来源使用报告版本主键字符串。 */
    @Column(name = "source_id", length = 64, nullable = false)
    private String sourceId;

    /** 产生来源报告的会话主键；无关联会话时可为 null。 */
    @Column(name = "source_conversation_id")
    private Long sourceConversationId;

    /** 产生来源报告的执行链路 ID；当前无法关联时可为 null。 */
    @Column(name = "source_trace_id", length = 64)
    private String sourceTraceId;

    /** JSON 字符串数组形式的来源引用，供回溯和展示。 */
    @Column(name = "source_citations", columnDefinition = "JSON", nullable = false)
    private String sourceCitations;

    /** 记忆衰减参考时点；当前报告来源写 generatedAt，尚不是底层证据的严格 as-of。 */
    @Column(name = "data_cutoff_at")
    private LocalDateTime dataCutoffAt;

    /** 来源报告的数据快照哈希，标识结论依据的数据版本。 */
    @Column(name = "snapshot_hash", length = 64, nullable = false)
    private String snapshotHash;

    /** {@link #memoryText} 的 SHA-256 哈希，用于内容一致性和向量版本标识。 */
    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    /** 送入嵌入模型和后续提示词的证据化记忆正文。 */
    @Column(name = "memory_text", columnDefinition = "TEXT", nullable = false)
    private String memoryText;

    /** MySQL 真相行对应的向量索引生命周期状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "vector_status", length = 16, nullable = false)
    private VectorStatus vectorStatus;

    /** 最近一次向量索引失败的稳定错误码；成功或尚未失败时为 null。 */
    @Column(name = "vector_error_code", length = 64)
    private String vectorErrorCode;

    /** 逻辑撤销时间；非 null 时检索必须排除该记忆。 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 真相行创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 真相行或索引状态最近更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 首次保存前补齐时间与索引状态。
     *
     * <p>索引状态未设置时从 PENDING 开始；调用方显式提供的创建时间会被保留。</p>
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
        vectorStatus = vectorStatus == null ? VectorStatus.PENDING : vectorStatus;
        analysisHorizon = analysisHorizon == null
                ? AnalysisHorizon.UNSPECIFIED : analysisHorizon;
        resolutionStatus = resolutionStatus == null
                ? ResolutionStatus.CURRENT : resolutionStatus;
    }

    /** 每次更新真相行前刷新 {@link #updatedAt}；由 JPA 自动调用。 */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 判断该真相行是否仍允许参与检索。
     *
     * <p>该判断同时检查撤销时间和向量状态；调用方还需确认状态为 INDEXED 才能使用向量结果。</p>
     *
     * @return 未撤销且状态不是 REVOKED 时返回 true
     */
    public boolean active() {
        return revokedAt == null && vectorStatus != VectorStatus.REVOKED;
    }

    /**
     * 判断记忆是否同时通过撤销、向量生命周期和业务冲突状态门禁。
     *
     * @return 仅未撤销、向量未撤销且业务状态为 CURRENT 时返回 true
     */
    public boolean resolutionCurrent() {
        return active() && resolutionStatus == ResolutionStatus.CURRENT;
    }

    /** 记忆条目的业务冲突裁决状态。 */
    public enum ResolutionStatus {
        /** 当前冲突组唯一可注入的赢家。 */
        CURRENT,

        /** 已由更新或更高优先级的记忆取代。 */
        SUPERSEDED,

        /** 同优先级相反结论尚未消解，不得注入。 */
        CONFLICTED
    }

    /** 向量副本相对于 MySQL 真相行的索引状态。 */
    public enum VectorStatus {
        /** 等待首次或补偿索引。 */
        PENDING,

        /** 已成功写入向量库，可参与检索。 */
        INDEXED,

        /** 最近一次索引失败，可由补偿任务重试。 */
        FAILED,

        /** 真相行已撤销，向量副本不得再参与检索。 */
        REVOKED
    }
}
