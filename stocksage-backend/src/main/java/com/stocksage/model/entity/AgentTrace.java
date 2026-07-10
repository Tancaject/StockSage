package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 记录一次智能体调用的完整链路（一条用户问题对应一条链路）。
 */
@Data
@Entity
@Table(name = "agent_traces")
public class AgentTrace {

    @Id
    @Column(length = 64)
    private String traceId;

    @Column(length = 32, nullable = false)
    private String userId;

    private Long conversationId;

    @Column(columnDefinition = "TEXT")
    private String userQuery;

    /** 智能体执行的总步骤数（思考 -> 动作 -> 观察循环） */
    private Integer totalSteps;

    /** 消耗的总令牌数（提示词 + 补全文本） */
    private Integer totalTokens;

    /** 总墙钟耗时，单位毫秒 */
    private Long durationMs;

    /** 终止状态："success" | "error" | "timeout" */
    @Column(length = 16)
    private String status;

    /** 步骤详情的 JSON 数组 */
    @Column(columnDefinition = "JSON")
    private String steps;

    private LocalDateTime createdAt;
}
