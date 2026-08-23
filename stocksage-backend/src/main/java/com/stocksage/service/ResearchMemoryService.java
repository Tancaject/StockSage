package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchMemoryConflictGroup;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryEntry;
import com.stocksage.repository.InvestmentReportVersionRepository;
import com.stocksage.repository.ResearchMemoryConflictGroupRepository;
import com.stocksage.repository.ResearchMemoryEntryRepository;
import com.stocksage.trace.TraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 从已验证投资报告捕获、索引、检索和撤销用户级跨会话研究记忆。
 *
 * <p>{@link InvestmentReportVersionService} 成功提交后发布事件，本服务在 AFTER_COMMIT 的独立事务中
 * 创建 MySQL 记忆行，再在事务提交后调用 {@link ResearchMemoryVectorIndex} 写 Milvus。检索先做
 * tenant/ticker 向量搜索，再回查“未撤销且 INDEXED”的数据库真源，避免孤儿向量进入提示词。</p>
 *
 * <p>边界：历史记忆明确标注可能过期，只作当前证据的补充；捕获、索引和检索失败均不回滚主报告。</p>
 */
@Slf4j
@Service
public class ResearchMemoryService {

    /** 记忆来源类型，sourceId 对应 InvestmentReportVersion 主键。 */
    private static final String SOURCE_TYPE = "INVESTMENT_REPORT_VERSION";
    /** 当前第一版只对结构化投资建议进行冲突消解。 */
    private static final String CONFLICT_KIND = "REPORT_RECOMMENDATION";
    /** 负向人工审核撤销可在报告重新进入审核后恢复。 */
    private static final String REVOKED_BY_NEGATIVE_REVIEW = "REVOKED_BY_NEGATIVE_REVIEW";
    /** 用户主动撤销必须保持永久，不能被后续审核状态变化复活。 */
    private static final String REVOKED_BY_USER = "REVOKED_BY_USER";
    /** 补偿任务可尝试索引的数据库状态。 */
    private static final List<ResearchMemoryEntry.VectorStatus> INDEXABLE_VECTOR_STATUSES = List.of(
            ResearchMemoryEntry.VectorStatus.PENDING,
            ResearchMemoryEntry.VectorStatus.FAILED
    );
    /** 向 INDEXED 转换时允许的旧状态；包含 INDEXED 以支持幂等重放。 */
    private static final List<ResearchMemoryEntry.VectorStatus> SUCCESS_TRANSITION_STATUSES = List.of(
            ResearchMemoryEntry.VectorStatus.PENDING,
            ResearchMemoryEntry.VectorStatus.FAILED,
            ResearchMemoryEntry.VectorStatus.INDEXED
    );
    /** 记忆行的数据库真源和 guarded vector 状态更新。 */
    private final ResearchMemoryEntryRepository repository;
    /** 保存冲突组赢家并为并发捕获/审核/撤销提供稳定锁点。 */
    private final ResearchMemoryConflictGroupRepository conflictGroupRepository;
    /** 捕获前锁定并重读报告当前审核状态。 */
    private final InvestmentReportVersionRepository reportVersionRepository;
    /** 执行 Milvus 向量写入、检索和删除。 */
    private final ResearchMemoryVectorIndex vectorIndex;
    /** 控制 capture/index/retrieve/inject 四个独立开关与上限。 */
    private final ResearchMemoryProperties properties;
    /** 序列化来源引用并读取统计。 */
    private final ObjectMapper objectMapper;
    /** 把检索命中和注入状态写入当前 Agent Trace。 */
    private final TraceService traceService;
    /** 纯 Java 确定性冲突解析器，不访问数据库或模型。 */
    private final ResearchMemoryConflictResolver conflictResolver;
    /** 对真源和冲突门禁后的候选执行时间衰减与自适应截断。 */
    private final ResearchMemoryRanker ranker;
    /** 为衰减、阻断和审计时间提供统一时钟。 */
    private final Clock clock;
    /** AFTER_COMMIT 阶段用于新事务捕获或更新 vector 状态。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建研究记忆服务，并把补充数据库工作配置为 REQUIRES_NEW。
     *
     * @param repository 研究记忆仓储
     * @param reportVersionRepository 报告版本仓储
     * @param vectorIndex Milvus 索引适配器
     * @param properties 功能开关和限制
     * @param objectMapper JSON 解析器
     * @param traceService Trace 服务
     * @param transactionManager Spring 事务管理器
     */
    @Autowired
    public ResearchMemoryService(
            ResearchMemoryEntryRepository repository,
            ResearchMemoryConflictGroupRepository conflictGroupRepository,
            InvestmentReportVersionRepository reportVersionRepository,
            ResearchMemoryVectorIndex vectorIndex,
            ResearchMemoryProperties properties,
            ObjectMapper objectMapper,
            TraceService traceService,
            PlatformTransactionManager transactionManager
    ) {
        this(repository, conflictGroupRepository, reportVersionRepository, vectorIndex, properties,
                objectMapper, traceService, transactionManager, Clock.systemDefaultZone());
    }

