package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long conversationId;

    /** 消息角色："user" | "assistant" | "system" */
    @Column(length = 16, nullable = false)
    private String role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 该消息的令牌数 */
    private Integer tokenCount;

    /** 关联的链路追踪 ID（仅 assistant 消息） */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 助手消息最终回答使用的模型能力层级 */
    @Column(name = "model_tier", length = 16)
    private String modelTier;

    /** 助手消息最终回答实际调用的 DashScope 模型名 */
    @Column(name = "model_name", length = 64)
    private String modelName;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
