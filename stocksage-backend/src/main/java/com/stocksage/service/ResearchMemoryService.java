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

@Slf4j
@Service
public class ResearchMemoryService {

    private static final String SOURCE_TYPE = "INVESTMENT_REPORT_VERSION";
    private static final List<ResearchMemoryEntry.VectorStatus> INDEXABLE_VECTOR_STATUSES = List.of(
            ResearchMemoryEntry.VectorStatus.PENDING,
            ResearchMemoryEntry.VectorStatus.FAILED
    );
    private static final List<ResearchMemoryEntry.VectorStatus> SUCCESS_TRANSITION_STATUSES = List.of(
            ResearchMemoryEntry.VectorStatus.PENDING,
            ResearchMemoryEntry.VectorStatus.FAILED,
            ResearchMemoryEntry.VectorStatus.INDEXED
    );
    private final ResearchMemoryEntryRepository repository;
    private final InvestmentReportVersionRepository reportVersionRepository;
    private final ResearchMemoryVectorIndex vectorIndex;
    private final ResearchMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final TraceService traceService;
    private final TransactionTemplate transactionTemplate;

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

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void onReportPersisted(InvestmentReportPersistedEvent event) {
        if (!properties.isCapture() || event == null) {
            return;
        }
        try {
            // capture() is a self-invocation here, so its @Transactional annotation is not
            // intercepted. The explicit template also keeps transaction commit failures inside
            // this best-effort catch boundary.
            transactionTemplate.executeWithoutResult(
                    status -> capture(event.source(), event.report()));
        } catch (Exception error) {
            // Report persistence has already succeeded. Memory is strictly best effort.
            log.warn("Research memory capture failed. errorType={}", error.getClass().getSimpleName());
        }
    }

    @Transactional
    public ResearchMemoryEntry capture(InvestmentReportVersion source, InvestmentReport report) {
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

    public RetrievalResult retrieve(String userId, String ticker, String query, String traceId) {
        if (!properties.isRetrieve() || userId == null || userId.isBlank()
                || query == null || query.isBlank()) {
            return RetrievalResult.empty();
        }
        long startedAt = System.nanoTime();
        try {
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

    @Transactional(readOnly = true)
    public List<MemoryView> listForUser(String userId, int limit) {
        int capped = Math.max(1, Math.min(50, limit));
        return repository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(
                        userId.trim(), PageRequest.of(0, capped))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public boolean revoke(String userId, Long id) {
        ResearchMemoryEntry entry = repository.findByIdAndUserId(id, userId.trim()).orElse(null);
        return revokeEntry(entry);
    }

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

    private void deleteVectorBestEffort(Long entryId, String reason) {
        try {
            vectorIndex.delete(entryId);
        } catch (Exception error) {
            // Retrieval always joins vector hits back to active INDEXED database truth.
            log.warn("Research memory vector delete failed. entryId={}, reason={}, errorType={}",
                    entryId, reason, error.getClass().getSimpleName());
        }
    }

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
        traceService.addStep(traceId, AgentStep.builder()
                .thought("Retrieved sourced cross-session research memory.")
                .action("Research Memory Retrieval")
                .observation("Matched " + entries.size() + " sourced memory entries.")
                .durationMs(durationMs)
                .tokenCount(0)
                .attributes(Map.of("hits", safeHits, "injected", properties.isInject()))
                .build());
    }

    private int readCitationCount(String json) {
        try {
            return objectMapper.readTree(json).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private MemoryView toView(ResearchMemoryEntry entry) {
        return new MemoryView(
                entry.getId(), entry.getTicker(), entry.getSourceType(), entry.getSourceId(),
                entry.getMemoryText(), entry.getSourceCitations(), entry.getDataCutoffAt(),
                entry.getSnapshotHash(), entry.getVectorStatus(), entry.getCreatedAt()
        );
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("RESEARCH_MEMORY_CITATIONS_SERIALIZE_FAILED", error);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

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

    private long elapsedMs(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public record RetrievalResult(String promptContext, int hitCount, boolean injected) {
        public static RetrievalResult empty() {
            return new RetrievalResult("", 0, false);
        }
    }

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
