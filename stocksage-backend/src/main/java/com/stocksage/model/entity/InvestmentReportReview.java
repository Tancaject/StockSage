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
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 投资报告人工审核的一条追加式状态变更记录。
 *
 * <p>当前状态保存在 {@link InvestmentReportVersion}，本实体保存从旧状态到新状态的审计历史。
 * 除主键外各列均不可更新，确保审核记录写入后不会被覆盖。</p>
 */
@Data
@Entity
@Table(
        name = "investment_report_reviews",
        indexes = {
                @Index(name = "idx_report_review_version", columnList = "report_version_id"),
                @Index(name = "idx_report_review_user", columnList = "user_id")
        }
)
public class InvestmentReportReview {

    /** 数据库自增审核记录主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被审核的报告版本主键；记录写入后不可修改。 */
    @Column(name = "report_version_id", nullable = false, updatable = false)
    private Long reportVersionId;

    /** 执行本次审核的用户标识，数据库列名沿用 {@code user_id}。 */
    @Column(name = "user_id", length = 32, nullable = false, updatable = false)
    private String reviewer;

    /** 状态变更前的报告审核状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32, nullable = false, updatable = false)
    private InvestmentReportVersion.ReviewStatus fromStatus;

    /** 状态变更后的报告审核状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32, nullable = false, updatable = false)
    private InvestmentReportVersion.ReviewStatus toStatus;

    /** 审核说明；部分状态允许为空，拒绝和补充研究由服务层要求必填。 */
    @Column(columnDefinition = "TEXT", updatable = false)
    private String comment;

    /** 审核记录创建时间，由 JPA 首次持久化时补齐。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 首次保存前补齐创建时间，同时保留测试或迁移代码显式提供的时间。
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * 返回审核用户标识的 API 友好别名。
     *
     * @return 与 {@link #reviewer} 相同的用户标识
     */
    public String getReviewerUserId() {
        return reviewer;
    }

    /**
     * 通过 API 友好名称设置审核用户标识。
     *
     * @param reviewerUserId 执行审核的用户标识
     */
    public void setReviewerUserId(String reviewerUserId) {
        this.reviewer = reviewerUserId;
    }
}
