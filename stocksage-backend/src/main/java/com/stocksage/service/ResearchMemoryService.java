package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryEntry;
import com.stocksage.repository.InvestmentReportVersionRepository;
import com.stocksage.repository.ResearchMemoryEntryRepository;
import com.stocksage.trace.TraceService;
import lombok.extern.slf4j.Slf4j;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public ResearchMemoryService(
            ResearchMemoryEntryRepository repository,
            InvestmentReportVersionRepository reportVersionRepository,
            ResearchMemoryVectorIndex vectorIndex,
            ResearchMemoryProperties properties,
            ObjectMapper objectMapper,
            TraceService traceService,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.reportVersionRepository = reportVersionRepository;
        this.vectorIndex = vectorIndex;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
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
        String sourceId = String.valueOf(currentSource.getId());
        var existing = repository.findByUserIdAndSourceTypeAndSourceId(
                currentSource.getUserId(), SOURCE_TYPE, sourceId);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<String> citations = sourceCitations(report);
        String text = buildMemoryText(currentSource, report, citations);
        ResearchMemoryEntry entry = new ResearchMemoryEntry();
        entry.setUserId(currentSource.getUserId());
        entry.setTicker(normalizeTicker(currentSource.getTicker()));
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
            return repository.findByUserIdAndSourceTypeAndSourceId(
                    currentSource.getUserId(), SOURCE_TYPE, sourceId).orElseThrow(() -> duplicate);
        }
        if (properties.isIndex()) {
            indexAfterCommit(entry);
        }
        return entry;
    }

    /** 定时重试 PENDING/FAILED 的向量索引，每轮最多处理 10 条。 */
    @Scheduled(fixedDelayString = "${stocksage.research-memory.compensation-delay-ms:60000}")
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

    /**
     * 检索当前用户的历史研究记忆，并按配置决定是否注入提示词。
     *
     * @param userId 当前用户 ID
     * @param ticker 可选 ticker 过滤
     * @param query 当前用户问题
     * @param traceId 可选 Agent Trace ID
     * @return 提示词片段、数据库确认命中数及是否实际注入；失败时为空结果
     */
    public RetrievalResult retrieve(String userId, String ticker, String query, String traceId) {
        if (!properties.isRetrieve() || userId == null || userId.isBlank()
                || query == null || query.isBlank()) {
            return RetrievalResult.empty();
        }
        long startedAt = System.nanoTime();
        try {
            // 先调用 Milvus 做 tenant/ticker 过滤检索，随后必须回查数据库 active+INDEXED 真源。
            List<ResearchMemoryVectorIndex.Hit> hits =
                    vectorIndex.search(userId, ticker == null ? "" : ticker, query, properties.getTopK());
            Map<Long, Double> scores = new LinkedHashMap<>();
            hits.forEach(hit -> scores.put(hit.entryId(), hit.score()));
            List<ResearchMemoryEntry> entries =
                    repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                            scores.keySet(),
                            userId.trim(),
                            ResearchMemoryEntry.VectorStatus.INDEXED)
                    .stream()
                    .filter(ResearchMemoryEntry::active)
                    .filter(entry ->
                            entry.getVectorStatus() == ResearchMemoryEntry.VectorStatus.INDEXED)
                    .sorted(Comparator.comparingDouble(
                            entry -> -scores.getOrDefault(entry.getId(), 0.0)))
                    .limit(3)
                    .toList();
            traceRetrieval(traceId, entries, scores, elapsedMs(startedAt));
            if (entries.isEmpty()) {
                return RetrievalResult.empty();
            }
            if (!properties.isInject()) {
                return new RetrievalResult("", entries.size(), false);
            }
            return new RetrievalResult(buildPrompt(entries), entries.size(), true);
        } catch (Exception error) {
            log.warn("Research memory retrieval failed. errorType={}", error.getClass().getSimpleName());
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
        ResearchMemoryEntry entry = repository.findByIdAndUserId(id, userId.trim()).orElse(null);
        return revokeEntry(entry);
    }

    /**
     * 撤销指定报告版本派生的研究记忆，供负向人工审核调用。
     *
     * @param source 报告版本实体
     * @return true 表示找到并撤销 active 记忆
     */
    @Transactional
    public boolean revokeForReport(InvestmentReportVersion source) {
        if (source == null || source.getId() == null
                || source.getUserId() == null || source.getUserId().isBlank()) {
            return false;
        }
        ResearchMemoryEntry entry = repository.findByUserIdAndSourceTypeAndSourceId(
                source.getUserId().trim(),
                SOURCE_TYPE,
                String.valueOf(source.getId())
        ).orElse(null);
        return revokeEntry(entry);
    }

    /** 先提交数据库 REVOKED 真源，再安排非事务性向量删除。 */
    private boolean revokeEntry(ResearchMemoryEntry entry) {
        if (entry == null || !entry.active()) {
            return false;
        }
        entry.setRevokedAt(LocalDateTime.now());
        entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.REVOKED);
        entry.setVectorErrorCode(null);
        // Flush the database truth before deleting the vector. A concurrent index CAS must either
        // finish first (then this delete removes its vector) or observe REVOKED and clean up its
        // own just-written vector after the guarded update is rejected.
        repository.saveAndFlush(entry);
        // Delete even when the row was observed as PENDING/FAILED: a concurrent index call may
        // already have written the deterministic vector id before its guarded database update.
        // The database revocation is the authoritative truth and must commit before the
        // non-transactional vector side effect is attempted.
        deleteVectorAfterCommit(entry.getId(), "entry revoked");
        return true;
    }

    /**
     * 写入单条向量，并用数据库状态 CAS 把 PENDING/FAILED 转为 INDEXED 或 FAILED。
     *
     * <p>向量成功但 CAS 失败时立即删除向量，避免半完成索引。</p>
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
                    LocalDateTime.now()
            );
        } catch (Exception error) {
            if (vectorWritten) {
                // The authoritative database row is still PENDING/FAILED. Remove the vector that
                // cannot be proven committed so retrieval can never observe a half-finished index.
                deleteVectorBestEffort(entry.getId(), "post-index CAS failed");
            }
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
        if (vectorWritten) {
            deleteVectorBestEffort(entry.getId(), "post-index CAS rejected");
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
                历史研究结论：%s
                核心依据：%s
                主要风险：%s
                数据截止：%s
                来源：%s
                """.formatted(
                normalizeTicker(source.getTicker()),
                clean(report.getRecommendation()),
                rationale,
                risks,
                source.getGeneratedAt() == null ? "未知" : source.getGeneratedAt(),
                String.join("；", citations)
        ).trim();
    }

    /** 构造带“可能过期、当前证据优先”边界提示的长度受限上下文。 */
    private String buildPrompt(List<ResearchMemoryEntry> entries) {
        StringBuilder prompt = new StringBuilder("""
                以下是可能过期的历史研究证据，只能作为背景。若与当前 RAG 或工具数据冲突，必须以当前证据为准，并明确指出历史结论已过期。

                """);
        int index = 1;
        for (ResearchMemoryEntry entry : entries) {
            String row = "[M%d] %s%n".formatted(index++, entry.getMemoryText());
            if (prompt.length() + row.length() > properties.getMaxPromptChars()) {
                break;
            }
            prompt.append(row);
        }
        return prompt.toString().trim();
    }

    /** 只记录安全元数据、分数和年龄，不把完整历史记忆复制进 Trace。 */
    private void traceRetrieval(
            String traceId,
            List<ResearchMemoryEntry> entries,
            Map<Long, Double> scores,
            long durationMs
    ) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        List<Map<String, Object>> safeHits = entries.stream().map(entry -> Map.<String, Object>of(
                "entryId", entry.getId(),
                "ticker", entry.getTicker(),
                "score", scores.getOrDefault(entry.getId(), 0.0),
                "ageDays", entry.getCreatedAt() == null ? 0
                        : Math.max(0, Duration.between(entry.getCreatedAt(), LocalDateTime.now()).toDays()),
                "sourceCount", readCitationCount(entry.getSourceCitations())
        )).toList();
        // 调用 TraceService 追加检索步骤，便于评估命中与是否注入，而不影响主回答。
        traceService.addStep(traceId, AgentStep.builder()
                .thought("Retrieved sourced cross-session research memory.")
                .action("Research Memory Retrieval")
                .observation("Matched " + entries.size() + " sourced memory entries.")
                .durationMs(durationMs)
                .tokenCount(0)
                .attributes(Map.of("hits", safeHits, "injected", properties.isInject()))
                .build());
    }

    /** 读取引用 JSON 数组长度；损坏时按零处理。 */
    private int readCitationCount(String json) {
        try {
            return objectMapper.readTree(json).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** 将数据库实体转换为管理 API 视图。 */
    private MemoryView toView(ResearchMemoryEntry entry) {
        return new MemoryView(
                entry.getId(), entry.getTicker(), entry.getSourceType(), entry.getSourceId(),
                entry.getMemoryText(), entry.getSourceCitations(), entry.getDataCutoffAt(),
                entry.getSnapshotHash(), entry.getVectorStatus(), entry.getCreatedAt()
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
            ResearchMemoryEntry.VectorStatus vectorStatus,
            LocalDateTime createdAt
    ) {
    }
}
