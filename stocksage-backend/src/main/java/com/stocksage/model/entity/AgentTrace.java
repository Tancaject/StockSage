package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 一次智能体请求的持久化执行链路。
 *
 * <p>{@code TraceService} 在请求开始时创建记录，随后把规划和工具步骤序列化到
 * {@link #steps}，结束时补齐状态、token 和耗时；前端据此展示可审计执行过程。</p>
 */
@Data
@Entity
@Table(name = "agent_traces")
public class AgentTrace {

    /** 全局唯一链路 ID，也是 SSE 事件、助手消息和外部 Phoenix span 的关联键。 */
    @Id
    @Column(length = 64)
    private String traceId;

    /** 链路所属用户标识，用于查询和权限隔离。 */
    @Column(length = 32, nullable = false)
    private String userId;

    /** 关联会话主键；尚未建立会话或非聊天路径时可为 null。 */
    private Long conversationId;

    /** 触发本次链路的原始用户问题。 */
    @Column(columnDefinition = "TEXT")
    private String userQuery;

    /** 已持久化的规划、动作和观察步骤总数。 */
    private Integer totalSteps;

    /** 本次模型调用估算或统计的总 token 数；未统计时通常为 0。 */
    private Integer totalTokens;

    /** 总墙钟耗时，单位毫秒 */
    private Long durationMs;

    /** 链路状态，例如 running、success、error 或 cancelled。 */
    @Column(length = 16)
    private String status;

    /** {@code AgentStep} 的有序 JSON 数组；新链路初始化为 {@code []}。 */
    @Column(columnDefinition = "JSON")
    private String steps;

    /** 链路创建时间，由 {@code TraceService} 写入。 */
    private LocalDateTime createdAt;
}
