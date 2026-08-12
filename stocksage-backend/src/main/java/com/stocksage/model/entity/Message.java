package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 会话中的一条持久化消息。
 *
 * <p>消息按 {@link #conversationId} 归组并按创建时间重建历史；助手最终答复还可关联
 * 执行链路和模型信息。Redis 短期记忆是派生缓存，不替代此表的持久记录。</p>
 */
@Data
@Entity
@Table(name = "messages")
public class Message {

    /** 数据库自增消息主键，也用于“从此消息重新生成”时裁剪后续分支。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话主键；会话所有权由服务层校验。 */
    @Column(nullable = false)
    private Long conversationId;

    /** 消息角色，例如 user、assistant 或 system。 */
    @Column(length = 16, nullable = false)
    private String role;

    /** 消息正文；不能为空，助手内容通常为最终 Markdown 答复。 */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 该消息估算的 token 数；未统计时可为 null。 */
    private Integer tokenCount;

    /** 生成该助手消息的链路 ID；用户或系统消息通常为 null。 */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 助手最终答复使用的模型能力层级；非助手消息为 null。 */
    @Column(name = "model_tier", length = 16)
    private String modelTier;

    /** 助手最终答复实际调用的模型名；降级或非助手消息可为 null。 */
    @Column(name = "model_name", length = 64)
    private String modelName;

    /** 消息写入时间，由 JPA 在首次持久化前自动生成。 */
    private LocalDateTime createdAt;

    /**
     * 首次保存前写入当前本地时间。
     *
     * <p>该方法由 JPA 自动调用，会覆盖调用方预先设置的创建时间。</p>
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
