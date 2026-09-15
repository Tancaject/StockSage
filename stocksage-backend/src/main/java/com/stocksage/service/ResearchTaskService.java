package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.repository.ResearchTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 研究任务数据库状态机与幂等键服务。
 *
 * <p>{@link ToolPrefetchService} 创建/重提任务，{@link ResearchTaskWorker} 与
 * {@link DeepResearchPipeline} 通过本服务获取租约并推进 PENDING、RUNNING、SUCCEEDED/FAILED。
 * 生产长任务状态变更使用 leaseToken 条件更新作为 owner fence；调度器也通过心跳截止时间和
 * 已观察 token 做 CAS，避免旧 worker 覆盖新 owner。</p>
 *
 * <p>边界：Redis lease 只是执行权前置协调，最终写入正确性由数据库条件更新保证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchTaskService {

    /** 防止外部异常文本无限扩张任务行。 */
    private static final int MAX_ERROR_LENGTH = 4000;
    /** 调度恢复后保留在 PENDING 行上的可识别重入队标记。 */
    private static final String RECOVERED_FOR_RETRY_MESSAGE = "stale research task recovered for retry";

    /** 执行任务创建、状态 CAS、心跳和恢复查询。 */
    private final ResearchTaskRepository repository;
    /** 终态任务重新提交时在同一事务内清理旧检查点。 */
    private final ResearchTaskCheckpointRepository checkpointRepository;
    /** 提供 Redis 优先、进程回退的可续约租约。 */
    private final ResearchTaskLeaseService leaseService;
    /** 构造和解析稳定任务载荷 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 按幂等键创建 PENDING 任务；并发唯一键冲突时回读已存在任务。
     *
     * @param idempotencyKey 稳定请求幂等键
     * @param userId 任务所属用户
     * @param conversationId 可选会话 ID
     * @param ticker 研究标的
     * @param initialStage 初始阶段；null 使用 CREATED
     * @param payloadJson 后台 worker 所需提交载荷
     * @return 任务及本次是否新建
     */
    public TaskCreation createIfAbsent(
            String idempotencyKey,
            String userId,
            Long conversationId,
            String ticker,
            ResearchTask.Stage initialStage,
            String payloadJson
    ) {
        String normalizedKey = normalizeText(idempotencyKey);
        if (normalizedKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Optional<ResearchTask> existing = repository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            return new TaskCreation(existing.get(), false);
        }

        ResearchTask task = new ResearchTask();
        task.setIdempotencyKey(normalizedKey);
        task.setUserId(normalizeText(userId));
        task.setConversationId(conversationId);
        task.setTicker(normalizeTicker(ticker));
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(initialStage == null ? ResearchTask.Stage.CREATED : initialStage);
        task.setAttempts(0);
        task.setPayloadJson(normalizePayload(payloadJson));

        try {
            // 立即 flush 让数据库唯一键处理跨线程/实例的并发幂等创建。
            return new TaskCreation(repository.saveAndFlush(task), true);
        } catch (DataIntegrityViolationException e) {
            log.warn("Research task already exists after concurrent create, idempotencyKey={}", normalizedKey);
            return repository.findByIdempotencyKey(normalizedKey)
                    .map(found -> new TaskCreation(found, false))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 尝试获取任务幂等键对应的执行租约。
     *
     * @param task 已持久化任务
     * @return 新 owner 租约；已有 owner 或任务为空时为空
     */
    public Optional<ResearchTaskLeaseService.Lease> tryAcquire(ResearchTask task) {
        if (task == null) {
            return Optional.empty();
        }
        return leaseService.tryAcquire(task.getIdempotencyKey());
    }

    /** @param lease 要按 token 安全释放的执行租约。 */
    public void release(ResearchTaskLeaseService.Lease lease) {
        leaseService.release(lease);
    }

    /** @return 是否成功续约且仍拥有该租约。 */
    public boolean renewLease(ResearchTaskLeaseService.Lease lease) {
        return leaseService.renew(lease);
    }

    /** @return 长任务调度器应使用的租约续期间隔。 */
    public Duration leaseHeartbeatInterval() {
        return leaseService.heartbeatInterval();
    }

    /**
     * 以 leaseToken 为 owner fence，把 PENDING 任务原子启动为 RUNNING。
     *
     * @param task 待启动任务
     * @param leaseToken 当前租约 token
     * @param stage 首个执行阶段；null 使用 DATA_PREFETCH
     * @return 同步更新后的传入任务对象
     * @throws IllegalStateException 任务已不再 PENDING 时
     */
    @Transactional
    public ResearchTask startAttempt(
            ResearchTask task,
            String leaseToken,
            ResearchTask.Stage stage
    ) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("persisted research task is required to start an attempt");
        }
        LocalDateTime now = LocalDateTime.now();
        ResearchTask.Stage targetStage = stage == null ? ResearchTask.Stage.DATA_PREFETCH : stage;
        String normalizedLeaseToken = normalizeText(leaseToken);
        // 调用条件 UPDATE，仅 PENDING 行能写入当前 leaseToken 并增加 attempts。
        int updated = repository.startAttemptIfPending(
                task.getId(), normalizedLeaseToken, targetStage.name(), now);
        if (updated == 0) {
            throw new IllegalStateException("research task is no longer pending; execution fencing rejected");
        }
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(targetStage);
        task.setAttempts(safeAttempts(task) + 1);
        task.setLeaseToken(normalizedLeaseToken);
        task.setStartedAt(now);
        task.setCompletedAt(null);
        task.setHeartbeatAt(now);
        task.setErrorMessage(null);
        return task;
    }

    /**
     * 用新 Redis 租约接管 TTL 已过期、心跳已陈旧的 RUNNING 尝试。
     *
     * @param task worker 读取到的 RUNNING 任务快照
     * @param lease 当前新获取的 Redis 租约
     * @param maxAttempts 最大尝试数
     * @return true 表示数据库 CAS 接管成功
     */
    @Transactional
    public boolean takeOverRunningAttempt(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            int maxAttempts
    ) {
        if (!canUseRedisTakeover(task, lease)) {
            return false;
        }
        String observedLeaseToken = normalizeText(task.getLeaseToken());
        String newLeaseToken = normalizeText(lease.token());
        if (observedLeaseToken.isBlank() || observedLeaseToken.equals(newLeaseToken)) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        int effectiveMaxAttempts = Math.max(1, maxAttempts);
        // 同时匹配旧 token、心跳截止时间和 attempts，上一个 owner 若仍活跃则 CAS 失败。
        int updated = repository.takeOverStaleRunningAttempt(
                task.getId(),
                observedLeaseToken,
                newLeaseToken,
                now.minus(leaseService.leaseTtl()),
                effectiveMaxAttempts,
                now
        );
        if (updated == 0) {
            return false;
        }

        task.setStatus(ResearchTask.Status.RUNNING);
        task.setAttempts(safeAttempts(task) + 1);
        task.setLeaseToken(newLeaseToken);
        task.setStartedAt(now);
        task.setCompletedAt(null);
        task.setHeartbeatAt(now);
        task.setUpdatedAt(now);
        task.setErrorMessage(null);
        return true;
    }

    /**
     * worker 已取得新 Redis 租约时，关闭达到尝试上限的 PENDING 或 stale RUNNING 任务。
     *
     * @param task 当前任务快照
     * @param lease worker 新租约
     * @param maxAttempts 最大尝试数
     * @param errorMessage 终止原因
     * @return true 表示条件更新成功
     */
    @Transactional
    public boolean failStaleRunningAtAttemptLimit(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            int maxAttempts,
            String errorMessage
    ) {
        if (task == null || task.getId() == null) {
            return false;
        }
        if (task.getStatus() == ResearchTask.Status.PENDING) {
            return markFailedIfPending(task, errorMessage);
        }
        if (!canUseRedisTakeover(task, lease)) {
            return false;
        }
        return failStaleRunningAtAttemptLimit(
                task,
                Math.max(1, maxAttempts),
                errorMessage,
                leaseService.leaseTtl()
        );
    }

    /**
     * 把超过心跳截止时间且仍是观察 token 的 RUNNING 任务重置为 PENDING。
     *
     * @param task stale 任务快照
     * @param staleAfter 心跳过期阈值
     * @param maxAttempts 最大尝试数
     * @param errorMessage 恢复原因
     * @return true 表示 CAS 重置成功
     */
    @Transactional
    public boolean resetStaleRunningForRetry(
            ResearchTask task,
            Duration staleAfter,
            int maxAttempts,
            String errorMessage
    ) {
        if (task == null || task.getId() == null || task.getStatus() != ResearchTask.Status.RUNNING) {
            return false;
        }
        String observedLeaseToken = normalizeText(task.getLeaseToken());
        if (observedLeaseToken.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        Duration effectiveStaleAfter = normalizePositiveDuration(staleAfter, leaseService.leaseTtl());
        String retryMessage = truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH);
        if (retryMessage.isBlank()) {
            retryMessage = RECOVERED_FOR_RETRY_MESSAGE;
        }
        int updated = repository.resetStaleRunningForRetry(
                task.getId(),
                observedLeaseToken,
                now.minus(effectiveStaleAfter),
                Math.max(1, maxAttempts),
                retryMessage,
                now
        );
        if (updated == 0) {
            return false;
        }
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(ResearchTask.Stage.CREATED);
        task.setLeaseToken(null);
        task.setErrorMessage(retryMessage);
        task.setCompletedAt(null);
        task.setHeartbeatAt(now);
        task.setUpdatedAt(now);
        return true;
    }

    /**
     * 关闭达到尝试上限的 stale RUNNING 任务。
     *
     * @param task stale 任务快照
     * @param staleAfter 心跳过期阈值
     * @param maxAttempts 最大尝试数
     * @param errorMessage 终止原因
     * @return true 表示 CAS 关闭成功
     */
    @Transactional
    public boolean failStaleRunningAtAttemptLimit(
            ResearchTask task,
            Duration staleAfter,
            int maxAttempts,
            String errorMessage
    ) {
        if (task == null || task.getId() == null || task.getStatus() != ResearchTask.Status.RUNNING) {
            return false;
        }
        return failStaleRunningAtAttemptLimit(
                task,
                Math.max(1, maxAttempts),
                errorMessage,
                normalizePositiveDuration(staleAfter, leaseService.leaseTtl())
        );
    }

    /**
     * 只在任务尚为 PENDING 时标记失败，避免启动失败处理覆盖并发 worker 的 RUNNING 状态。
     *
     * @param task 待关闭任务
     * @param errorMessage 失败原因
     * @return true 表示条件更新成功
     */
    @Transactional
    public boolean markFailedIfPending(ResearchTask task, String errorMessage) {
        if (task == null || task.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        String normalizedError = truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH);
        int updated = repository.failIfPending(task.getId(), normalizedError, now);
        if (updated == 0) {
            return false;
        }
        task.setStatus(ResearchTask.Status.FAILED);
        task.setStage(ResearchTask.Stage.FAILED);
        task.setErrorMessage(normalizedError);
        task.setCompletedAt(now);
        task.setHeartbeatAt(now);
        task.setLeaseToken(null);
        return true;
    }

    /**
     * 当前 owner 的执行异常后把 RUNNING 任务重置为 PENDING。
     *
     * @param task 当前任务
     * @param leaseToken 当前 owner token
     * @param errorMessage 本次执行错误
     * @return true 表示 owner-fenced 重置成功
     */
    @Transactional
    public boolean resetRunningForRetryForOwner(
            ResearchTask task,
            String leaseToken,
            String errorMessage
    ) {
        if (task == null || task.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        String retryMessage = truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH);
        if (retryMessage.isBlank()) {
            retryMessage = "research task attempt failed; pending retry";
        }
        int updated = repository.resetRunningForRetryForOwner(
                task.getId(),
                normalizeText(leaseToken),
                retryMessage,
                now
        );
        if (updated == 0) {
            return false;
        }
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(ResearchTask.Stage.CREATED);
        task.setLeaseToken(null);
        task.setErrorMessage(retryMessage);
        task.setCompletedAt(null);
        task.setHeartbeatAt(now);
        return true;
    }

    /**
     * 仅当前 owner 可推进任务阶段并刷新数据库心跳。
     *
     * @param task 当前任务
     * @param leaseToken 当前 owner token
     * @param stage 目标阶段
     * @throws IllegalStateException ownership 已改变时
     */
    @Transactional
    public void markStageForOwner(ResearchTask task, String leaseToken, ResearchTask.Stage stage) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("task id is required for owner-checked stage update");
        }
        // 调用带 status=RUNNING 和 lease_token 条件的阶段更新，形成数据库 owner fence。
        int updated = repository.advanceStageForOwner(
                task.getId(),
                normalizeText(leaseToken),
                stage == null ? task.getStage() : stage,
                LocalDateTime.now()
        );
        if (updated == 0) {
            throw new IllegalStateException("research task lost ownership before stage update");
        }
        task.setStage(stage == null ? task.getStage() : stage);
        task.setHeartbeatAt(LocalDateTime.now());
    }

    /**
     * 刷新仍属于当前 owner 的数据库心跳。
     *
     * @param task 当前任务
     * @param leaseToken 当前 owner token
     * @return true 表示任务仍为当前 owner 的 RUNNING 行
     */
    @Transactional
    public boolean heartbeatForOwner(ResearchTask task, String leaseToken) {
        if (task == null || task.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = repository.heartbeatForOwner(task.getId(), normalizeText(leaseToken), now);
        if (updated > 0) {
            task.setHeartbeatAt(now);
            return true;
        }
        return false;
    }

    /**
     * 以默认结果类型完成当前 owner 的任务。
     *
     * @param task 当前任务
     * @param leaseToken 当前 owner token
     * @param resultReportVersionId 报告版本 ID；null 表示证据不足
     */
    @Transactional
    public void markSucceededForOwner(ResearchTask task, String leaseToken, Long resultReportVersionId) {
        markSucceededForOwner(
                task,
                leaseToken,
                resultReportVersionId,
                resultReportVersionId == null
                        ? ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE
                        : ResearchTask.ResultKind.FULL_REPORT
        );
    }

    /**
     * 以显式结果类型将当前 owner 的任务原子置为 SUCCEEDED/COMPLETE。
     *
     * @param task 当前任务
     * @param leaseToken 当前 owner token
     * @param resultReportVersionId 可选报告版本 ID
     * @param resultKind FULL_REPORT、INSUFFICIENT_EVIDENCE、POLICY_BLOCKED 等结果类型
     * @throws IllegalStateException ownership 已改变时
     */
    @Transactional
    public void markSucceededForOwner(
            ResearchTask task,
            String leaseToken,
            Long resultReportVersionId,
            ResearchTask.ResultKind resultKind
    ) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("task id is required for owner-checked completion");
        }
        ResearchTask.ResultKind effectiveResultKind = resultKind == null
                ? ResearchTask.ResultKind.POLICY_BLOCKED
                : resultKind;
        // 终态条件 UPDATE 与报告发布事务组合，旧 owner 无法覆盖新 owner 或终态。
        int updated = repository.completeForOwner(
                task.getId(),
                normalizeText(leaseToken),
                resultReportVersionId,
                effectiveResultKind.name(),
                LocalDateTime.now()
        );
        if (updated == 0) {
            throw new IllegalStateException("research task lost ownership before completion");
        }
        task.setStatus(ResearchTask.Status.SUCCEEDED);
        task.setStage(ResearchTask.Stage.COMPLETE);
        task.setResultReportVersionId(resultReportVersionId);
        task.setResultKind(effectiveResultKind);
        task.setCompletedAt(LocalDateTime.now());
        task.setLeaseToken(null);
        task.setErrorMessage(null);
    }

    /**
     * 仅当前 owner 可将 RUNNING 任务标记为 FAILED。
     *
     * @param task 当前任务
     * @param leaseToken 当前 owner token
     * @param errorMessage 失败原因
     * @return true 表示 owner-fenced 更新成功
     */
    @Transactional
    public boolean markFailedForOwner(ResearchTask task, String leaseToken, String errorMessage) {
        if (task == null || task.getId() == null) {
            return false;
        }
        int updated = repository.failForOwner(
                task.getId(),
                normalizeText(leaseToken),
                truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH),
                LocalDateTime.now()
        );
        if (updated > 0) {
            task.setStatus(ResearchTask.Status.FAILED);
            task.setStage(ResearchTask.Stage.FAILED);
            task.setErrorMessage(truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH));
            task.setCompletedAt(LocalDateTime.now());
            task.setLeaseToken(null);
            return true;
        }
        return false;
    }

    /**
     * 扫描 stale RUNNING 任务：未达上限则恢复为 PENDING，达到上限则 FAILED。
     *
     * <p>同时回收上轮已标记 PENDING 但队列投递失败的任务 ID，供调度器再次入队。</p>
     *
     * @param staleAfter 心跳过期阈值
     * @param maxAttempts 最大尝试数
     * @return 重试数、失败数及需要入队的任务 ID
     */
    @Transactional
    public RecoveryResult recoverStaleRunningTasks(Duration staleAfter, int maxAttempts) {
        Duration effectiveStaleAfter = staleAfter == null ? Duration.ofMinutes(15) : staleAfter;
        int effectiveMaxAttempts = Math.max(1, maxAttempts);
        LocalDateTime cutoff = LocalDateTime.now().minus(effectiveStaleAfter);
        // 先查询候选，真正状态变化仍由下方包含旧 token/截止时间的条件 UPDATE 决定。
        List<ResearchTask> staleTasks = repository.findByStatusAndHeartbeatAtBefore(
                ResearchTask.Status.RUNNING,
                cutoff
        );
        List<ResearchTask> pendingRecoveredTasks = repository.findByStatusAndErrorMessage(
                ResearchTask.Status.PENDING,
                RECOVERED_FOR_RETRY_MESSAGE
        );

        LinkedHashSet<Long> retriedTaskIds = new LinkedHashSet<>();
        repository.findByStatusAndUpdatedAtBefore(ResearchTask.Status.PENDING, cutoff).stream()
                .map(ResearchTask::getId)
                .forEach(retriedTaskIds::add);
        if (pendingRecoveredTasks != null) {
            pendingRecoveredTasks.stream()
                    .map(ResearchTask::getId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(retriedTaskIds::add);
        }
        int failed = 0;
        for (ResearchTask task : staleTasks) {
            if (safeAttempts(task) >= effectiveMaxAttempts) {
                boolean closed = failStaleRunningAtAttemptLimit(
                        task,
                        effectiveStaleAfter,
                        effectiveMaxAttempts,
                        "stale research task exceeded attempt limit " + effectiveMaxAttempts
                );
                if (closed) {
                    failed++;
                }
            } else if (resetStaleRunningForRetry(
                    task,
                    effectiveStaleAfter,
                    effectiveMaxAttempts,
                    RECOVERED_FOR_RETRY_MESSAGE
            )) {
                if (task.getId() != null) {
                    retriedTaskIds.add(task.getId());
                }
            }
        }
        List<Long> ids = List.copyOf(retriedTaskIds);
        return new RecoveryResult(ids.size(), failed, ids);
    }

    /**
     * 构造不含会话维度的 DEEP 提交幂等键。
     *
     * @return SHA-256 派生的稳定键
     */
    public String buildSubmissionKey(String userId, String ticker, String userQuery) {
        return buildSubmissionKey(userId, ticker, userQuery, null);
    }

    /**
     * 由用户、会话、ticker 和归一化问题构造 DEEP 提交幂等键。
     *
     * @param userId 用户 ID
     * @param ticker 目标 ticker
     * @param userQuery 原始问题
     * @param conversationId 可选会话 ID
     * @return SHA-256 派生的稳定键
     */
    public String buildSubmissionKey(
            String userId,
            String ticker,
            String userQuery,
            Long conversationId
    ) {
        String normalizedQuery = normalizeText(userQuery)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return "deep-submit:" + sha256(String.join("|",
                normalizeText(userId),
                conversationId == null ? "no-conversation" : conversationId.toString(),
                normalizeTicker(ticker),
                normalizedQuery));
    }

    /**
     * 统计用户 PENDING 与 RUNNING 任务，用于提交配额。
     *
     * @param userId 用户 ID
     * @return 活动任务数
     */
    public int countActiveTasks(String userId) {
        return (int) repository.countByUserIdAndStatusIn(normalizeText(userId),
                List.of(ResearchTask.Status.PENDING, ResearchTask.Status.RUNNING));
    }

    /**
     * 构造后台 worker 可恢复的提交载荷 JSON。
     *
     * @param ticker 目标 ticker
     * @param query 用户问题
     * @param traceId 链路 ID
     * @param conversationId 可选会话 ID
     * @return JSON 载荷
     */
    public String buildSubmissionPayload(String ticker, String query, String traceId, Long conversationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticker", normalizeTicker(ticker));
        payload.put("query", normalizeText(query));
        payload.put("traceId", normalizeText(traceId));
        payload.put("conversationId", conversationId);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize submission payload", e);
        }
    }

    /**
     * 把 SUCCEEDED/FAILED 终态任务重置为新一轮 PENDING，并同事务清理旧检查点。
     *
     * @param task 终态任务
     * @param payloadJson 新提交载荷
     * @return 当前任务及本次 CAS 是否重置成功
     */
    @Transactional
    public TaskReset resetForResubmission(ResearchTask task, String payloadJson) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("persisted research task is required for resubmission");
        }
        String normalizedPayload = normalizePayload(payloadJson);
        Long conversationId = submissionConversationId(normalizedPayload, task.getConversationId());
        LocalDateTime now = LocalDateTime.now();
        int updated = repository.resetTerminalForResubmission(
                task.getId(), conversationId, normalizedPayload, now);
        if (updated == 0) {
            ResearchTask current = repository.findById(task.getId()).orElse(task);
            return new TaskReset(current, false);
        }
        // 终态 CAS 与旧 checkpoint 删除同事务，防止新尝试恢复上一次 REPORT_SYNTHESIS。
        checkpointRepository.deleteByTaskId(task.getId());
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(ResearchTask.Stage.CREATED);
        task.setAttempts(0);
        task.setConversationId(conversationId);
        task.setLeaseToken(null);
        task.setPayloadJson(normalizedPayload);
        task.setErrorMessage(null);
        task.setResultReportVersionId(null);
        task.setResultKind(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setHeartbeatAt(now);
        return new TaskReset(task, true);
    }

    /** 从新提交载荷读取 conversationId，损坏或缺失时保留任务原值。 */
    private Long submissionConversationId(String payloadJson, Long fallback) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            return root.path("conversationId").canConvertToLong()
                    ? root.path("conversationId").longValue()
                    : fallback;
        } catch (JsonProcessingException error) {
            return fallback;
        }
    }

    /** 使用已观察 token 和心跳截止时间 CAS 关闭 stale RUNNING 任务。 */
    private boolean failStaleRunningAtAttemptLimit(
            ResearchTask task,
            int maxAttempts,
            String errorMessage,
            Duration staleAfter
    ) {
        String observedLeaseToken = normalizeText(task.getLeaseToken());
        if (observedLeaseToken.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        String normalizedError = truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH);
        if (normalizedError.isBlank()) {
            normalizedError = "stale research task exceeded attempt limit " + maxAttempts;
        }
        int updated = repository.failStaleRunningAtAttemptLimit(
                task.getId(),
                observedLeaseToken,
                now.minus(staleAfter),
                maxAttempts,
                normalizedError,
                now
        );
        if (updated == 0) {
            return false;
        }
        task.setStatus(ResearchTask.Status.FAILED);
        task.setStage(ResearchTask.Stage.FAILED);
        task.setErrorMessage(normalizedError);
        task.setCompletedAt(now);
        task.setHeartbeatAt(now);
        task.setUpdatedAt(now);
        task.setLeaseToken(null);
        return true;
    }

    /** 只有新 Redis 租约与同一幂等键匹配时才允许跨 owner 接管。 */
    private boolean canUseRedisTakeover(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease
    ) {
        if (task == null
                || task.getId() == null
                || task.getStatus() != ResearchTask.Status.RUNNING
                || lease == null
                || lease.backend() != ResearchTaskLeaseService.Backend.REDIS) {
            return false;
        }
        return !normalizeText(lease.token()).isBlank()
                && normalizeText(task.getIdempotencyKey()).equals(normalizeText(lease.idempotencyKey()));
    }

    /** 将 null、零或负时长替换为安全默认值。 */
    private Duration normalizePositiveDuration(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value;
    }

    /** 将可空 attempts 视为零。 */
    private int safeAttempts(ResearchTask task) {
        return task.getAttempts() == null ? 0 : task.getAttempts();
    }

    /** 空载荷统一保存为空 JSON 对象。 */
    private String normalizePayload(String payloadJson) {
        String normalized = normalizeText(payloadJson);
        return normalized.isBlank() ? "{}" : normalized;
    }

    /** 归一化 ticker；空值使用 UNKNOWN 保持任务键可构造。 */
    private String normalizeTicker(String value) {
        String normalized = normalizeText(value)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9.]", "");
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    /** null 安全去除首尾空白。 */
    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    /** 生成 UTF-8 文本的十六进制 SHA-256。 */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    /** 无后缀截断数据库错误文本。 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** @param created true 表示本次插入，false 表示复用现有幂等任务。 */
    public record TaskCreation(ResearchTask task, boolean created) {
    }

    /** @param reset true 表示终态 CAS 已成功重置。 */
    public record TaskReset(ResearchTask task, boolean reset) {
    }

    /**
     * 一轮 stale 恢复汇总。
     *
     * @param retried 需要重入队的任务数
     * @param failed 达到上限并关闭的任务数
     * @param retriedTaskIds 需要写回队列的任务 ID
     */
    public record RecoveryResult(int retried, int failed, List<Long> retriedTaskIds) {

        public RecoveryResult {
            retriedTaskIds = retriedTaskIds == null ? List.of() : List.copyOf(retriedTaskIds);
        }
    }
}
