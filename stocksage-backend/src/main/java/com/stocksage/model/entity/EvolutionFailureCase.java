package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自进化失败池中的一条候选失败。
 *
 * <p>来源包括用户对回答的反馈、DEEP 报告审核驳回、普通 FUNDAMENTALS 回答未完成。
 * 入池只代表"可能有问题"；人工分诊为 {@link FailureType#METHOD} 后才会被离线学习流程读取。</p>
 */
@Data
@Entity
@Table(name = "evolution_failure_cases")
public class EvolutionFailureCase {

    public enum Source { USER_FEEDBACK, REPORT_REVIEW, ORDINARY_OUTCOME }

    public enum Status { NEW, TRIAGED }

    /** 分诊结论：只有 METHOD 进入反思学习；DETERMINISTIC 交给代码修复。 */
    public enum FailureType { METHOD, DETERMINISTIC, DATA_GAP, PROVIDER, NOT_A_FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Source source;

    /** 发生失败的路由，如 FUNDAMENTALS、DEEP；Trace 中无法判定时为 UNKNOWN。 */
    @Column(length = 16, nullable = false)
    private String route;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "report_version_id")
    private Long reportVersionId;

    @Column(name = "task_outcome", length = 32)
    private String taskOutcome;

    /** 该次回答实际使用的方法包，用于把失败归属到具体方法版本。 */
    @Column(name = "method_bundle_id", length = 80)
    private String methodBundleId;

    @Column(name = "user_query", columnDefinition = "TEXT")
    private String userQuery;

    /** 用户反馈说明或审核评论。 */
    @Column(columnDefinition = "TEXT")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type", length = 24)
    private FailureType failureType;

    @Column(name = "triage_note", columnDefinition = "TEXT")
    private String triageNote;

    @Column(name = "triaged_at")
    private LocalDateTime triagedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
