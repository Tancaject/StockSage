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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(length = 32, nullable = false)
    private String ticker;

    @Column(name = "user_query", columnDefinition = "TEXT")
    private String userQuery;

    @Column(length = 32)
    private String recommendation;

    @Column(name = "report_version", nullable = false)
    private Integer reportVersion;

    @Column(name = "data_snapshot_hash", length = 64, nullable = false)
    private String dataSnapshotHash;

    @Column(name = "context_hash", length = 64, nullable = false)
    private String contextHash;

    @Column(name = "model_tier", length = 16)
    private String modelTier;

    @Column(name = "model_name", length = 64)
    private String modelName;

    @Column(name = "report_json", columnDefinition = "JSON", nullable = false)
    private String reportJson;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 32, nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.DRAFT;

    @Column(name = "reviewer_user_id", length = 32)
    private String reviewerUserId;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

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

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum ReviewStatus {
        DRAFT,
        IN_REVIEW,
        APPROVED,
        REJECTED,
        NEEDS_RESEARCH
    }
}
