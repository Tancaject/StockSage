package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;
import java.time.LocalDateTime;

/**
 * 用户长期偏好的持久化画像实体。
 *
 * <p>每个用户最多一行。关注列表是用户显式状态；持仓、风险偏好和摘要列保留为兼容投影，
 * Prompt 可见性由事实表的确认时间和撤销状态决定。账号凭据由 {@link User} 单独保存。</p>
 */
@Data
@Entity
@DynamicUpdate
@Table(name = "user_profiles")
public class UserProfile {

    /** 用户标识，同时作为画像表主键；对应账号的 {@link User#getUserId()}。 */
    @Id
    @Column(name = "user_id", length = 32)
    private String userId;

    /** 学习到的持仓兼容投影；事实级可见性由 user_memory_facts 决定。 */
    @Column(name = "holdings", columnDefinition = "JSON")
    private String holdings;

    /** 用户关注但未必持有的证券代码 JSON 字符串数组。 */
    @Column(name = "watch_list", columnDefinition = "JSON")
    private String watchList;

    /** 风险偏好兼容投影，例如 conservative、moderate 或 aggressive。 */
    @Column(name = "risk_preference", length = 32)
    private String riskPreference;

    /** 历史画像摘要兼容投影；没有已知事实时可为空。 */
    @Column(name = "profile_summary", columnDefinition = "TEXT")
    private String profileSummary;

    /** 画像最近一次写入时间，由 JPA 生命周期回调自动刷新。 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 新增或更新画像前刷新 {@link #updatedAt}。
     *
     * <p>该方法由 JPA 自动调用，不应由业务代码手动触发。</p>
     */
    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
