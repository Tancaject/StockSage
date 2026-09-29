package com.stocksage.model.entity;

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
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户某个标的的一份不可重复投资报告版本。
 *
 * <p>服务层按用户、ticker、数据快照哈希和上下文哈希去重；相同输入复用已有版本，
 * 新输入才递增 {@link #reportVersion}。实体同时保存当前人工审核状态，完整审核流水位于
 * {@link InvestmentReportReview}。</p>
 */
@Data
@Entity
@Table(
        name = "investment_report_versions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_report_snapshot_context",
                        columnNames = {"user_id", "ticker", "data_snapshot_hash", "context_hash"}
                )
        },
        indexes = {
                @Index(name = "idx_report_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_report_user_ticker_version", columnList = "user_id, ticker, report_version"),
                @Index(name = "idx_report_hashes", columnList = "data_snapshot_hash, context_hash")
        }
)
public class InvestmentReportVersion {

    /** 数据库自增报告版本主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 报告所属用户，也是报告查询、审核和缓存复用的租户边界。 */
    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    /** 产生本报告的会话主键；非会话或历史数据可为 null。 */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 首次创建报告的研究运行；复用和人工审核不得改写，历史来源未知时为空。 */
    @Column(name = "producer_run_id", updatable = false)
    private Long producerRunId;

    @Column(name = "producer_attempt", updatable = false)
    private Integer producerAttempt;

    /** 创建时采用的证据输入；是否模型生成由运行结果类型及调用记录解释。 */
    @Column(name = "producer_evidence_snapshot_id", length = 36, updatable = false)
    private String producerEvidenceSnapshotId;

    /** 已归一化的研究标的代码。 */
    @Column(length = 32, nullable = false)
    private String ticker;

    /** 触发报告生成的原始用户问题。 */
    @Column(name = "user_query", columnDefinition = "TEXT")
    private String userQuery;

    /** 报告最终建议摘要，便于列表查询无需反序列化完整 JSON。 */
    @Column(length = 32)
    private String recommendation;

    /** 同一用户、同一 ticker 下从 1 递增的业务版本号。 */
    @Column(name = "report_version", nullable = false)
    private Integer reportVersion;

    /** 结构化证据快照的 SHA-256 哈希，用于识别底层数据变化。 */
    @Column(name = "data_snapshot_hash", length = 64, nullable = false)
    private String dataSnapshotHash;

    /** 用户问题与数据快照的联合哈希，用于限制不同问题之间的缓存复用。 */
    @Column(name = "context_hash", length = 64, nullable = false)
    private String contextHash;

    /** 生成时选择的模型能力层级；旧报告可能为空。 */
    @Column(name = "model_tier", length = 16)
    private String modelTier;

    /** 实际生成结构化报告的模型名；旧报告可能为空。 */
    @Column(name = "model_name", length = 64)
    private String modelName;

    /** 完整 {@code InvestmentReport} 的 JSON，持久化后用于详情和缓存复用。 */
    @Column(name = "report_json", columnDefinition = "JSON", nullable = false)
    private String reportJson;

    /** 报告内容生成时间；调用方未提供时回退到 {@link #createdAt}。 */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    /** 报告版本首次写入数据库的时间。 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 当前人工审核状态；新报告默认为 DRAFT。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 32, nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.DRAFT;

    /** 最近一次执行审核的用户标识；尚未审核时为 null。 */
    @Column(name = "reviewer_user_id", length = 32)
    private String reviewerUserId;

    /** 最近一次审核说明；未填写时为 null。 */
    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    /** 最近一次审核状态变更时间；尚未审核时为 null。 */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 报告或审核元数据最近更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 乐观锁版本，防止两个审核请求互相覆盖。 */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    /**
     * 首次保存前补齐报告时间、审核状态和更新时间。
     *
     * <p>JPA 自动调用；调用方显式提供的字段会被保留。</p>
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (generatedAt == null) {
            generatedAt = createdAt;
        }
        if (reviewStatus == null) {
            reviewStatus = ReviewStatus.DRAFT;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    /** 每次更新报告审核元数据前刷新 {@link #updatedAt}；由 JPA 自动调用。 */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 投资报告的人工审核工作流状态。 */
    public enum ReviewStatus {
        /** 新生成、尚未提交人工审核。 */
        DRAFT,

        /** 已进入人工审核流程，等待最终决定。 */
        IN_REVIEW,

        /** 审核通过；与机器门禁 VERIFIED 是相互独立的判断。 */
        APPROVED,

        /** 人工明确否决，不得复用或捕获为研究记忆。 */
        REJECTED,

        /** 证据或分析不充分，需要重新研究，也不得复用或记忆。 */
        NEEDS_RESEARCH
    }
}
