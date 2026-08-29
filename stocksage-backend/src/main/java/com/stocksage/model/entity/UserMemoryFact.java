package com.stocksage.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 从对话或显式画像更新中确认的长期用户事实。
 *
 * <p>权重由读取时的确认年龄计算，不持久化派生分数；撤销和低权重只影响可见性，
 * 事实行保留用于审计与后续重新确认。</p>
 */
@Data
@Entity
@Table(
        name = "user_memory_facts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_memory_fact",
                columnNames = {"user_id", "fact_type", "fact_key"}
        ),
        indexes = @Index(
                name = "idx_user_memory_fact_active",
                columnList = "user_id, revoked_at"
        )
)
public class UserMemoryFact {

    /** 当前需要独立确认时间的三类画像事实；关注列表由用户显式管理，不参与衰减。 */
    public enum FactType {
        HOLDING,
        RISK_PREFERENCE,
        PROFILE_SUMMARY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", length = 32, nullable = false)
    private FactType factType;

    /** 类型内稳定键；持仓使用归一化 ticker，单值字段使用固定键。 */
    @Column(name = "fact_key", length = 128, nullable = false)
    private String factKey;

    @Column(name = "fact_value", columnDefinition = "TEXT", nullable = false)
    private String factValue;

    /** 事实级确认时间；无法证明来源时间的 V8 旧数据回填为 null，并在查询时排除。 */
    @Column(name = "last_confirmed_at")
    private LocalDateTime lastConfirmedAt;

    /** 最近一次确认或撤销该事实的用户消息；显式 API 更新允许为空。 */
    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
