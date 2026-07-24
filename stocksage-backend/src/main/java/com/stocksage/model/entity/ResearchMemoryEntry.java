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
        name = "research_memory_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_research_memory_source",
                columnNames = {"user_id", "source_type", "source_id"}
        ),
        indexes = {
                @Index(name = "idx_research_memory_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_research_memory_user_ticker_status",
                        columnList = "user_id, ticker, vector_status")
        }
)
public class ResearchMemoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    @Column(length = 32, nullable = false)
    private String ticker;

    @Column(name = "source_type", length = 32, nullable = false)
    private String sourceType;

    @Column(name = "source_id", length = 64, nullable = false)
    private String sourceId;

    @Column(name = "source_conversation_id")
    private Long sourceConversationId;

    @Column(name = "source_trace_id", length = 64)
    private String sourceTraceId;

    @Column(name = "source_citations", columnDefinition = "JSON", nullable = false)
    private String sourceCitations;

    @Column(name = "data_cutoff_at")
    private LocalDateTime dataCutoffAt;

    @Column(name = "snapshot_hash", length = 64, nullable = false)
    private String snapshotHash;

    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    @Column(name = "memory_text", columnDefinition = "TEXT", nullable = false)
    private String memoryText;

    @Enumerated(EnumType.STRING)
    @Column(name = "vector_status", length = 16, nullable = false)
    private VectorStatus vectorStatus;

    @Column(name = "vector_error_code", length = 64)
    private String vectorErrorCode;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
        vectorStatus = vectorStatus == null ? VectorStatus.PENDING : vectorStatus;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean active() {
        return revokedAt == null && vectorStatus != VectorStatus.REVOKED;
    }

    public enum VectorStatus {
        PENDING,
        INDEXED,
        FAILED,
        REVOKED
    }
}
