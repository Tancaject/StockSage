package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 长期用户画像：组合持仓、关注列表、风险偏好等。
 */
@Data
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    @Column(name = "user_id", length = 32)
    private String userId;

    /** 用户当前持有股票代码的 JSON 数组 */
    @Column(name = "holdings", columnDefinition = "JSON")
    private String holdings;

    /** 用户关注股票代码的 JSON 数组 */
    @Column(name = "watch_list", columnDefinition = "JSON")
    private String watchList;

    /** 风险偏好："conservative" | "moderate" | "aggressive" */
    @Column(name = "risk_preference", length = 32)
    private String riskPreference;

    /** 从历史对话中提炼出的自由文本摘要 */
    @Column(name = "profile_summary", columnDefinition = "TEXT")
    private String profileSummary;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
