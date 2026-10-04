package com.stocksage.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次可恢复深度研究作业的数据库真相记录。
 *
 * <p>Redis Stream 负责投递，MySQL 通过状态、阶段、心跳和 {@link #leaseToken} 决定谁有权推进任务。
 * 所有 worker 状态变更都应使用仓储中的条件更新，避免失效持有者覆盖接管后的结果。</p>
 */
@Data
@Entity
@Table(
        name = "research_tasks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_research_task_idempotency",
                        columnNames = {"idempotency_key"}
                )
        },
        indexes = {
                @Index(name = "idx_research_task_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_research_task_status_stage", columnList = "status, stage"),
                @Index(name = "idx_research_task_heartbeat", columnList = "status, heartbeat_at"),
                @Index(name = "idx_research_task_ticker_created", columnList = "ticker, created_at"),
                @Index(name = "idx_research_task_request", columnList = "user_id, request_fingerprint, id")
        }
)
public class ResearchTask {

    /** 研究任务的粗粒度生命周期。 */
    public enum Status {
        /** 已创建或已重置，等待 worker 开始一次尝试。 */
        PENDING,

        /** 某个持有租约的 worker 正在执行。 */
        RUNNING,

        /** 管线已正常结束；是否产出完整报告由 {@link ResultKind} 区分。 */
        SUCCEEDED,

        /** 任务达到不可恢复错误或尝试上限。 */
        FAILED
    }

    /** 深度研究管线的可检查点执行阶段。 */
    public enum Stage {
        /** 任务刚创建，尚未开始数据工作。 */
        CREATED,

        /** 正在预取行情、财务、新闻等外部数据。 */
        DATA_PREFETCH,

        /** 正在整理指标和结构化分析输入。 */
        INDICATORS,

        /** 正在检索 RAG 或研究记忆证据。 */
        RETRIEVAL,

        /** 正在执行多头与空头辩论。 */
        AGENT_DEBATE,

        /** 正在由研究经理合成结构化报告。 */
        REPORT_SYNTHESIS,

        /** 正在原子持久化报告、助手消息和任务终态。 */
        REPORT_PERSIST,

        /** 管线成功结束。 */
        COMPLETE,

        /** 管线以失败终态结束。 */
        FAILED
    }

    /** 成功结束的任务实际交付了哪类结果。 */
    public enum ResultKind {
        /** 证据和报告门禁通过，并持久化了完整报告版本。 */
        FULL_REPORT,

        /** 数据不足以形成受支持的完整投资建议。 */
        INSUFFICIENT_EVIDENCE,

        /** 外部能力不可用，返回了明确标记的离线演示结果。 */
        OFFLINE_FALLBACK,

        /** 完成策略主动阻止发布不满足门禁的结论。 */
        POLICY_BLOCKED
    }

    /** 数据库自增任务主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务所属用户，也是查询、配额和幂等隔离边界。 */
    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    /** 任务关联会话；尚未绑定或旧任务可为 null。 */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 一次主动运行的提交键；重复投递沿用，旧客户端仍使用历史请求指纹键。 */
    @Column(name = "idempotency_key", length = 191, nullable = false, updatable = false)
    private String idempotencyKey;

    /** 相同用户、标的及问题的跨会话分组指纹，不作为新运行的唯一约束。 */
    @Column(name = "request_fingerprint", length = 191, nullable = false, updatable = false)
    private String requestFingerprint;

    /** 创建时已存在的上一条同组运行；不重置或覆盖它的状态、结果和快照。 */
    @Column(name = "previous_task_id", updatable = false)
    private Long previousTaskId;

    /** 已归一化的研究标的代码。 */
    @Column(length = 32, nullable = false)
    private String ticker;

    /** 当前任务生命周期状态。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private Status status = Status.PENDING;

    /** 当前执行阶段；只有有效租约持有者可以推进运行中任务。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Stage stage = Stage.CREATED;

    /** 已成功开始的执行尝试次数；接管也会递增，用于限制无限重试。 */
    @Column(nullable = false)
    private Integer attempts = 0;

    /** 提交时冻结的总截止时间，包含排队与所有重试；历史未记录值保持 null。 */
    @Column(name = "budget_deadline_epoch_ms", updatable = false)
    private Long budgetDeadlineEpochMs;

    /** 本次运行所有尝试共享的模型调用上限。 */
    @Column(name = "max_model_calls", updatable = false)
    private Integer maxModelCalls;

    /** 提交时冻结额度合同；历史未知值不能视作显式禁用。 */
    @Column(name = "token_budget_json", columnDefinition = "JSON", updatable = false)
    private String tokenBudgetJson;

    /** 当前 worker 的租约令牌；非 RUNNING 状态通常为 null。 */
    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    /** 提交参数 JSON，包含 ticker、问题、traceId 和 conversationId。 */
    @Column(name = "payload_json", columnDefinition = "JSON", nullable = false)
    private String payloadJson = "{}";

    /** 首次执行冻结的模型客户端默认值；仅通过持有者围栏写入，重试不可覆盖。 */
    @Column(name = "model_configuration_json", columnDefinition = "LONGTEXT", insertable = false, updatable = false)
    private String modelConfigurationJson;

    /** 运行中指向最近证据快照，终态后保留；仅通过持有者围栏更新。 */
    @Column(name = "final_evidence_snapshot_id", length = 36, insertable = false, updatable = false)
    private String finalEvidenceSnapshotId;

    /** 最近失败或重试原因；正常运行和成功终态为 null。 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 成功产出的报告版本主键；无完整持久化报告时为 null。 */
    @Column(name = "result_report_version_id")
    private Long resultReportVersionId;

    /** 成功终态的业务结果类型；任务未结束或失败时可为 null。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "result_kind", length = 32)
    private ResultKind resultKind;

    /** 最近一次执行尝试开始时间；尚未启动时为 null。 */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 任务进入成功或失败终态的时间；活跃任务为 null。 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 当前持有者最近一次续命时间，用于识别失联任务和安全接管。 */
    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    /** 任务首次创建时间。 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 状态、阶段、心跳或结果最近更新时间。 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 首次保存任务前补齐生命周期默认值和时间戳。
     *
     * <p>JPA 自动调用；空白 payload 会规范化为 {@code {}}，计数和枚举只在为空时使用默认值。</p>
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (heartbeatAt == null) {
            heartbeatAt = now;
        }
        if (status == null) {
            status = Status.PENDING;
        }
        if (stage == null) {
            stage = Stage.CREATED;
        }
        if (attempts == null) {
            attempts = 0;
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            payloadJson = "{}";
        }
        if (requestFingerprint == null) {
            requestFingerprint = idempotencyKey;
        }
    }

    /** 每次实体更新前刷新 {@link #updatedAt}；条件原生更新会在 SQL 中显式维护同一字段。 */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
