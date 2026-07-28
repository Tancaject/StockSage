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
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "research_tasks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_research_task_idempotency",
                        columnNames = {"idempotency_key"}
                )
        },
        indexes = {
                @Index(name = "idx_research_task_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_research_task_status_stage", columnList = "status, stage"),
                @Index(name = "idx_research_task_heartbeat", columnList = "status, heartbeat_at"),
                @Index(name = "idx_research_task_ticker_created", columnList = "ticker, created_at")
        }
)
public class ResearchTask {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public enum Stage {
        CREATED,
        DATA_PREFETCH,
        INDICATORS,
        RETRIEVAL,
        AGENT_DEBATE,
        REPORT_SYNTHESIS,
        REPORT_PERSIST,
        COMPLETE,
        FAILED
    }

    public enum ResultKind {
        FULL_REPORT,
        INSUFFICIENT_EVIDENCE,
        OFFLINE_FALLBACK,
        POLICY_BLOCKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "idempotency_key", length = 191, nullable = false)
    private String idempotencyKey;

    @Column(length = 32, nullable = false)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Stage stage = Stage.CREATED;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "payload_json", columnDefinition = "JSON", nullable = false)
    private String payloadJson = "{}";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "result_report_version_id")
    private Long resultReportVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_kind", length = 32)
    private ResultKind resultKind;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (heartbeatAt == null) {
            heartbeatAt = now;
        }
        if (status == null) {
            status = Status.PENDING;
        }
        if (stage == null) {
            stage = Stage.CREATED;
        }
        if (attempts == null) {
            attempts = 0;
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            payloadJson = "{}";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
