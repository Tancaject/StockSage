package com.stocksage.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 登录账号及认证属性的持久化实体。
 *
 * <p>该表只保存身份、密码哈希和展示名；持仓与风险偏好位于
 * {@link UserProfile}，会话和报告通过 {@link #userId} 实现用户隔离。</p>
 */
@Data
@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email")
)
public class User {

    /** 应用生成的稳定用户标识，也是其他业务表使用的租户键。 */
    @Id
    @Column(name = "user_id", length = 32)
    private String userId;

    /** 登录邮箱，数据库唯一；历史或演示记录可能为空。 */
    @Column(name = "email", length = 255, unique = true)
    private String email;

    /** 单向编码后的密码哈希，绝不保存明文；无密码账号可为空。 */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** 邮箱是否已完成验证；当前轻量登录流程可保留为 false。 */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    /** 前端展示名；未设置时可为空并由调用方回退到邮箱。 */
    @Column(name = "nickname", length = 64)
    private String nickname;

    /** 账号首次写入数据库的时间。 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 账号凭据或资料最近更新时间。 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 首次保存账号前补齐时间戳。
     *
     * <p>保留调用方显式提供的 {@link #createdAt}，并始终刷新 {@link #updatedAt}。</p>
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /** 更新账号前刷新 {@link #updatedAt}；由 JPA 自动调用。 */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
