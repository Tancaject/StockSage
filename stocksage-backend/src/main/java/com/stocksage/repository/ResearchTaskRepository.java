package com.stocksage.repository;

import com.stocksage.model.entity.ResearchTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 可恢复深度研究任务的状态机仓储。
 *
 * <p>普通查询用于用户视图、配额和恢复扫描；原生条件更新把状态与 lease token 一并放进
 * {@code WHERE} 子句，形成数据库级比较并交换。返回 0 表示状态或所有权已变化，调用方不得继续写。</p>
 */
public interface ResearchTaskRepository extends JpaRepository<ResearchTask, Long> {

    /**
     * 按全局唯一提交键查找任务，用于重复提交复用同一真相行。
     *
     * @param idempotencyKey 由用户、会话、ticker 和问题生成的幂等键
     * @return 已存在任务；首次提交时为空
     */
    Optional<ResearchTask> findByIdempotencyKey(String idempotencyKey);

    /**
     * 统计用户处于指定状态的任务数，主要用于 PENDING/RUNNING 配额。
     *
     * @param userId 任务所属用户
     * @param statuses 要计数的状态集合
     * @return 匹配任务数量
     */
    long countByUserIdAndStatusIn(
            String userId,
            Collection<ResearchTask.Status> statuses
    );

    /**
     * 统计全局某状态任务数，供监控指标使用。
     *
     * @param status 目标生命周期状态
     * @return 匹配任务数量
     */
    long countByStatus(ResearchTask.Status status);

    /**
     * 查找用户某会话最近的一条指定状态任务。
     *
     * @param userId 任务所属用户
     * @param conversationId 会话主键
     * @param statuses 允许命中的状态集合
     * @return 创建时间最新的匹配任务；没有时为空
     */
    Optional<ResearchTask> findFirstByUserIdAndConversationIdAndStatusInOrderByCreatedAtDesc(
            String userId,
            Long conversationId,
            Collection<ResearchTask.Status> statuses
    );

    /**
     * 按主键和所有者读取任务，避免仅凭任务 ID 越权访问。
     *
     * @param id 任务主键
     * @param userId 当前用户标识
     * @return 用户拥有的任务；不存在或越权时为空
     */
    Optional<ResearchTask> findByIdAndUserId(Long id, String userId);

    /**
     * 扫描心跳早于阈值的指定状态任务，供失联任务恢复器处理。
     *
     * @param status 通常为 RUNNING
     * @param heartbeatBefore 心跳过期边界，不包含等于边界的记录
     * @return 可能失联的候选任务；最终操作仍需条件更新再次校验
     */
    List<ResearchTask> findByStatusAndHeartbeatAtBefore(
            ResearchTask.Status status,
            LocalDateTime heartbeatBefore
    );

    /**
     * 按状态和稳定错误消息查找任务，供特定恢复路径定位历史标记。
     *
     * @param status 目标状态
     * @param errorMessage 要精确匹配的错误消息
     * @return 匹配任务列表
     */
    List<ResearchTask> findByStatusAndErrorMessage(
            ResearchTask.Status status,
            String errorMessage
    );

    /**
     * 分页读取用户某标的的任务时间线，创建时间新的在前。
     *
     * @param userId 任务所属用户
     * @param ticker 已归一化的证券代码
     * @param pageable 页码和每页数量
     * @return 当前页任务；没有记录时为空列表
     */
    List<ResearchTask> findByUserIdAndTickerOrderByCreatedAtDesc(
            String userId,
            String ticker,
            Pageable pageable
    );

