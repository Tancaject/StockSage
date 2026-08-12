package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户长期偏好的持久化画像实体。
 *
 * <p>每个用户最多一行，保存持仓、关注列表、风险偏好和对话提炼摘要；
 * 聊天服务读取这些信息后注入个性化上下文。账号凭据由 {@link User} 单独保存。</p>
 */
@Data
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    /** 用户标识，同时作为画像表主键；对应账号的 {@link User#getUserId()}。 */
    @Id
    @Column(name = "user_id", length = 32)
    private String userId;

    /** 用户当前持有证券代码的 JSON 字符串数组；新用户通常为 {@code []}。 */
    @Column(name = "holdings", columnDefinition = "JSON")
    private String holdings;

    /** 用户关注但未必持有的证券代码 JSON 字符串数组。 */
    @Column(name = "watch_list", columnDefinition = "JSON")
    private String watchList;

    /** 风险偏好，例如 conservative、moderate 或 aggressive。 */
    @Column(name = "risk_preference", length = 32)
    private String riskPreference;

    /** 从历史对话中提炼的长期背景与偏好摘要；没有已知事实时可为空。 */
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
