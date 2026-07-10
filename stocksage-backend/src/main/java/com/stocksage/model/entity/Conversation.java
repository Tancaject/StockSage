package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 归属于当前会话用户的持久化对话线程。
 *
 * <p>消息表保存实际对话内容；该行保存侧边栏使用的会话标题和排序时间。</p>
 */
@Data
@Entity
@Table(name = "conversations")
public class Conversation {

    /** 会话主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话所属用户标识，用于隔离不同用户的对话列表。 */
    @Column(length = 32, nullable = false)
    private String userId;

    /** 会话来源：chat 显示在聊天侧栏；workbench 归属于研究工作台。 */
    @Column(length = 16, nullable = false)
    private String origin = "chat";

    /** 侧边栏展示标题，通常由首条用户问题或摘要生成。 */
    @Column(length = 128)
    private String title;

    /** 会话创建时间。 */
    private LocalDateTime createdAt;

    /** 会话最近更新时间，用于列表倒序排序。 */
    private LocalDateTime updatedAt;

    /**
     * 首次保存会话前初始化创建和更新时间。
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * 每次更新会话元数据前刷新更新时间。
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