    /**
     * 仅在任务仍为 PENDING 时开始一次执行尝试。
     *
     * <p>更新会切换为 RUNNING、递增 attempts、写入租约和起始心跳，并清空旧错误。
     * 调用方必须在事务中执行，并以返回行数判断是否赢得启动竞争。</p>
     *
     * @param id 任务主键
     * @param leaseToken 新执行者的租约令牌
     * @param stage 初始执行阶段枚举名
     * @param startedAt 尝试开始时间，同时作为初始心跳和更新时间
     * @return 1 表示成功启动；0 表示任务已不再是 PENDING
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'RUNNING',
                   stage = :stage,
                   attempts = attempts + 1,
                   lease_token = :leaseToken,
                   started_at = :startedAt,
                   completed_at = NULL,
                   heartbeat_at = :startedAt,
                   error_message = NULL,
                   updated_at = :startedAt
             WHERE id = :id
               AND status = 'PENDING'
            """, nativeQuery = true)
    int startAttemptIfPending(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("stage") String stage,
            @Param("startedAt") LocalDateTime startedAt
    );

    /**
     * 接管心跳过期且尚未达到尝试上限的运行中任务。
     *
     * <p>比较旧 lease token 后原子替换为新 token 并递增 attempts；旧 worker 随后的所有者更新将失败。</p>
     *
     * @param id 任务主键
     * @param observedLeaseToken 扫描时观察到的旧租约令牌
     * @param newLeaseToken 接管 worker 的新租约令牌
     * @param heartbeatBefore 心跳必须早于该过期边界
     * @param maxAttempts 最大允许尝试次数，当前 attempts 必须小于该值
     * @param takenOverAt 接管时间，同时刷新开始、心跳和更新时间
     * @return 1 表示接管成功；0 表示任一围栏条件已变化
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET lease_token = :newLeaseToken,
                   attempts = attempts + 1,
                   started_at = :takenOverAt,
                   completed_at = NULL,
                   heartbeat_at = :takenOverAt,
                   error_message = NULL,
                   updated_at = :takenOverAt
             WHERE id = :id
               AND status = 'RUNNING'
               AND lease_token = :observedLeaseToken
               AND heartbeat_at < :heartbeatBefore
               AND attempts < :maxAttempts
            """, nativeQuery = true)
    int takeOverStaleRunningAttempt(
            @Param("id") Long id,
            @Param("observedLeaseToken") String observedLeaseToken,
            @Param("newLeaseToken") String newLeaseToken,
            @Param("heartbeatBefore") LocalDateTime heartbeatBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("takenOverAt") LocalDateTime takenOverAt
    );

    /**
     * 将已过期且达到尝试上限的运行中任务原子关闭为 FAILED。
     *
     * <p>只有旧 lease、过期心跳和 attempts 上限同时匹配才会清空租约并写入失败终态。</p>
     *
     * @param id 任务主键
     * @param observedLeaseToken 扫描时观察到的租约令牌
     * @param heartbeatBefore 心跳过期边界
     * @param maxAttempts 最大尝试次数，当前 attempts 必须大于等于该值
     * @param errorMessage 持久化的终态错误说明
     * @param failedAt 失败时间，同时刷新心跳和更新时间
     * @return 1 表示关闭成功；0 表示任务不再满足失败围栏
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'FAILED',
                   stage = 'FAILED',
                   error_message = :errorMessage,
                   completed_at = :failedAt,
                   heartbeat_at = :failedAt,
                   updated_at = :failedAt,
                   lease_token = NULL
             WHERE id = :id
               AND status = 'RUNNING'
               AND lease_token = :observedLeaseToken
               AND heartbeat_at < :heartbeatBefore
               AND attempts >= :maxAttempts
            """, nativeQuery = true)
    int failStaleRunningAtAttemptLimit(
            @Param("id") Long id,
            @Param("observedLeaseToken") String observedLeaseToken,
            @Param("heartbeatBefore") LocalDateTime heartbeatBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("errorMessage") String errorMessage,
            @Param("failedAt") LocalDateTime failedAt
    );

    /**
     * 将心跳过期但仍可重试的运行中任务重置为 PENDING。
     *
     * <p>方法清空租约、完成时间并把阶段退回 CREATED，但保留 attempts，下一次启动时再递增。</p>
     *
     * @param id 任务主键
     * @param observedLeaseToken 扫描时观察到的租约令牌
     * @param heartbeatBefore 心跳过期边界
     * @param maxAttempts 最大尝试次数，当前 attempts 必须小于该值
     * @param errorMessage 记录本次恢复原因
     * @param resetAt 重置时间，同时刷新心跳和更新时间
     * @return 1 表示成功排回待执行；0 表示围栏条件已变化
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'PENDING',
                   stage = 'CREATED',
                   lease_token = NULL,
                   error_message = :errorMessage,
                   completed_at = NULL,
                   heartbeat_at = :resetAt,
                   updated_at = :resetAt
             WHERE id = :id
               AND status = 'RUNNING'
               AND lease_token = :observedLeaseToken
               AND heartbeat_at < :heartbeatBefore
               AND attempts < :maxAttempts
            """, nativeQuery = true)
    int resetStaleRunningForRetry(
            @Param("id") Long id,
            @Param("observedLeaseToken") String observedLeaseToken,
            @Param("heartbeatBefore") LocalDateTime heartbeatBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("errorMessage") String errorMessage,
            @Param("resetAt") LocalDateTime resetAt
    );

    /**
     * 仅在任务尚未启动时将其关闭为 FAILED。
     *
     * @param id 任务主键
     * @param errorMessage 失败原因
     * @param failedAt 失败时间，同时刷新心跳和更新时间
     * @return 1 表示成功关闭；0 表示任务已被其他 worker 启动或进入终态
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'FAILED',
                   stage = 'FAILED',
                   error_message = :errorMessage,
                   completed_at = :failedAt,
                   heartbeat_at = :failedAt,
                   updated_at = :failedAt,
                   lease_token = NULL
             WHERE id = :id
               AND status = 'PENDING'
            """, nativeQuery = true)
    int failIfPending(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("failedAt") LocalDateTime failedAt
    );

    /**
     * 由当前租约持有者把运行中任务退回 PENDING 以便快速重试。
     *
     * <p>该路径不要求心跳过期，但必须匹配 lease token；它保留 attempts，清空租约和完成时间。</p>
     *
     * @param id 任务主键
     * @param leaseToken 当前 worker 的租约令牌
     * @param errorMessage 本次尝试失败原因
     * @param resetAt 重置时间，同时刷新心跳和更新时间
     * @return 1 表示重置成功；0 表示任务已失去所有权或不再运行
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'PENDING',
                   stage = 'CREATED',
                   lease_token = NULL,
                   error_message = :errorMessage,
                   completed_at = NULL,
                   heartbeat_at = :resetAt,
                   updated_at = :resetAt
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int resetRunningForRetryForOwner(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("errorMessage") String errorMessage,
            @Param("resetAt") LocalDateTime resetAt
    );

    /**
     * 将 SUCCEEDED 或 FAILED 的旧任务原子重置为一次全新提交。
     *
     * <p>重置会清除结果、错误、租约、时间和 attempts，并替换会话与 payload。
     * 调用方应在同一事务中删除旧检查点，避免新尝试恢复上一轮快照。</p>
     *
     * @param id 终态任务主键
     * @param conversationId 新提交关联会话；可为 null
     * @param payloadJson 新提交参数 JSON
     * @param resetAt 重置时间，同时作为新心跳和更新时间
     * @return 1 表示成功重置；0 表示任务当前不是终态
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'PENDING',
                   stage = 'CREATED',
                   attempts = 0,
                   conversation_id = :conversationId,
                   lease_token = NULL,
                   payload_json = :payloadJson,
                   error_message = NULL,
                   result_report_version_id = NULL,
                   result_kind = NULL,
                   started_at = NULL,
                   completed_at = NULL,
                   heartbeat_at = :resetAt,
                   updated_at = :resetAt
             WHERE id = :id
               AND status IN ('SUCCEEDED', 'FAILED')
            """, nativeQuery = true)
    int resetTerminalForResubmission(
            @Param("id") Long id,
            @Param("conversationId") Long conversationId,
            @Param("payloadJson") String payloadJson,
            @Param("resetAt") LocalDateTime resetAt
    );

    /**
     * 以类型安全的阶段枚举推进当前租约持有者的任务。
     *
     * @param id 任务主键
     * @param leaseToken 当前 worker 的租约令牌
     * @param stage 要写入的非空阶段
     * @param heartbeatAt 阶段推进时间，同时刷新心跳和更新时间
     * @return 1 表示推进成功；0 表示任务已失去所有权或不再运行
     */
    default int advanceStageForOwner(
            Long id,
            String leaseToken,
            ResearchTask.Stage stage,
            LocalDateTime heartbeatAt
    ) {
        return advanceStageForOwnerValue(id, leaseToken, stage.name(), heartbeatAt);
    }

