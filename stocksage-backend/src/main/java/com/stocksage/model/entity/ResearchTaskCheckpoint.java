package com.stocksage.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 深度研究任务的单行持久化检查点。
 *
 * <p>每个任务最多保存一个最新快照。后台 worker 在阶段完成后写入序列化的
 * {@code AnalysisState}，任务重试或被其他实例接管时从这里继续，避免重复模型调用。</p>
 */
@Data
@Entity
@Table(
        name = "research_task_checkpoints",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_checkpoint_task",
                columnNames = {"task_id"}
        )
)
public class ResearchTaskCheckpoint {

    /** 数据库自增检查点主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属研究任务主键；唯一约束保证每个任务仅保留最新检查点。 */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 快照已经完整完成的最后一个任务阶段，恢复时从其后继续。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_completed", length = 32, nullable = false)
    private ResearchTask.Stage stageCompleted;

    /** 已完成的完整多空辩论轮数，从 0 开始计数。 */
    @Column(name = "debate_rounds_completed", nullable = false)
    private Integer debateRoundsCompleted = 0;

    /** 当前管线计划执行的辩论总轮数；尚未规划时为 0。 */
    @Column(name = "planned_rounds", nullable = false)
    private Integer plannedRounds = 0;

    /** 序列化后的 {@code AnalysisState}，包含证据账本、辩论和 Harness 状态。 */
    @Column(name = "payload_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String payloadJson;

    /** 检查点首次创建时间。 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 检查点最近覆盖更新时间。 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 首次持久化前补齐时间和计数默认值。
     *
     * <p>JPA 自动调用；显式提供的创建、更新时间和计数不会被覆盖。</p>
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (debateRoundsCompleted == null) {
            debateRoundsCompleted = 0;
        }
        if (plannedRounds == null) {
            plannedRounds = 0;
        }
    }

    /** 每次覆盖检查点前刷新 {@link #updatedAt}；由 JPA 自动调用。 */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
