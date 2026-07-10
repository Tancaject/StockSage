package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.dto.InvestmentReportVersionSummary;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.repository.InvestmentReportVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentReportVersionService {

    private static final String PROMPT_CONTRACT_VERSION = "investment-report-v2-stable-news-excluded";
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 50;

    private final InvestmentReportVersionRepository repository;
    private final ObjectMapper objectMapper;

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
        appendField(canonical, "ticker", normalizeTicker(state == null ? null : state.getPrimaryTicker()));
        appendField(canonical, "fundamentals", state == null ? null : state.getFundamentalsReport());
        appendField(canonical, "market", state == null ? null : state.getMarketReport());
        // News/search payloads still feed the report, but not the reuse key because upstream ordering changes often.
        appendField(canonical, "newsPolicy", "excluded-from-cache-key");
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
                .map(entity -> toReport(entity, true));
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
                return new PersistedReportVersion(reused, raced.get().getId(), true);
            }
            throw e;
        }
        state.setInvestmentReport(report);
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
                .map(entity -> {
                    InvestmentReport report = toReport(entity, false);
                    return new ReportDetail(
                            toSummary(entity, report),
                            safeList(report.getEvidenceItems()),
                            safeList(report.getCitations())
                    );
                });
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
        return report;
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
                buildPreview(report)
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
            List<InvestmentReport.EvidenceItem> evidenceItems,
            List<String> citations
    ) {
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