    /**
     * 执行阶段推进的原生条件更新；业务代码优先调用 {@link #advanceStageForOwner}。
     *
     * @param id 任务主键
     * @param leaseToken 当前 worker 的租约令牌
     * @param stage 阶段枚举名
     * @param heartbeatAt 阶段推进时间
     * @return 1 表示更新成功；0 表示所有权或状态围栏不匹配
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET stage = :stage,
                   heartbeat_at = :heartbeatAt,
                   updated_at = :heartbeatAt
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int advanceStageForOwnerValue(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("stage") String stage,
            @Param("heartbeatAt") LocalDateTime heartbeatAt
    );

    /**
     * 仅为当前租约持有者刷新运行中任务心跳。
     *
     * @param id 任务主键
     * @param leaseToken 当前 worker 的租约令牌
     * @param heartbeatAt 新心跳时间，同时刷新更新时间
     * @return 1 表示续命成功；0 表示任务已失去所有权或不再运行
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET heartbeat_at = :heartbeatAt,
                   updated_at = :heartbeatAt
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int heartbeatForOwner(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("heartbeatAt") LocalDateTime heartbeatAt
    );

    /**
     * 由当前租约持有者发布成功终态。
     *
     * <p>更新会写入报告关联与结果类型、切换到 SUCCEEDED/COMPLETE，并清空租约和错误。
     * 报告、最终消息和此更新应由服务层放在同一事务中提交。</p>
     *
     * @param id 任务主键
     * @param leaseToken 当前 worker 的租约令牌
     * @param resultReportVersionId 产出的报告版本主键；无完整报告时可为 null
     * @param resultKind {@code ResearchTask.ResultKind} 枚举名
     * @param completedAt 完成时间，同时刷新心跳和更新时间
     * @return 1 表示发布成功；0 表示任务已失去所有权或不再运行
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'SUCCEEDED',
                   stage = 'COMPLETE',
                   result_report_version_id = :resultReportVersionId,
                   result_kind = :resultKind,
                   completed_at = :completedAt,
                   heartbeat_at = :completedAt,
                   updated_at = :completedAt,
                   lease_token = NULL,
                   error_message = NULL
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int completeForOwner(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("resultReportVersionId") Long resultReportVersionId,
            @Param("resultKind") String resultKind,
            @Param("completedAt") LocalDateTime completedAt
    );

    /**
     * 由当前租约持有者发布失败终态。
     *
     * <p>更新会切换到 FAILED 状态和阶段，保存错误并清空租约。</p>
     *
     * @param id 任务主键
     * @param leaseToken 当前 worker 的租约令牌
     * @param errorMessage 持久化错误说明
     * @param failedAt 失败时间，同时刷新心跳和更新时间
     * @return 1 表示发布成功；0 表示任务已失去所有权或不再运行
     */
    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'FAILED',
                   stage = 'FAILED',
                   error_message = :errorMessage,
                   completed_at = :failedAt,
                   heartbeat_at = :failedAt,
                   updated_at = :failedAt,
                   lease_token = NULL
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int failForOwner(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("errorMessage") String errorMessage,
            @Param("failedAt") LocalDateTime failedAt
    );
}
