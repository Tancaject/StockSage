package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.DebateDecisionPolicy;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.ParseStatus;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.dto.InvestmentReportReviewRequest;
import com.stocksage.model.dto.InvestmentReportReviewSummary;
import com.stocksage.model.dto.InvestmentReportVersionSummary;
import com.stocksage.model.entity.InvestmentReportReview;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.repository.InvestmentReportReviewRepository;
import com.stocksage.repository.InvestmentReportVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 投资报告版本、快照复用和人工审核的核心领域服务。
 *
 * <p>{@link DeepResearchPipeline} 在报告发布事务中调用本服务：先由证据账本生成
 * dataSnapshotHash/contextHash，再按“用户 + ticker + 双哈希”复用或创建递增版本。
 * 复用必须同时通过当前 {@link DeepResearchCompletionPolicy} 与人工审核状态，旧策略、
 * 未验证或被拒绝的报告不会作为新回答缓存。</p>
 *
 * <p>报告落库后发布 {@link InvestmentReportPersistedEvent} 供研究记忆捕获；人工负向审核会调用
 * {@link ResearchMemoryService} 撤销相关记忆。这里不负责 Markdown 渲染或任务终态 CAS。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentReportVersionService {

    /** 参与双哈希的提示词/报告契约版本，变更时自动使旧快照失效。 */
    private static final String PROMPT_CONTRACT_VERSION =
            "investment-report-v6-debate-provenance-freshness-bound";
    /** 历史报告列表的默认条数。 */
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    /** 历史报告列表的最大条数。 */
    private static final int MAX_HISTORY_LIMIT = 50;

    /** 查询快照复用、分配 ticker 版本号并持久化报告 JSON。 */
    private final InvestmentReportVersionRepository repository;
    /** 追加不可变人工审核流转历史。 */
    private final InvestmentReportReviewRepository reviewRepository;
    /** 序列化和恢复结构化 InvestmentReport。 */
    private final ObjectMapper objectMapper;
    /** 报告落库后发布进程内领域事件。 */
    private final ApplicationEventPublisher eventPublisher;
    /** 负向人工审核时撤销由该报告产生的研究记忆。 */
    private final ResearchMemoryService researchMemoryService;
    /** 重新校验缓存报告在当前策略和证据账本下是否允许评级。 */
    private final DeepResearchCompletionPolicy completionPolicy;

    /**
     * 计算并写回当前研究状态的证据快照哈希和上下文哈希。
     *
     * @param state 证据收集完成后的分析状态；null 时无操作
     */
    public void prepareHashes(AnalysisState state) {
        if (state == null) {
            return;
        }
        state.setDataSnapshotHash(computeDataSnapshotHash(state));
        state.setContextHash(computeContextHash(state));
    }

    /**
     * 对完成/辩论策略版本、ticker、排序后的证据标识/内容哈希/业务时间及引用生成稳定哈希。
     *
     * @param state 当前分析状态
     * @return 64 位十六进制 SHA-256，用于判断底层研究数据是否相同
     */
    public String computeDataSnapshotHash(AnalysisState state) {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, "contract", PROMPT_CONTRACT_VERSION);
        appendField(canonical, "policyId", DeepResearchCompletionPolicy.POLICY_ID);
        appendField(canonical, "policyVersion",
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION));
        appendField(canonical, "debateDecisionPolicyId", DebateDecisionPolicy.POLICY_ID);
        appendField(canonical, "debateDecisionPolicyVersion",
                Integer.toString(DebateDecisionPolicy.POLICY_VERSION));
        appendField(canonical, "ticker", normalizeTicker(state == null ? null : state.getPrimaryTicker()));
        if (state != null && state.getEvidenceLedger() != null) {
            state.getEvidenceLedger().evidence().stream()
                    .map(EvidenceEnvelope::observedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .map(observedAt -> observedAt.atZone(ZoneOffset.UTC).toLocalDate().toString())
                    .ifPresent(day -> appendField(canonical, "freshnessEvaluationDayUtc", day));
            state.getEvidenceLedger().evidence().stream()
                    .sorted(Comparator
                            .comparing((EvidenceEnvelope item) -> item.dimension().name())
                            .thenComparing(EvidenceEnvelope::capabilityId)
                            .thenComparing(EvidenceEnvelope::evidenceId))
                    .forEach(item -> appendField(
                            canonical,
                            "evidence",
                            String.join("|",
                                    item.dimension().name(),
                                    item.capabilityId(),
                                    item.targetKey(),
                                    item.status().name(),
                                    item.evidenceId(),
                                    item.provider(),
                                    item.sourceRef(),
                                    Boolean.toString(item.approvedReadOnly()),
                                    item.payloadHash(),
                                    item.asOf() == null ? "" : item.asOf().toString())
                    ));
        }
        appendSortedList(canonical, "citations", state == null ? null : state.getCitations());
        return sha256(canonical.toString());
    }

    /**
     * 对提示词契约、ticker、用户问题和数据快照哈希生成稳定上下文哈希。
     *
     * @param state 当前分析状态
     * @return 64 位十六进制 SHA-256，用于区分同数据下的不同研究问题
     */
    public String computeContextHash(AnalysisState state) {
        String dataHash = state == null ? "" : computeDataSnapshotHash(state);
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, "contract", PROMPT_CONTRACT_VERSION);
        appendField(canonical, "ticker", normalizeTicker(state == null ? null : state.getPrimaryTicker()));
        appendField(canonical, "query", state == null ? null : state.getQuery());
        appendField(canonical, "dataSnapshotHash", dataHash);
        return sha256(canonical.toString());
    }

    /**
     * 查找可在当前证据与策略下安全复用的同快照报告。
     *
     * @param userId 报告所属用户
     * @param conversationId 当前会话 ID；当前查询键不使用该参数，保留调用契约
     * @param state 当前证据和问题状态
     * @return 已重新通过策略闸门且未被人工负向审核的报告
     */
    @Transactional(readOnly = true)
    public Optional<InvestmentReport> findReusableReport(String userId, Long conversationId, AnalysisState state) {
        if (state == null || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        prepareHashesIfMissing(state);
        String ticker = normalizeTicker(state.getPrimaryTicker());
        if (ticker.isBlank() || state.getDataSnapshotHash() == null || state.getContextHash() == null) {
            return Optional.empty();
        }
        // 调用快照唯一键查询，再依次施加人工审核和当前完成策略双重复用闸门。
        return repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                        userId,
                        ticker,
                        state.getDataSnapshotHash(),
                        state.getContextHash()
                )
                .filter(this::allowsCacheReuseUnderHumanReview)
                .flatMap(entity -> reusableReport(entity, state));
    }

    /**
     * 持久化或复用报告版本，并只返回结构化报告。
     *
     * @param userId 所属用户
     * @param conversationId 来源会话
     * @param state 已包含报告的分析状态
     * @param modelTier 生成模型档位
     * @param modelName 生成模型名称
     * @return 新报告或安全复用报告；输入不完整时为 null
     */
    public InvestmentReport persistReportVersion(
            String userId,
            Long conversationId,
            AnalysisState state,
            String modelTier,
            String modelName
    ) {
        return persistReportVersionWithMetadata(userId, conversationId, state, modelTier, modelName).report();
    }

    /**
     * 持久化或复用报告，并返回版本 ID 与复用标记。
     *
     * <p>并发插入同快照时依赖数据库唯一键裁决，冲突后回读赢家并再次执行复用策略校验。</p>
     *
     * @param userId 所属用户
     * @param conversationId 来源会话
     * @param state 已包含报告与证据账本的分析状态
     * @param modelTier 生成模型档位
     * @param modelName 生成模型名称
     * @return 报告、实体 ID 和是否复用
     */
    public PersistedReportVersion persistReportVersionWithMetadata(
            String userId,
            Long conversationId,
            AnalysisState state,
            String modelTier,
            String modelName
    ) {
        if (state == null || state.getInvestmentReport() == null || userId == null || userId.isBlank()) {
            return new PersistedReportVersion(null, null, false);
        }
        prepareHashesIfMissing(state);
        String ticker = normalizeTicker(state.getPrimaryTicker());
        if (ticker.isBlank()) {
            ticker = "UNKNOWN";
        }

        Optional<InvestmentReportVersion> existing = repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                userId,
                ticker,
                state.getDataSnapshotHash(),
                state.getContextHash()
        );
        if (existing.isPresent()) {
            InvestmentReport reused = requireReusableReport(existing.get(), state);
            state.setInvestmentReport(reused);
            // 即使复用也发布持久化事件，让下游捕获逻辑按自身幂等规则观察这次使用。
            eventPublisher.publishEvent(new InvestmentReportPersistedEvent(existing.get(), reused));
            return new PersistedReportVersion(reused, existing.get().getId(), true);
        }

        int nextVersion = repository.findTopByUserIdAndTickerOrderByReportVersionDesc(userId, ticker)
                .map(InvestmentReportVersion::getReportVersion)
                .filter(version -> version != null && version > 0)
                .map(version -> version + 1)
                .orElse(1);

        InvestmentReport report = state.getInvestmentReport();
        enrichReport(report, ticker, state.getDataSnapshotHash(), state.getContextHash(),
                nextVersion, modelTier, modelName, false);

        InvestmentReportVersion entity = new InvestmentReportVersion();
        entity.setUserId(userId.trim());
        entity.setConversationId(conversationId);
        entity.setTicker(ticker);
        entity.setUserQuery(state.getQuery());
        entity.setRecommendation(report.getRecommendation());
        entity.setReportVersion(nextVersion);
        entity.setDataSnapshotHash(state.getDataSnapshotHash());
        entity.setContextHash(state.getContextHash());
        entity.setModelTier(blankToNull(modelTier));
        entity.setModelName(blankToNull(modelName));
        entity.setGeneratedAt(report.getGeneratedAt() == null ? LocalDateTime.now() : report.getGeneratedAt());
        entity.setReportJson(writeReportJson(report));

        try {
            // 立即 flush 让快照唯一键与版本唯一键在当前发布事务内完成并发裁决。
            entity = repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            log.warn("Investment report version already exists after concurrent persist, userId={}, ticker={}, dataSnapshotHash={}, contextHash={}",
                    userId, ticker, state.getDataSnapshotHash(), state.getContextHash());
            Optional<InvestmentReportVersion> raced = repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                    userId,
                    ticker,
                    state.getDataSnapshotHash(),
                    state.getContextHash()
            );
            if (raced.isPresent()) {
                InvestmentReport reused = requireReusableReport(raced.get(), state);
                state.setInvestmentReport(reused);
                eventPublisher.publishEvent(new InvestmentReportPersistedEvent(raced.get(), reused));
                return new PersistedReportVersion(reused, raced.get().getId(), true);
            }
            throw e;
        }
        state.setInvestmentReport(report);
        eventPublisher.publishEvent(new InvestmentReportPersistedEvent(entity, report));
        return new PersistedReportVersion(report, entity.getId(), false);
    }

    /**
     * 列出用户报告历史，可按 ticker 过滤。
     *
     * @param userId 当前用户
     * @param ticker 可选 ticker；空值列出所有标的
     * @param limit 请求条数，收敛到默认值和最大值
     * @return 新到旧的报告摘要
     */
    @Transactional(readOnly = true)
    public List<InvestmentReportVersionSummary> listReportVersions(String userId, String ticker, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int cappedLimit = normalizeLimit(limit);
        String normalizedTicker = normalizeTicker(ticker);
        List<InvestmentReportVersion> rows = normalizedTicker.isBlank()
                ? repository.findByUserIdOrderByCreatedAtDesc(userId.trim(), PageRequest.of(0, cappedLimit))
                : repository.findByUserIdAndTickerOrderByReportVersionDesc(
                        userId.trim(),
                        normalizedTicker,
                        PageRequest.of(0, cappedLimit)
                );
        return rows.stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 查找用户某 ticker 的最新完整报告详情。
     *
     * @param userId 当前用户
     * @param ticker 目标 ticker
     * @return 报告、证据、引用和审核历史
     */
    @Transactional(readOnly = true)
    public Optional<ReportDetail> findLatestReportDetail(String userId, String ticker) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank()) {
            return Optional.empty();
        }
        return repository.findByUserIdAndTickerOrderByReportVersionDesc(
                        userId.trim(),
                        normalizedTicker,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .map(this::toDetail);
    }

    /**
     * 按 ID 读取属于当前用户的完整报告详情。
     *
     * @param userId 当前用户
     * @param reportVersionId 报告版本实体 ID
     * @return 完整详情
     * @throws ResourceNotFoundException 不存在或不属于该用户时
     */
    @Transactional(readOnly = true)
    public ReportDetail getReportDetail(String userId, Long reportVersionId) {
        InvestmentReportVersion entity = findOwnedReport(userId, reportVersionId);
        return toDetail(entity);
    }

    /**
     * 按乐观锁版本推进人工审核状态，并追加审核历史。
     *
     * <p>REJECTED/NEEDS_RESEARCH 必须带评论，并会撤销该报告派生的研究记忆。</p>
     *
     * @param userId 当前用户/审核人
     * @param reportVersionId 报告版本实体 ID
     * @param request 目标状态、评论和 expectedLockVersion
     * @return 更新后的完整详情
     * @throws ResponseStatusException 乐观锁冲突时返回 HTTP 409
     */
    @Transactional
    public ReportDetail reviewReport(
            String userId,
            Long reportVersionId,
            InvestmentReportReviewRequest request
    ) {
        InvestmentReportVersion entity = findOwnedReport(userId, reportVersionId);
        validateReviewRequest(request);

        long currentLockVersion = effectiveLockVersion(entity.getLockVersion());
        if (request.expectedLockVersion() != currentLockVersion) {
            throw staleReview();
        }

        InvestmentReportVersion.ReviewStatus fromStatus = effectiveReviewStatus(entity.getReviewStatus());
        InvestmentReportVersion.ReviewStatus toStatus = request.status();
        if (!isAllowedTransition(fromStatus, toStatus)) {
            throw new IllegalArgumentException(
                    "Invalid report review transition from " + fromStatus + " to " + toStatus);
        }

        String comment = blankToNull(request.comment());
        if ((toStatus == InvestmentReportVersion.ReviewStatus.REJECTED
                || toStatus == InvestmentReportVersion.ReviewStatus.NEEDS_RESEARCH)
                && comment == null) {
            throw new IllegalArgumentException("A non-blank comment is required for " + toStatus);
        }

        LocalDateTime now = LocalDateTime.now();
        entity.setReviewStatus(toStatus);
        entity.setReviewerUserId(userId.trim());
        entity.setReviewComment(comment);
        entity.setReviewedAt(now);

        try {
            // 调用 JPA @Version 乐观锁保存审核主状态；并发修改会转换为明确 409。
            repository.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException e) {
            throw staleReview();
        }

        InvestmentReportReview review = new InvestmentReportReview();
        review.setReportVersionId(entity.getId());
        review.setReviewer(userId.trim());
        review.setFromStatus(fromStatus);
        review.setToStatus(toStatus);
        review.setComment(comment);
        review.setCreatedAt(now);
        // 追加独立审核历史，保留每次 from/to 状态与评论，而非只保存最终状态。
        reviewRepository.saveAndFlush(review);

        if (isNegativeHumanReview(toStatus)) {
            // 调用研究记忆服务撤销被人工否定报告产生的长期偏好/研究线索。
            researchMemoryService.revokeForReport(entity);
        } else {
            // 审核通过或重新进入审核时，重新计算该来源所在冲突组。
            researchMemoryService.reconcileForReport(entity);
        }

        return toDetail(entity);
    }

    /**
     * 读取适合嵌入其他页面的一段报告摘要。
     *
     * @param userId 当前用户
     * @param reportVersionId 报告版本实体 ID
     * @return 最长 220 字符的摘要；无报告或无内容时为空
     */
    @Transactional(readOnly = true)
    public Optional<String> findReportBrief(String userId, Long reportVersionId) {
        if (userId == null || userId.isBlank() || reportVersionId == null) {
            return Optional.empty();
        }
        return repository.findByIdAndUserId(reportVersionId, userId.trim())
                .map(entity -> buildPreview(toReport(entity, false)))
                .filter(brief -> !brief.isBlank());
    }

    /** 仅在任一哈希缺失时计算，保留上游已经冻结的快照值。 */
    private void prepareHashesIfMissing(AnalysisState state) {
        if (state.getDataSnapshotHash() == null || state.getDataSnapshotHash().isBlank()
                || state.getContextHash() == null || state.getContextHash().isBlank()) {
            prepareHashes(state);
        }
    }

    /** 从实体 JSON 恢复报告，并补齐版本、模型和复用元数据。 */
    private InvestmentReport toReport(InvestmentReportVersion entity, boolean reusedFromCache) {
        InvestmentReport report = readStoredReport(entity);
        enrichReport(report, entity.getTicker(), entity.getDataSnapshotHash(), entity.getContextHash(),
                entity.getReportVersion(), entity.getModelTier(), entity.getModelName(), reusedFromCache);
        if (report.getQualityStatus() == null) {
            report.setQualityStatus(InvestmentReport.ReportQualityStatus.LEGACY_UNVERIFIED);
        }
        if (report.getAnalysisHorizon() == null) {
            // 兼容旧版 JSON 缺失或显式写入 null 的期限字段；旧策略版本仍不能作为当前缓存复用。
            report.setAnalysisHorizon(AnalysisHorizon.UNSPECIFIED);
        }
        return report;
    }

    /** 解析存储报告；旧/损坏 JSON 降级为仅含 recommendation 的兼容报告。 */
    private InvestmentReport readStoredReport(InvestmentReportVersion entity) {
        InvestmentReport report;
        try {
            report = objectMapper.readValue(entity.getReportJson(), InvestmentReport.class);
        } catch (Exception e) {
            log.warn("Failed to parse investment report version {}, falling back to metadata only: {}",
                    entity.getId(), e.getMessage());
            report = InvestmentReport.builder()
                    .recommendation(entity.getRecommendation())
                    .build();
        }
        return report;
    }

    /**
     * 用当前 completion policy 和当前证据账本重新验收缓存报告。
     *
     * <p>只有 VERIFIED、策略 ID/版本一致且仍允许 recommendation 的报告可复用。</p>
     */
    private boolean reusableUnderCurrentPolicy(InvestmentReport report, AnalysisState state) {
        if (report == null
                || report.getQualityStatus() != InvestmentReport.ReportQualityStatus.VERIFIED
                || !DeepResearchCompletionPolicy.POLICY_ID.equals(report.getCompletionPolicyId())
                || !Integer.valueOf(DeepResearchCompletionPolicy.POLICY_VERSION)
                .equals(report.getCompletionPolicyVersion())
                || report.getDecisionAudit() == null
                || !DebateDecisionPolicy.POLICY_ID.equals(report.getDecisionAudit().policyId())
                || report.getDecisionAudit().version() != DebateDecisionPolicy.POLICY_VERSION
                || state.getEvidenceLedger() == null) {
            return false;
        }
        try {
            // 调用确定性报告后置闸门，避免仅因双哈希相同就绕过升级后的安全策略。
            return completionPolicy.afterReport(
                    RunContext.deepResearch(),
                    state.getEvidenceLedger(),
                    new SynthesisResult(report, ParseStatus.VALID, List.of())
            ).allowsRecommendation();
        } catch (RuntimeException e) {
            log.warn("Cached investment report failed deterministic report gate; reuse denied: {}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** 恢复实体报告并施加当前策略复用闸门。 */
    private Optional<InvestmentReport> reusableReport(
            InvestmentReportVersion entity,
            AnalysisState state
    ) {
        InvestmentReport report = readStoredReport(entity);
        if (!reusableUnderCurrentPolicy(report, state)) {
            return Optional.empty();
        }
        enrichReport(report, entity.getTicker(), entity.getDataSnapshotHash(), entity.getContextHash(),
                entity.getReportVersion(), entity.getModelTier(), entity.getModelName(), true);
        return Optional.of(report);
    }

    /** REJECTED 或 NEEDS_RESEARCH 的人工结论优先于自动缓存复用。 */
    private boolean allowsCacheReuseUnderHumanReview(InvestmentReportVersion entity) {
        return entity != null && !isNegativeHumanReview(effectiveReviewStatus(entity.getReviewStatus()));
    }

    /** 并发快照冲突后要求赢家仍满足人工和自动策略，否则拒绝静默复用。 */
    private InvestmentReport requireReusableReport(
            InvestmentReportVersion entity,
            AnalysisState state
    ) {
        if (!allowsCacheReuseUnderHumanReview(entity)) {
            throw new IllegalStateException("REPORT_REVIEW_DISALLOWS_CACHE_REUSE");
        }
        return reusableReport(entity, state)
                .orElseThrow(() -> new IllegalStateException("REPORT_POLICY_DISALLOWS_CACHE_REUSE"));
    }

    /** 判断审核状态是否否决当前报告作为缓存和研究记忆来源。 */
    private boolean isNegativeHumanReview(InvestmentReportVersion.ReviewStatus status) {
        return status == InvestmentReportVersion.ReviewStatus.REJECTED
                || status == InvestmentReportVersion.ReviewStatus.NEEDS_RESEARCH;
    }

    /** 将版本实体和已恢复报告转换为列表摘要。 */
    private InvestmentReportVersionSummary toSummary(InvestmentReportVersion entity) {
        InvestmentReport report = toReport(entity, false);
        return toSummary(entity, report);
    }

    private InvestmentReportVersionSummary toSummary(InvestmentReportVersion entity, InvestmentReport report) {
        return new InvestmentReportVersionSummary(
                entity.getId(),
                entity.getConversationId(),
                entity.getTicker(),
                entity.getReportVersion(),
                entity.getRecommendation(),
                entity.getDataSnapshotHash(),
                entity.getContextHash(),
                entity.getModelTier(),
                entity.getModelName(),
                entity.getGeneratedAt(),
                entity.getCreatedAt(),
                entity.getUserQuery(),
                buildPreview(report),
                effectiveReviewStatus(entity.getReviewStatus()),
                entity.getReviewerUserId(),
                entity.getReviewComment(),
                entity.getReviewedAt(),
                entity.getUpdatedAt() == null ? entity.getCreatedAt() : entity.getUpdatedAt(),
                effectiveLockVersion(entity.getLockVersion())
        );
    }

    /** 组装报告正文、证据、引用和按时间排序的审核历史。 */
    private ReportDetail toDetail(InvestmentReportVersion entity) {
        InvestmentReport report = toReport(entity, false);
        List<InvestmentReportReviewSummary> history = safeList(
                reviewRepository.findByReportVersionIdOrderByCreatedAtAscIdAsc(entity.getId())
        ).stream()
                .map(this::toReviewSummary)
                .toList();
        return new ReportDetail(
                toSummary(entity, report),
                report,
                safeList(report.getEvidenceItems()),
                safeList(report.getCitations()),
                history
        );
    }

    /** 将审核实体转换为公开历史条目。 */
    private InvestmentReportReviewSummary toReviewSummary(InvestmentReportReview review) {
        return new InvestmentReportReviewSummary(
                review.getId(),
                review.getReportVersionId(),
                review.getFromStatus(),
                review.getToStatus(),
                review.getComment(),
                review.getReviewer(),
                review.getCreatedAt()
        );
    }

    /** 按 userId 强制报告归属隔离，未命中统一返回资源不存在。 */
    private InvestmentReportVersion findOwnedReport(String userId, Long reportVersionId) {
        if (userId == null || userId.isBlank() || reportVersionId == null) {
            throw new ResourceNotFoundException("Investment report not found");
        }
        return repository.findByIdAndUserId(reportVersionId, userId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Investment report not found"));
    }

    /** 校验审核目标状态、乐观锁版本和评论长度。 */
    private void validateReviewRequest(InvestmentReportReviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Review request is required");
        }
        if (request.status() == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (request.expectedLockVersion() == null) {
            throw new IllegalArgumentException("expectedLockVersion is required");
        }
        if (request.comment() != null && request.comment().length() > 2000) {
            throw new IllegalArgumentException("comment must be at most 2000 characters");
        }
    }

    /** 限制审核状态机只能从 DRAFT 进入审核、从审核给结论、再回到审核。 */
    private boolean isAllowedTransition(
            InvestmentReportVersion.ReviewStatus fromStatus,
            InvestmentReportVersion.ReviewStatus toStatus
    ) {
        return switch (fromStatus) {
            case DRAFT -> toStatus == InvestmentReportVersion.ReviewStatus.IN_REVIEW;
            case IN_REVIEW -> toStatus == InvestmentReportVersion.ReviewStatus.APPROVED
                    || toStatus == InvestmentReportVersion.ReviewStatus.REJECTED
                    || toStatus == InvestmentReportVersion.ReviewStatus.NEEDS_RESEARCH;
            case APPROVED, REJECTED, NEEDS_RESEARCH ->
                    toStatus == InvestmentReportVersion.ReviewStatus.IN_REVIEW;
        };
    }

    /** 兼容旧行：缺失审核状态视为 DRAFT。 */
    private InvestmentReportVersion.ReviewStatus effectiveReviewStatus(
            InvestmentReportVersion.ReviewStatus status
    ) {
        return status == null ? InvestmentReportVersion.ReviewStatus.DRAFT : status;
    }

    /** 兼容旧行：缺失乐观锁版本视为 0。 */
    private long effectiveLockVersion(Long lockVersion) {
        return lockVersion == null ? 0L : lockVersion;
    }

    /** 构造统一的审核乐观锁冲突响应。 */
    private ResponseStatusException staleReview() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Investment report review changed; refresh and retry"
        );
    }

    /** 把数据库版本元数据写回结构化报告，供 API 和 Markdown 渲染统一消费。 */
    private void enrichReport(
            InvestmentReport report,
            String ticker,
            String dataSnapshotHash,
            String contextHash,
            Integer reportVersion,
            String modelTier,
            String modelName,
            boolean reusedFromCache
    ) {
        report.setTicker(ticker);
        report.setDataSnapshotHash(dataSnapshotHash);
        report.setContextHash(contextHash);
        report.setReportVersion(reportVersion);
        report.setModelTier(blankToNull(modelTier));
        report.setModelName(blankToNull(modelName));
        report.setReusedFromCache(reusedFromCache);
        if (report.getGeneratedAt() == null) {
            report.setGeneratedAt(LocalDateTime.now());
        }
    }

    /** 将完整结构化报告序列化为版本快照 JSON。 */
    private String writeReportJson(InvestmentReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize investment report", e);
        }
    }

    /** 优先用 analystSummary，否则取首条 rationale 构造列表预览。 */
    private String buildPreview(InvestmentReport report) {
        if (report == null) {
            return "";
        }
        if (report.getAnalystSummary() != null && !report.getAnalystSummary().isBlank()) {
            return truncate(report.getAnalystSummary(), 220);
        }
        if (report.getRationale() != null) {
            return report.getRationale().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .findFirst()
                    .map(item -> truncate(item, 220))
                    .orElse("");
        }
        return "";
    }

    /** 以带字段名的稳定行格式追加哈希输入，防止字段边界歧义。 */
    private void appendField(StringBuilder builder, String name, String value) {
        builder.append(name).append('=').append(blankToDefault(value, "")).append('\n');
    }

    /** 排序后追加列表，消除原集合遍历顺序对哈希的影响。 */
    private void appendSortedList(StringBuilder builder, String name, List<String> values) {
        builder.append(name).append('=');
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .sorted(Comparator.naturalOrder())
                    .forEach(value -> builder.append(value).append('|'));
        }
        builder.append('\n');
    }

    /** 生成 UTF-8 文本的十六进制 SHA-256。 */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    /** 归一化报告查询和唯一键中的 ticker。 */
    private String normalizeTicker(String value) {
        String normalized = blankToDefault(value, "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9.]", "");
        return ".".equals(normalized) ? "" : normalized;
    }

    /** 将历史查询条数收敛到默认值与最大值。 */
    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(limit, MAX_HISTORY_LIMIT);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 截断展示预览并追加省略号。 */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    /**
     * 一次版本持久化结果。
     *
     * @param report 新建或复用的报告
     * @param reportVersionId 版本实体 ID
     * @param reused 是否复用现有快照
     */
    public record PersistedReportVersion(InvestmentReport report, Long reportVersionId, boolean reused) {
    }

    /**
     * 报告详情聚合，包含正文、证据、引用和人工审核历史。
     *
     * @param summary 列表摘要及版本元数据
     * @param report 完整结构化报告
     * @param evidenceItems 证据表条目
     * @param citations 来源引用
     * @param reviewHistory 审核流转历史
     */
    public record ReportDetail(
            InvestmentReportVersionSummary summary,
            InvestmentReport report,
            List<InvestmentReport.EvidenceItem> evidenceItems,
            List<String> citations,
            List<InvestmentReportReviewSummary> reviewHistory
    ) {
        /**
         * 构造不含报告正文和审核历史的兼容详情。
         *
         * @param summary 列表摘要及版本元数据
         * @param evidenceItems 证据表条目
         * @param citations 来源引用
         */
        public ReportDetail(
                InvestmentReportVersionSummary summary,
                List<InvestmentReport.EvidenceItem> evidenceItems,
                List<String> citations
        ) {
            this(summary, null, evidenceItems, citations, List.of());
        }
    }

    /** 把可空列表转换为防御性只读副本。 */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
