package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentReportVersionService {

    private static final String PROMPT_CONTRACT_VERSION = "investment-report-v3-harness-evidence-bound";
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 50;

    private final InvestmentReportVersionRepository repository;
    private final InvestmentReportReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public void prepareHashes(AnalysisState state) {
        if (state == null) {
            return;
        }
        state.setDataSnapshotHash(computeDataSnapshotHash(state));
        state.setContextHash(computeContextHash(state));
    }

    public String computeDataSnapshotHash(AnalysisState state) {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, "contract", PROMPT_CONTRACT_VERSION);
        appendField(canonical, "policyId", DeepResearchCompletionPolicy.POLICY_ID);
        appendField(canonical, "policyVersion",
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION));
        appendField(canonical, "ticker", normalizeTicker(state == null ? null : state.getPrimaryTicker()));
        if (state != null && state.getEvidenceLedger() != null) {
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
                                    item.status().name(),
                                    item.evidenceId(),
                                    item.payloadHash(),
                                    item.asOf() == null ? "" : item.asOf().toString())
                    ));
        }
        appendSortedList(canonical, "citations", state == null ? null : state.getCitations());
        return sha256(canonical.toString());
    }

    public String computeContextHash(AnalysisState state) {
        String dataHash = state == null ? "" : computeDataSnapshotHash(state);
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, "contract", PROMPT_CONTRACT_VERSION);
        appendField(canonical, "ticker", normalizeTicker(state == null ? null : state.getPrimaryTicker()));
        appendField(canonical, "query", state == null ? null : state.getQuery());
        appendField(canonical, "dataSnapshotHash", dataHash);
        return sha256(canonical.toString());
    }

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
        return repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                        userId,
                        ticker,
                        state.getDataSnapshotHash(),
                        state.getContextHash()
                )
                .map(entity -> toReport(entity, true))
                .filter(report -> reusableUnderCurrentPolicy(report, state));
    }

    public InvestmentReport persistReportVersion(
            String userId,
            Long conversationId,
            AnalysisState state,
            String modelTier,
            String modelName
    ) {
        return persistReportVersionWithMetadata(userId, conversationId, state, modelTier, modelName).report();
    }

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
            InvestmentReport reused = toReport(existing.get(), true);
            state.setInvestmentReport(reused);
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
                InvestmentReport reused = toReport(raced.get(), true);
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

    @Transactional(readOnly = true)
    public ReportDetail getReportDetail(String userId, Long reportVersionId) {
        InvestmentReportVersion entity = findOwnedReport(userId, reportVersionId);
        return toDetail(entity);
    }

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
        reviewRepository.saveAndFlush(review);

        return toDetail(entity);
    }

    @Transactional(readOnly = true)
    public Optional<String> findReportBrief(String userId, Long reportVersionId) {
        if (userId == null || userId.isBlank() || reportVersionId == null) {
            return Optional.empty();
        }
        return repository.findByIdAndUserId(reportVersionId, userId.trim())
                .map(entity -> buildPreview(toReport(entity, false)))
                .filter(brief -> !brief.isBlank());
    }

    private void prepareHashesIfMissing(AnalysisState state) {
        if (state.getDataSnapshotHash() == null || state.getDataSnapshotHash().isBlank()
                || state.getContextHash() == null || state.getContextHash().isBlank()) {
            prepareHashes(state);
        }
    }

    private InvestmentReport toReport(InvestmentReportVersion entity, boolean reusedFromCache) {
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
        enrichReport(report, entity.getTicker(), entity.getDataSnapshotHash(), entity.getContextHash(),
                entity.getReportVersion(), entity.getModelTier(), entity.getModelName(), reusedFromCache);
        if (report.getQualityStatus() == null) {
            report.setQualityStatus(InvestmentReport.ReportQualityStatus.LEGACY_UNVERIFIED);
        }
        return report;
    }

    private boolean reusableUnderCurrentPolicy(InvestmentReport report, AnalysisState state) {
        if (report == null
                || report.getQualityStatus() != InvestmentReport.ReportQualityStatus.VERIFIED
                || !DeepResearchCompletionPolicy.POLICY_ID.equals(report.getCompletionPolicyId())
                || !Integer.valueOf(DeepResearchCompletionPolicy.POLICY_VERSION)
                .equals(report.getCompletionPolicyVersion())
                || state.getEvidenceLedger() == null) {
            return false;
        }
        Set<String> knownEvidenceIds = state.getEvidenceLedger().evidenceIds();
        List<InvestmentReport.EvidenceItem> evidenceItems = safeList(report.getEvidenceItems());
        return !evidenceItems.isEmpty()
                && evidenceItems.stream().allMatch(item ->
                item != null
                        && item.getSourceEvidenceIds() != null
                        && !item.getSourceEvidenceIds().isEmpty()
                        && knownEvidenceIds.containsAll(item.getSourceEvidenceIds()));
    }

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

    private InvestmentReportVersion findOwnedReport(String userId, Long reportVersionId) {
        if (userId == null || userId.isBlank() || reportVersionId == null) {
            throw new ResourceNotFoundException("Investment report not found");
        }
        return repository.findByIdAndUserId(reportVersionId, userId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Investment report not found"));
    }

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

    private InvestmentReportVersion.ReviewStatus effectiveReviewStatus(
            InvestmentReportVersion.ReviewStatus status
    ) {
        return status == null ? InvestmentReportVersion.ReviewStatus.DRAFT : status;
    }

    private long effectiveLockVersion(Long lockVersion) {
        return lockVersion == null ? 0L : lockVersion;
    }

    private ResponseStatusException staleReview() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Investment report review changed; refresh and retry"
        );
    }

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

    private String writeReportJson(InvestmentReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize investment report", e);
        }
    }

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

    private void appendField(StringBuilder builder, String name, String value) {
        builder.append(name).append('=').append(blankToDefault(value, "")).append('\n');
    }

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

    private String normalizeTicker(String value) {
        String normalized = blankToDefault(value, "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9.]", "");
        return ".".equals(normalized) ? "" : normalized;
    }

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

    public record PersistedReportVersion(InvestmentReport report, Long reportVersionId, boolean reused) {
    }

    public record ReportDetail(
            InvestmentReportVersionSummary summary,
            InvestmentReport report,
            List<InvestmentReport.EvidenceItem> evidenceItems,
            List<String> citations,
            List<InvestmentReportReviewSummary> reviewHistory
    ) {
        public ReportDetail(
                InvestmentReportVersionSummary summary,
                List<InvestmentReport.EvidenceItem> evidenceItems,
                List<String> citations
        ) {
            this(summary, null, evidenceItems, citations, List.of());
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
