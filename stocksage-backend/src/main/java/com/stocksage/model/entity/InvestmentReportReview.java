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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_version_id", nullable = false, updatable = false)
    private Long reportVersionId;

    @Column(name = "user_id", length = 32, nullable = false, updatable = false)
    private String reviewer;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32, nullable = false, updatable = false)
    private InvestmentReportVersion.ReviewStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32, nullable = false, updatable = false)
    private InvestmentReportVersion.ReviewStatus toStatus;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getReviewerUserId() {
        return reviewer;
    }

    public void setReviewerUserId(String reviewerUserId) {
        this.reviewer = reviewerUserId;
    }
}