    /** 允许定向测试注入固定时钟；生产构造器使用系统默认时区。 */
    ResearchMemoryService(
            ResearchMemoryEntryRepository repository,
            ResearchMemoryConflictGroupRepository conflictGroupRepository,
            InvestmentReportVersionRepository reportVersionRepository,
            ResearchMemoryVectorIndex vectorIndex,
            ResearchMemoryProperties properties,
            ObjectMapper objectMapper,
            TraceService traceService,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.repository = repository;
        this.conflictGroupRepository = conflictGroupRepository;
        this.reportVersionRepository = reportVersionRepository;
        this.vectorIndex = vectorIndex;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.clock = clock;
        this.conflictResolver = new ResearchMemoryConflictResolver();
        this.ranker = new ResearchMemoryRanker(clock, properties);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 报告事务提交后尽力捕获研究记忆。
     *
     * @param event 已持久化报告事件
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void onReportPersisted(InvestmentReportPersistedEvent event) {
        if (!properties.isCapture() || event == null) {
            return;
        }
        try {
            // self-invocation 不会触发 capture 的事务代理，因此显式开启新事务并在此处兜住提交失败。
            transactionTemplate.executeWithoutResult(
                    status -> capture(event.source(), event.report()));
        } catch (Exception error) {
            // 报告已提交，记忆捕获仅尽力而为，失败不能回滚报告。
            log.warn("Research memory capture failed. errorType={}", error.getClass().getSimpleName());
        }
    }

    /**
     * 从符合质量、证据和审核条件的报告创建一条幂等记忆行。
     *
     * @param source 报告版本快照；方法会加锁重读当前行
     * @param report 结构化报告
     * @return 新建或已存在的记忆；不符合捕获条件时为 null
     */
    @Transactional
    public ResearchMemoryEntry capture(InvestmentReportVersion source, InvestmentReport report) {
        // 调用报告仓储加行锁重读，防止并发负向审核后仍捕获已被否决的报告。
        InvestmentReportVersion currentSource = lockCurrentSource(source);
        if (!eligible(currentSource, report)) {
            return null;
        }
        String userId = currentSource.getUserId().trim();
        String sourceId = String.valueOf(currentSource.getId());
        String ticker = normalizeTicker(currentSource.getTicker());
        AnalysisHorizon horizon = effectiveHorizon(report.getAnalysisHorizon());
        String recommendation = normalizeRecommendation(report.getRecommendation());
        String conflictKey = conflictKey(ticker, horizon);
        ResearchMemoryConflictGroup conflictGroup = conflictGroupRepository.ensureAndLock(
                userId, conflictKey, LocalDateTime.now(clock));
        var existing = repository.findByUserIdAndSourceTypeAndSourceId(
                userId, SOURCE_TYPE, sourceId);
        if (existing.isPresent()) {
            ResearchMemoryEntry entry = existing.get();
            if (!Objects.equals(entry.getAnalysisHorizon(), horizon)
                    || !Objects.equals(entry.getRecommendation(), recommendation)
                    || !Objects.equals(entry.getConflictKey(), conflictKey)
                    || entry.getResolutionStatus() == null) {
                syncConflictFields(entry, horizon, recommendation, conflictKey);
                repository.saveAndFlush(entry);
            }
            reconcileLockedGroup(conflictGroup);
            return entry;
        }

        List<String> citations = sourceCitations(report);
        String text = buildMemoryText(currentSource, report, citations);
        ResearchMemoryEntry entry = new ResearchMemoryEntry();
        entry.setUserId(userId);
        entry.setTicker(ticker);
        entry.setAnalysisHorizon(horizon);
        entry.setRecommendation(recommendation);
        entry.setConflictKey(conflictKey);
        entry.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.CURRENT);
        entry.setSourceType(SOURCE_TYPE);
        entry.setSourceId(sourceId);
        entry.setSourceConversationId(currentSource.getConversationId());
        entry.setSourceCitations(writeJson(citations));
        entry.setDataCutoffAt(currentSource.getGeneratedAt());
        entry.setSnapshotHash(currentSource.getDataSnapshotHash());
        entry.setContentHash(sha256(text));
        entry.setMemoryText(text);
        entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.PENDING);
        try {
            // 先提交 PENDING 数据库真源；同一报告来源由唯一键保证只捕获一次。
            entry = repository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException duplicate) {
            entry = repository.findByUserIdAndSourceTypeAndSourceId(
                    userId, SOURCE_TYPE, sourceId).orElseThrow(() -> duplicate);
        }
        reconcileLockedGroup(conflictGroup);
        if (properties.isIndex()) {
            indexAfterCommit(entry);
        }
        return entry;
    }

    /** 定时重试 PENDING/FAILED 的向量索引，每轮最多处理 10 条。 */
    @Scheduled(
            fixedDelayString = "${stocksage.research-memory.compensation-delay-ms:60000}",
            initialDelayString = "${stocksage.research-memory.compensation-initial-delay-ms:60000}"
    )
    public void compensateIndex() {
        if (!properties.isIndex()) {
            return;
        }
        repository.findByVectorStatusInOrderByUpdatedAtAsc(
                        List.of(ResearchMemoryEntry.VectorStatus.PENDING,
                                ResearchMemoryEntry.VectorStatus.FAILED),
                        PageRequest.of(0, 10))
                .forEach(this::indexOne);
    }

    /** 兼容旧调用方；未提供意图元数据时使用 STANDARD/UNSPECIFIED 对应的默认数量。 */
    public RetrievalResult retrieve(String userId, String ticker, String query, String traceId) {
        return retrieve(new ResearchMemoryQuery(
                userId, ticker, query, traceId, null, null));
    }

    /**
     * 以“30 条初始候选 -> MySQL 真源 -> 冲突赢家 -> 衰减 -> 12 -> 4/6/8”检索研究记忆。
     *
     * <p>冲突门禁和时间衰减是唯一检索链路。任何阶段失败都返回空记忆，不阻断主回答。</p>
     */
    public RetrievalResult retrieve(ResearchMemoryQuery query) {
        if (!properties.isRetrieve() || query == null
                || query.userId().isBlank() || query.query().isBlank()) {
            return RetrievalResult.empty();
        }
        long startedAt = System.nanoTime();
        try {
            List<ResearchMemoryVectorIndex.Hit> hits = vectorIndex.search(
                    query.userId(), query.ticker(), query.query(), properties.getCandidateK());
            Map<Long, Double> scores = new LinkedHashMap<>();
            hits.forEach(hit -> scores.merge(hit.entryId(), hit.score(), Math::max));
            if (scores.isEmpty()) {
                traceRetrieval(query, 0, 0, 0, 0,
                        List.of(), 0, elapsedMs(startedAt));
                return RetrievalResult.empty();
            }

            List<ResearchMemoryEntry> truthEntries = repository
                    .findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                            scores.keySet(), query.userId(), ResearchMemoryEntry.VectorStatus.INDEXED)
                    .stream()
                    .filter(ResearchMemoryEntry::active)
                    .filter(entry -> entry.getVectorStatus() == ResearchMemoryEntry.VectorStatus.INDEXED)
                    .toList();
            ConflictGateResult conflictGate = conflictGate(query.userId(), truthEntries);
            List<ResearchMemoryRanker.ScoredMemory> ranked = ranker.rank(
                    query, conflictGate.currentWinners(), scores);
            List<ResearchMemoryEntry> selected = ranked.stream()
                    .map(ResearchMemoryRanker.ScoredMemory::entry)
                    .toList();

            Map<Long, ResearchMemoryRanker.ScoredMemory> rankedById = ranked.stream()
                    .collect(Collectors.toMap(
                            scored -> scored.entry().getId(),
                            Function.identity(),
                            (left, right) -> left,
                            LinkedHashMap::new));
            boolean includeConflictWarning = conflictGate.unresolvedGroupCount() > 0;
            PromptResult prompt = properties.isInject()
                    ? buildPrompt(selected, rankedById, includeConflictWarning)
                    : PromptResult.empty();
            boolean injected = !prompt.text().isBlank();

            traceRetrieval(
                    query,
                    hits.size(),
                    truthEntries.size(),
                    conflictGate.currentWinners().size(),
                    conflictGate.unresolvedGroupCount(),
                    ranked,
                    prompt.text().length(),
                    elapsedMs(startedAt));
            if (selected.isEmpty() && !injected) {
                return RetrievalResult.empty();
            }
            int visibleHitCount = properties.isInject()
                    ? prompt.includedCount() : selected.size();
            return new RetrievalResult(prompt.text(), visibleHitCount, injected);
        } catch (Exception error) {
            log.warn("Research memory retrieval failed. errorType={}",
                    error.getClass().getSimpleName());
            return RetrievalResult.empty();
        }
    }

    /**
     * 列出用户未撤销的研究记忆。
     *
     * @param userId 当前用户 ID
     * @param limit 最大条数，限制在 1 至 50
     * @return 新到旧的记忆视图
     */
    @Transactional(readOnly = true)
    public List<MemoryView> listForUser(String userId, int limit) {
        int capped = Math.max(1, Math.min(50, limit));
        return repository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(
                        userId.trim(), PageRequest.of(0, capped))
                .stream().map(this::toView).toList();
    }

    /**
     * 按用户归属撤销一条研究记忆，并在提交后删除向量。
     *
     * @param userId 当前用户 ID
     * @param id 记忆 ID
     * @return true 表示本次从 active 变为 revoked
     */
    @Transactional
    public boolean revoke(String userId, Long id) {
        if (userId == null || userId.isBlank() || id == null) {
            return false;
        }
        ResearchMemoryEntry entry = repository.findByIdAndUserId(id, userId.trim()).orElse(null);
        return revokeEntry(entry, true);
    }

    /**
     * 撤销指定报告版本派生的研究记忆，供负向人工审核调用。
     *
     * @param source 报告版本实体
     * @return true 表示找到并撤销 active 记忆
     */
    @Transactional
    public boolean revokeForReport(InvestmentReportVersion source) {
        InvestmentReportVersion currentSource = lockCurrentSource(source);
        if (currentSource == null || !isNegativeReview(currentSource.getReviewStatus())) {
            return false;
        }
        ResearchMemoryEntry entry = repository.findByUserIdAndSourceTypeAndSourceId(
                currentSource.getUserId().trim(),
                SOURCE_TYPE,
                String.valueOf(currentSource.getId())
        ).orElse(null);
        return revokeEntry(entry, false);
    }

    /** 审核通过或重新进入审核时，仅恢复负向审核撤销的条目并重新计算冲突组。 */
    @Transactional
    public boolean reconcileForReport(InvestmentReportVersion source) {
        InvestmentReportVersion currentSource = lockCurrentSource(source);
        if (currentSource == null || isNegativeReview(currentSource.getReviewStatus())) {
            return false;
        }
        ResearchMemoryEntry entry = repository.findByUserIdAndSourceTypeAndSourceId(
                currentSource.getUserId().trim(), SOURCE_TYPE, String.valueOf(currentSource.getId()))
                .orElse(null);
        if (entry == null || entry.getConflictKey() == null || entry.getConflictKey().isBlank()) {
            return false;
        }
        ResearchMemoryConflictGroup group = conflictGroupRepository.ensureAndLock(
                entry.getUserId(), entry.getConflictKey(), LocalDateTime.now(clock));
        Long entryId = entry.getId();
        entry = repository.findConflictCandidatesForUpdate(
                        entry.getUserId(), entry.getConflictKey())
                .stream()
                .filter(candidate -> candidate.getId().equals(entryId))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return false;
        }
        boolean restoredFromNegativeReview = entry.getRevokedAt() != null
                && REVOKED_BY_NEGATIVE_REVIEW.equals(entry.getResolutionReason());
        if (restoredFromNegativeReview) {
            entry.setRevokedAt(null);
            entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.PENDING);
            entry.setVectorErrorCode(null);
            entry.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.CURRENT);
            entry.setSupersededById(null);
            entry.setSupersededAt(null);
            entry.setResolutionReason("RESTORED_BY_REVIEW");
            repository.saveAndFlush(entry);
        }
        reconcileLockedGroup(group);
        if (restoredFromNegativeReview && properties.isIndex()) {
            indexAfterCommit(entry);
        }
        return true;
    }

    /** 先提交数据库 REVOKED 真源并重选冲突组，再安排非事务性向量删除。 */
    private boolean revokeEntry(ResearchMemoryEntry observedEntry, boolean explicitUserRevoke) {
        if (observedEntry == null || !observedEntry.active()) {
            return false;
        }
        ResearchMemoryEntry entry = observedEntry;
        ResearchMemoryConflictGroup group = null;
        if (entry.getConflictKey() != null && !entry.getConflictKey().isBlank()) {
            group = conflictGroupRepository.ensureAndLock(
                    entry.getUserId(), entry.getConflictKey(), LocalDateTime.now(clock));
            entry = repository.findConflictCandidatesForUpdate(
                            entry.getUserId(), entry.getConflictKey())
                    .stream()
                    .filter(candidate -> candidate.getId().equals(observedEntry.getId()))
                    .findFirst()
                    .orElse(null);
            if (entry == null || !entry.active()) {
                return false;
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (entry == null || !entry.active()) {
            return false;
        }
        boolean revokedCurrentWinner = group != null
                && entry.getId().equals(group.getWinnerEntryId());
        entry.setRevokedAt(now);
        entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.REVOKED);
        entry.setVectorErrorCode(null);
        entry.setResolutionReason(explicitUserRevoke
                ? REVOKED_BY_USER : REVOKED_BY_NEGATIVE_REVIEW);
        // Flush the database truth before deleting the vector. A concurrent index CAS must either
        // finish first (then this delete removes its vector) or observe REVOKED and clean up its
        // own just-written vector after the guarded update is rejected.
        repository.saveAndFlush(entry);
        if (group != null) {
            if (explicitUserRevoke && revokedCurrentWinner) {
                group.setBlockedBeforeAt(now);
            }
            reconcileLockedGroup(group);
        }
        // 负向审核允许重新进入审核，不能让迟到的旧删除覆盖恢复后的同 ID 向量。
        // 用户主动撤销不可恢复，仍在数据库提交后尽力删除物理向量。
        if (explicitUserRevoke) {
            deleteVectorAfterCommit(entry.getId(), "entry revoked by user");
        }
        return true;
    }

    /**
     * 写入单条向量，并用数据库状态 CAS 把 PENDING/FAILED 转为 INDEXED 或 FAILED。
     *
     * <p>向量成功但 CAS 失败时仍由 MySQL 状态阻断可见性；只有用户永久撤销才清理固定 ID，
     * 避免迟到清理删除负向审核恢复后的新向量。</p>
     */
    private void indexOne(ResearchMemoryEntry entry) {
        if (entry == null || entry.getId() == null || !entry.active()
                || repository.countByIdAndRevokedAtIsNullAndVectorStatusIn(
                entry.getId(), INDEXABLE_VECTOR_STATUSES) == 0) {
            return;
        }
        ResearchMemoryEntry.VectorStatus targetStatus;
        String errorCode;
        boolean vectorWritten = false;
        try {
            // 调用 Milvus 写确定性 vectorId；后续数据库 CAS 才决定该向量是否可检索。
            vectorIndex.index(entry);
            vectorWritten = true;
            targetStatus = ResearchMemoryEntry.VectorStatus.INDEXED;
            errorCode = null;
        } catch (Exception error) {
            targetStatus = ResearchMemoryEntry.VectorStatus.FAILED;
            errorCode = "VECTOR_INDEX_FAILED";
        }
        int updated;
        try {
            updated = repository.updateVectorStateIfIndexable(
                    entry.getId(),
                    targetStatus == ResearchMemoryEntry.VectorStatus.INDEXED
                            ? SUCCESS_TRANSITION_STATUSES : INDEXABLE_VECTOR_STATUSES,
                    targetStatus,
                    errorCode,
                    LocalDateTime.now(clock)
            );
        } catch (Exception error) {
            // MySQL 状态仍是唯一可见性真源。数据库不可读时保留孤儿向量，避免一次旧清理
            // 竞态删除已恢复的同 ID 新向量；PENDING/FAILED 补偿会覆盖它。
            log.warn("Research memory vector state update failed. entryId={}, errorType={}",
                    entry.getId(), error.getClass().getSimpleName());
            return;
        }
        if (updated > 0) {
            // Only mirror a successful guarded transition. A rejected stale candidate is never
            // mutated or merged back over the authoritative revoked row.
            entry.setVectorStatus(targetStatus);
            entry.setVectorErrorCode(errorCode);
            return;
        }
        if (vectorWritten && permanentlyRevokedByUser(entry.getId())) {
            deleteVectorBestEffort(entry.getId(), "post-index CAS rejected after user revoke");
        }
    }

    /** 只有不可恢复的用户撤销允许迟到索引任务删除固定 ID 向量。 */
    private boolean permanentlyRevokedByUser(Long entryId) {
        try {
            return repository.findById(entryId)
                    .filter(entry -> entry.getRevokedAt() != null)
                    .map(ResearchMemoryEntry::getResolutionReason)
                    .map(reason -> REVOKED_BY_USER.equals(reason)
                            || "BLOCKED_BY_USER_REVOKE".equals(reason))
                    .orElse(false);
        } catch (Exception error) {
            return false;
        }
    }

    /** 尽力删除 Milvus 向量；检索回查数据库可继续隔离删除失败的孤儿向量。 */
    private void deleteVectorBestEffort(Long entryId, String reason) {
        try {
            vectorIndex.delete(entryId);
        } catch (Exception error) {
            // Retrieval always joins vector hits back to active INDEXED database truth.
            log.warn("Research memory vector delete failed. entryId={}, reason={}, errorType={}",
                    entryId, reason, error.getClass().getSimpleName());
        }
    }

    /** 仅在撤销事务提交成功后执行非事务性向量删除。 */
    private void deleteVectorAfterCommit(Long entryId, String reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            deleteVectorBestEffort(entryId, reason);
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // A Spring-managed @Transactional call always has synchronization. Failing closed here
            // avoids deleting a vector for a database transaction that can still roll back.
            log.warn("Research memory vector delete deferred without transaction synchronization. "
                    + "entryId={}, reason={}", entryId, reason);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteVectorBestEffort(entryId, reason);
            }
        });
    }

    /** 仅在 PENDING 记忆行提交后索引，并用独立事务提交 vector 状态 CAS。 */
    private void indexAfterCommit(ResearchMemoryEntry entry) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            indexOne(entry);
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // The committed PENDING row is recoverable by compensateIndex(). Do not risk an
            // orphan vector while the surrounding database transaction may still roll back.
            log.warn("Research memory vector index deferred without transaction synchronization. "
                    + "entryId={}", entry == null ? null : entry.getId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // afterCommit still runs while the completed transaction's resources are
                    // thread-bound. Start an independent transaction so the vector-state CAS is
                    // durably committed instead of accidentally joining that completed context.
                    transactionTemplate.executeWithoutResult(status -> indexOne(entry));
                } catch (Exception error) {
                    // The committed PENDING row remains discoverable by compensateIndex().
                    log.warn("Research memory post-commit vector index failed. entryId={}, errorType={}",
                            entry == null ? null : entry.getId(),
                            error.getClass().getSimpleName());
                }
            }
        });
    }

    /** 按报告 ID 与 userId 加锁重读当前审核状态。 */
    private InvestmentReportVersion lockCurrentSource(InvestmentReportVersion source) {
        if (source == null || source.getId() == null
                || source.getUserId() == null || source.getUserId().isBlank()) {
            return null;
        }
        return reportVersionRepository.findByIdAndUserIdForUpdate(
                source.getId(),
                source.getUserId().trim()
        ).orElse(null);
    }

    /** 负向人工审核不能参与冲突选举或恢复向量。 */
    private boolean isNegativeReview(InvestmentReportVersion.ReviewStatus reviewStatus) {
        return reviewStatus == InvestmentReportVersion.ReviewStatus.REJECTED
                || reviewStatus == InvestmentReportVersion.ReviewStatus.NEEDS_RESEARCH;
    }

    /** 将新旧报告都收敛到稳定期限枚举。 */
    private AnalysisHorizon effectiveHorizon(AnalysisHorizon horizon) {
        return horizon == null ? AnalysisHorizon.UNSPECIFIED : horizon;
    }

    /** 将报告建议收敛为冲突解析器使用的稳定大写值。 */
    private String normalizeRecommendation(String recommendation) {
        if (recommendation == null) {
            return null;
        }
        String normalized = recommendation.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BUY", "OVERWEIGHT", "HOLD", "UNDERWEIGHT", "SELL" -> normalized;
            default -> null;
        };
    }

    /** 当前只为结构化投资建议建立冲突组，不把自由文本或来源 ID 放进键。 */
    private String conflictKey(String ticker, AnalysisHorizon horizon) {
        return String.join("|", CONFLICT_KIND, normalizeTicker(ticker), effectiveHorizon(horizon).name());
    }

    /** 幂等重放时补齐当前冲突字段，不改变来源正文和向量生命周期。 */
    private void syncConflictFields(
            ResearchMemoryEntry entry,
            AnalysisHorizon horizon,
            String recommendation,
            String conflictKey
    ) {
        entry.setAnalysisHorizon(effectiveHorizon(horizon));
        entry.setRecommendation(recommendation);
        entry.setConflictKey(conflictKey);
        if (entry.getResolutionStatus() == null) {
            entry.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.CURRENT);
        }
    }

    /**
     * 在已经锁定冲突组行后，按 ID 锁定候选并把确定性解析结果写回 MySQL。
     */
    private void reconcileLockedGroup(ResearchMemoryConflictGroup group) {
        List<ResearchMemoryEntry> candidates = repository.findConflictCandidatesForUpdate(
                group.getUserId(), group.getConflictKey());
        LocalDateTime now = LocalDateTime.now(clock);
        if (candidates.isEmpty()) {
            group.setWinnerEntryId(null);
            group.setResolutionStatus(group.getBlockedBeforeAt() == null
                    ? ResearchMemoryConflictGroup.ResolutionStatus.NO_ELIGIBLE_CANDIDATE
                    : ResearchMemoryConflictGroup.ResolutionStatus.BLOCKED);
            conflictGroupRepository.saveAndFlush(group);
            return;
        }

        Map<Long, InvestmentReportVersion> sources = sourceReports(group.getUserId(), candidates);
        LocalDateTime blockedBefore = group.getBlockedBeforeAt();
        List<ResearchMemoryEntry> unblocked = candidates.stream()
                .filter(entry -> blockedBefore == null
                        || effectiveReferenceAt(entry) == null
                        || effectiveReferenceAt(entry).isAfter(blockedBefore))
                .toList();
        List<ResearchMemoryConflictResolver.Candidate> resolverCandidates = unblocked.stream()
                .map(entry -> conflictCandidate(entry, sources.get(sourceId(entry))))
                .toList();
        ResearchMemoryConflictResolver.Resolution resolution = conflictResolver.resolve(resolverCandidates);

        boolean newerEvidenceExists = blockedBefore != null
                && resolution.groupStatus()
                != ResearchMemoryConflictResolver.GroupStatus.NO_ELIGIBLE_CANDIDATE;
        if (newerEvidenceExists) {
            group.setBlockedBeforeAt(null);
        }
        Map<Long, ResearchMemoryConflictResolver.EntryDecision> decisions = resolution.entryDecisions()
                .stream()
                .collect(Collectors.toMap(
                        ResearchMemoryConflictResolver.EntryDecision::entryId,
                        Function.identity()));
        Long winnerId = resolution.winnerId();
        for (ResearchMemoryEntry entry : candidates) {
            ResearchMemoryConflictResolver.EntryDecision decision = decisions.get(entry.getId());
            if (decision == null) {
                applyBlockedDecision(entry, now);
            } else {
                applyResolutionDecision(entry, decision, winnerId, now);
            }
        }
        repository.saveAll(candidates);
        repository.flush();

        if (group.getBlockedBeforeAt() != null
                && resolution.groupStatus()
                == ResearchMemoryConflictResolver.GroupStatus.NO_ELIGIBLE_CANDIDATE) {
            group.setResolutionStatus(ResearchMemoryConflictGroup.ResolutionStatus.BLOCKED);
            group.setWinnerEntryId(null);
        } else {
            group.setResolutionStatus(groupStatus(resolution.groupStatus()));
            group.setWinnerEntryId(winnerId);
        }
        conflictGroupRepository.saveAndFlush(group);
    }

    /** 批量读取组内来源报告当前审核状态；缺失来源按 DRAFT 兼容。 */
    private Map<Long, InvestmentReportVersion> sourceReports(
            String userId,
            List<ResearchMemoryEntry> candidates
    ) {
        Set<Long> sourceIds = candidates.stream()
                .filter(entry -> SOURCE_TYPE.equals(entry.getSourceType()))
                .map(this::sourceId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        List<InvestmentReportVersion> reports = reportVersionRepository.findByIdInAndUserId(
                sourceIds, userId);
        if (reports == null || reports.isEmpty()) {
            return Map.of();
        }
        return reports.stream().collect(Collectors.toMap(
                InvestmentReportVersion::getId,
                Function.identity(),
                (left, right) -> left,
                HashMap::new));
    }

    /** 将字符串来源主键安全解析为报告版本 ID。 */
    private Long sourceId(ResearchMemoryEntry entry) {
        if (entry == null || entry.getSourceId() == null) {
            return null;
        }
        try {
            return Long.valueOf(entry.getSourceId());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ResearchMemoryConflictResolver.Candidate conflictCandidate(
            ResearchMemoryEntry entry,
            InvestmentReportVersion source
    ) {
        return new ResearchMemoryConflictResolver.Candidate(
                entry,
                source == null ? entry.getRecommendation() : source.getRecommendation(),
                source == null ? null : source.getReviewStatus(),
                source == null ? entry.getDataCutoffAt() : source.getGeneratedAt());
    }

    /** 用户阻断范围内的旧条目保留审计，但不得成为当前赢家。 */
    private void applyBlockedDecision(ResearchMemoryEntry entry, LocalDateTime now) {
        entry.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.SUPERSEDED);
        entry.setSupersededById(null);
        entry.setSupersededAt(entry.getSupersededAt() == null ? now : entry.getSupersededAt());
        entry.setResolutionReason("BLOCKED_BY_USER_REVOKE");
    }

    /** 将纯 Java 解析器结果映射到持久化 entry 状态。 */
    private void applyResolutionDecision(
            ResearchMemoryEntry entry,
            ResearchMemoryConflictResolver.EntryDecision decision,
            Long winnerId,
            LocalDateTime now
    ) {
        boolean preserveRevokeOrigin = decision.reason()
                == ResearchMemoryConflictResolver.Reason.INELIGIBLE_REVOKED
                && (REVOKED_BY_NEGATIVE_REVIEW.equals(entry.getResolutionReason())
                || REVOKED_BY_USER.equals(entry.getResolutionReason()));
        if (!preserveRevokeOrigin) {
            entry.setResolutionReason(decision.reason().name());
        }
        switch (decision.status()) {
            case CURRENT -> {
                entry.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.CURRENT);
                entry.setSupersededById(null);
                entry.setSupersededAt(null);
            }
            case SUPERSEDED -> {
                entry.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.SUPERSEDED);
                entry.setSupersededById(winnerId);
                entry.setSupersededAt(entry.getSupersededAt() == null ? now : entry.getSupersededAt());
            }
            case CONFLICTED -> {
                entry.setResolutionStatus(ResearchMemoryEntry.ResolutionStatus.CONFLICTED);
                entry.setSupersededById(null);
                entry.setSupersededAt(null);
            }
        }
    }

    private ResearchMemoryConflictGroup.ResolutionStatus groupStatus(
            ResearchMemoryConflictResolver.GroupStatus status
    ) {
        return switch (status) {
            case RESOLVED -> ResearchMemoryConflictGroup.ResolutionStatus.RESOLVED;
            case UNRESOLVED -> ResearchMemoryConflictGroup.ResolutionStatus.UNRESOLVED;
            case NO_ELIGIBLE_CANDIDATE ->
                    ResearchMemoryConflictGroup.ResolutionStatus.NO_ELIGIBLE_CANDIDATE;
        };
    }

    private LocalDateTime effectiveReferenceAt(ResearchMemoryEntry entry) {
        return entry.getDataCutoffAt() == null ? entry.getCreatedAt() : entry.getDataCutoffAt();
    }

    /** 检索时批量校验组赢家，保证每个冲突组最多保留一条。 */
    private ConflictGateResult conflictGate(String userId, List<ResearchMemoryEntry> truthEntries) {
        Set<String> conflictKeys = truthEntries.stream()
                .map(ResearchMemoryEntry::getConflictKey)
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, ResearchMemoryConflictGroup> groups = conflictKeys.isEmpty()
                ? Map.of()
                : conflictGroupRepository.findByUserIdAndConflictKeyIn(userId, conflictKeys)
                .stream()
                .collect(Collectors.toMap(
                        ResearchMemoryConflictGroup::getConflictKey,
                        Function.identity(),
                        (left, right) -> left));
        long unresolved = groups.values().stream()
                .filter(group -> group.getResolutionStatus()
                        == ResearchMemoryConflictGroup.ResolutionStatus.UNRESOLVED)
                .count();
        List<ResearchMemoryEntry> winners = truthEntries.stream()
                .filter(ResearchMemoryEntry::resolutionCurrent)
                .filter(entry -> {
                    if (entry.getConflictKey() == null || entry.getConflictKey().isBlank()) {
                        return true;
                    }
                    ResearchMemoryConflictGroup group = groups.get(entry.getConflictKey());
                    return group != null
                            && group.getResolutionStatus()
                            == ResearchMemoryConflictGroup.ResolutionStatus.RESOLVED
                            && entry.getId().equals(group.getWinnerEntryId());
                })
                .toList();
        return new ConflictGateResult(winners, Math.toIntExact(unresolved));
    }

    /**
     * 捕获资格闸门：排除离线样本、负向审核、非 VERIFIED、无来源证据和无引用报告。
     */
    private boolean eligible(InvestmentReportVersion source, InvestmentReport report) {
        if (source == null || source.getId() == null || report == null
                || source.getUserId() == null || source.getUserId().isBlank()
                || "DEMO".equalsIgnoreCase(source.getModelTier())
                || "offline-rule-fallback".equalsIgnoreCase(source.getModelName())) {
            return false;
        }
        InvestmentReportVersion.ReviewStatus reviewStatus = source.getReviewStatus();
        if (reviewStatus == InvestmentReportVersion.ReviewStatus.REJECTED
                || reviewStatus == InvestmentReportVersion.ReviewStatus.NEEDS_RESEARCH) {
            return false;
        }
        if (report.getQualityStatus() != InvestmentReport.ReportQualityStatus.VERIFIED
                || normalizeRecommendation(report.getRecommendation()) == null
                || report.getEvidenceItems() == null
                || report.getEvidenceItems().isEmpty()
                || report.getEvidenceItems().stream().anyMatch(item ->
                item == null
                        || item.getSourceEvidenceIds() == null
                        || item.getSourceEvidenceIds().isEmpty())) {
            return false;
        }
        return !sourceCitations(report).isEmpty();
    }

    /** 合并报告 citations 与证据来源，去重并限制在 20 条。 */
    private List<String> sourceCitations(InvestmentReport report) {
        List<String> sources = new ArrayList<>();
        if (report.getCitations() != null) {
            report.getCitations().stream()
                    .filter(this::hasText).map(String::trim).forEach(sources::add);
        }
        if (report.getEvidenceItems() != null) {
            report.getEvidenceItems().stream()
                    .map(InvestmentReport.EvidenceItem::getSource)
                    .filter(this::hasText).map(String::trim).forEach(sources::add);
        }
        return sources.stream().distinct().limit(20).toList();
    }

    /** 把评级、核心依据、风险、数据截止和来源压缩成可嵌入的研究记忆文本。 */
    private String buildMemoryText(
            InvestmentReportVersion source,
            InvestmentReport report,
            List<String> citations
    ) {
        String rationale = report.getRationale() == null
                ? "" : String.join("；", report.getRationale().stream().limit(5).toList());
        String risks = report.getRiskFactors() == null
                ? "" : String.join("；", report.getRiskFactors().stream().limit(5).toList());
        return """
                标的：%s
                分析期限：%s
                历史研究结论：%s
                核心依据：%s
                主要风险：%s
                记忆参考时点（当前为报告生成时间）：%s
                来源：%s
                """.formatted(
                normalizeTicker(source.getTicker()),
                effectiveHorizon(report.getAnalysisHorizon()),
                clean(report.getRecommendation()),
                rationale,
                risks,
                source.getGeneratedAt() == null ? "未知" : source.getGeneratedAt(),
                String.join("；", citations)
        ).trim();
    }

    /** 在总预算内为每条最终记忆动态分配空间，避免选中后又静默丢弃。 */
    private PromptResult buildPrompt(
            List<ResearchMemoryEntry> entries,
            Map<Long, ResearchMemoryRanker.ScoredMemory> rankedById,
            boolean includeConflictWarning
    ) {
        if ((entries == null || entries.isEmpty()) && !includeConflictWarning) {
            return PromptResult.empty();
        }
        StringBuilder prompt = new StringBuilder("""
                以下是可能过期的历史研究证据，只能作为背景。若与当前 RAG 或工具数据冲突，必须以当前证据为准，并明确指出历史结论已过期。
                """);
        if (includeConflictWarning) {
            prompt.append("\n注意：部分历史建议存在未消解冲突，系统已排除相反结论，请勿据此补全或猜测。\n");
        }
        int included = 0;
        List<ResearchMemoryEntry> safeEntries = entries == null ? List.of() : entries;
        for (int index = 0; index < safeEntries.size(); index++) {
            int remainingBudget = properties.getMaxPromptChars() - prompt.length();
            int remainingEntries = safeEntries.size() - index;
            if (remainingEntries <= 0) {
                break;
            }
            // 为每条记录前的换行预留一个字符，确保 includedCount 只统计完整写入的记录。
            int distributableBudget = remainingBudget - remainingEntries;
            if (distributableBudget < 100) {
                break;
            }
            int rowBudget = Math.min(1000, distributableBudget / remainingEntries);
            ResearchMemoryEntry entry = safeEntries.get(index);
            String row = promptRow(entry, rankedById.get(entry.getId()), included + 1, rowBudget);
            if (row.isBlank()) {
                continue;
            }
            prompt.append('\n').append(row);
            included++;
        }
        String text = prompt.toString().trim();
        return new PromptResult(text, included);
    }

    /** 先保留结构化元数据和来源，再用剩余预算压缩正文。 */
    private String promptRow(
            ResearchMemoryEntry entry,
            ResearchMemoryRanker.ScoredMemory scored,
            int index,
            int maxChars
    ) {
        String metadata = "[M%d] 标的=%s；期限=%s；建议=%s；参考时点=%s".formatted(
                index,
                entry.getTicker(),
                effectiveHorizon(entry.getAnalysisHorizon()),
                entry.getRecommendation() == null ? "未知" : entry.getRecommendation(),
                effectiveReferenceAt(entry) == null ? "未知" : effectiveReferenceAt(entry));
        if (scored != null) {
            metadata += "；有效分=" + String.format(Locale.ROOT, "%.4f", scored.effectiveScore());
        }
        String sources = String.join("；", readCitationLabels(entry.getSourceCitations()));
        String sourceLine = sources.isBlank() ? "" : "\n来源=" + sources;
        int bodyBudget = Math.max(0, maxChars - metadata.length() - sourceLine.length() - 6);
        String body = truncate(entry.getMemoryText() == null ? "" : entry.getMemoryText(), bodyBudget);
        String row = metadata + (body.isBlank() ? "" : "\n摘要=" + body) + sourceLine;
        return truncate(row, maxChars);
    }

    /** 只记录安全阶段指标、分数和 ID，不把完整历史记忆复制进 Trace。 */
    private void traceRetrieval(
            ResearchMemoryQuery query,
            int candidateCount,
            int truthCount,
            int currentWinnerCount,
            int unresolvedGroupCount,
            List<ResearchMemoryRanker.ScoredMemory> ranked,
            int promptChars,
            long durationMs
    ) {
        if (query == null || query.traceId().isBlank()) {
            return;
        }
        List<ResearchMemoryEntry> selected = ranked.stream()
                .map(ResearchMemoryRanker.ScoredMemory::entry)
                .toList();
        List<Map<String, Object>> safeHits = safeTraceHits(ranked);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("candidateK", properties.getCandidateK());
        attributes.put("shortlistK", properties.getShortlistK());
        attributes.put("finalTopK", ranker.finalTopK(query));
        attributes.put("candidateCount", candidateCount);
        attributes.put("truthCount", truthCount);
        attributes.put("currentWinnerCount", currentWinnerCount);
        attributes.put("unresolvedGroupCount", unresolvedGroupCount);
        attributes.put("selectedIds", ranked.stream()
                .map(scored -> scored.entry().getId()).toList());
        attributes.put("promptChars", promptChars);
        attributes.put("hits", safeHits);
        attributes.put("injected", properties.isInject() && promptChars > 0);
        traceService.addStep(query.traceId(), AgentStep.builder()
                .thought("Retrieved sourced cross-session research memory.")
                .action("Research Memory Retrieval")
                .observation("Selected " + selected.size() + " memory entries after staged retrieval.")
                .durationMs(durationMs)
                .tokenCount(0)
                .attributes(attributes)
                .build());
    }

    /** 把命中转换为不含正文和原始来源的安全 Trace 元数据。 */
    private List<Map<String, Object>> safeTraceHits(
            List<ResearchMemoryRanker.ScoredMemory> ranked
    ) {
        return ranked.stream().map(scored -> {
            ResearchMemoryEntry entry = scored.entry();
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("entryId", entry.getId());
            hit.put("ticker", entry.getTicker());
            hit.put("semanticScore", scored.semanticScore());
            hit.put("freshness", scored.freshness());
            hit.put("effectiveScore", scored.effectiveScore());
            hit.put("ageDays", scored.ageDays());
            hit.put("referenceAtType", entry.getDataCutoffAt() == null
                    ? "ENTRY_CREATED_AT" : "REPORT_GENERATED_AT");
            hit.put("resolutionStatus", entry.getResolutionStatus() == null
                    ? "UNKNOWN" : entry.getResolutionStatus().name());
            hit.put("sourceCount", readCitationCount(entry.getSourceCitations()));
            return Map.copyOf(hit);
        }).toList();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return maxChars == 1 ? "…" : value.substring(0, maxChars - 1) + "…";
    }

    /** 读取引用 JSON 数组长度；损坏时按零处理。 */
    private int readCitationCount(String json) {
        try {
            return objectMapper.readTree(json).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Prompt 最多展示三条有限长来源，完整来源仍保存在 MySQL。 */
    private List<String> readCitationLabels(String json) {
        try {
            List<String> values = new ArrayList<>();
            objectMapper.readTree(json).elements().forEachRemaining(node -> {
                if (values.size() < 3 && node.isTextual() && !node.asText().isBlank()) {
                    values.add(truncate(node.asText().trim(), 160));
                }
            });
            return List.copyOf(values);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** 将数据库实体转换为管理 API 视图。 */
    private MemoryView toView(ResearchMemoryEntry entry) {
        return new MemoryView(
                entry.getId(), entry.getTicker(), entry.getSourceType(), entry.getSourceId(),
                entry.getMemoryText(), entry.getSourceCitations(), entry.getDataCutoffAt(),
                entry.getSnapshotHash(), entry.getAnalysisHorizon(), entry.getRecommendation(),
                entry.getResolutionStatus(), entry.getSupersededById(), entry.getResolutionReason(),
                entry.getVectorStatus(), entry.getCreatedAt()
        );
    }

    /** 将来源引用序列化为数据库 JSON。 */
    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("RESEARCH_MEMORY_CITATIONS_SERIALIZE_FAILED", error);
        }
    }

    /** 为记忆正文生成内容哈希。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

    /** 只接受短股票代码字符集，其余统一为 UNKNOWN。 */
    private String normalizeTicker(String ticker) {
        String value = ticker == null ? "UNKNOWN" : ticker.trim().toUpperCase();
        return value.matches("[A-Z0-9.\\-]{1,16}") ? value : "UNKNOWN";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 将纳秒计时转换为 Trace 毫秒耗时。 */
    private long elapsedMs(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /** MySQL 冲突门禁后的当前赢家和未消解组统计。 */
    private record ConflictGateResult(
            List<ResearchMemoryEntry> currentWinners,
            int unresolvedGroupCount
    ) {
        private ConflictGateResult {
            currentWinners = currentWinners == null ? List.of() : List.copyOf(currentWinners);
        }
    }

    /** 实际生成的 Prompt 及其中完整容纳的记忆条数。 */
    private record PromptResult(String text, int includedCount) {
        private PromptResult {
            text = text == null ? "" : text;
            includedCount = Math.max(0, includedCount);
        }

        private static PromptResult empty() {
            return new PromptResult("", 0);
        }
    }

    /**
     * 一次研究记忆检索结果。
     *
     * @param promptContext 可注入提示词的历史背景
     * @param hitCount 通过数据库真源校验的命中数
     * @param injected 是否按配置实际提供了上下文
     */
    public record RetrievalResult(String promptContext, int hitCount, boolean injected) {
        /** @return 未命中、未注入提示词的空检索结果 */
        public static RetrievalResult empty() {
            return new RetrievalResult("", 0, false);
        }
    }

    /** 管理接口展示的未撤销研究记忆。 */
    public record MemoryView(
            Long id,
            String ticker,
            String sourceType,
            String sourceId,
            String memoryText,
            String sourceCitations,
            LocalDateTime dataCutoffAt,
            String snapshotHash,
            AnalysisHorizon analysisHorizon,
            String recommendation,
            ResearchMemoryEntry.ResolutionStatus resolutionStatus,
            Long supersededById,
            String resolutionReason,
            ResearchMemoryEntry.VectorStatus vectorStatus,
            LocalDateTime createdAt
    ) {
    }
}
