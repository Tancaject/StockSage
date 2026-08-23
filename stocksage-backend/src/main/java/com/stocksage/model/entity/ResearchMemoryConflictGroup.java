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

/**
 * 同一用户、同一结构化研究冲突键的稳定真相行和并发锁点。
 *
 * <p>组行保存当前赢家或未消解/阻断状态。冲突裁决必须先锁定本行，再按主键顺序锁定
 * 组内 {@link ResearchMemoryEntry}，避免并发捕获、审核和撤销选出不同赢家。</p>
 */
@Data
@Entity
@Table(
        name = "research_memory_conflict_groups",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_research_memory_conflict_group",
                columnNames = {"user_id", "conflict_key"}
        ),
        indexes = {
                @Index(name = "idx_research_memory_conflict_group_winner",
                        columnList = "winner_entry_id"),
                @Index(name = "idx_research_memory_conflict_group_status",
                        columnList = "user_id, resolution_status")
        }
)
public class ResearchMemoryConflictGroup {

    /** 数据库自增冲突组主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 冲突组所属用户，也是所有查询必须携带的租户边界。 */
    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    /** 结构化冲突键，例如 REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM。 */
    @Column(name = "conflict_key", length = 128, nullable = false)
    private String conflictKey;

    /** 当前可注入的赢家记忆主键；未消解或阻断时为 null。 */
    @Column(name = "winner_entry_id")
    private Long winnerEntryId;

    /** 当前组级裁决状态，与记忆向量索引生命周期完全独立。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", length = 24, nullable = false)
    private ResolutionStatus resolutionStatus;

    /** 用户显式撤销赢家时的阻断时间；更早的旧结论不得静默复活。 */
    @Column(name = "blocked_before_at")
    private LocalDateTime blockedBeforeAt;

    /** 冲突组首次创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 赢家、裁决状态或阻断时间最近更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 首次保存前补齐安全初始状态和时间。 */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? now : updatedAt;
        resolutionStatus = resolutionStatus == null
                ? ResolutionStatus.UNRESOLVED : resolutionStatus;
    }

    /** 每次变更组级真相前刷新更新时间。 */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 冲突组的业务裁决状态。 */
    public enum ResolutionStatus {
        /** 已选出唯一当前赢家。 */
        RESOLVED,

        /** 同等时点、同等审核级别的相反结论尚无法确定赢家。 */
        UNRESOLVED,

        /** 当前组内没有可参与选举的有效候选。 */
        NO_ELIGIBLE_CANDIDATE,

        /** 用户撤销当前赢家，且没有更新的有效结论可解除阻断。 */
        BLOCKED
    }
}
