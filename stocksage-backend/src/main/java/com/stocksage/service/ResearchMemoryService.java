package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryEntry;
import com.stocksage.repository.ResearchMemoryEntryRepository;
import com.stocksage.trace.TraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ResearchMemoryEntryRepository repository;
    private final ResearchMemoryVectorIndex vectorIndex;
    private final ResearchMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final TraceService traceService;

    public ResearchMemoryService(
            ResearchMemoryEntryRepository repository,
            ResearchMemoryVectorIndex vectorIndex,
            ResearchMemoryProperties properties,
            ObjectMapper objectMapper,
            TraceService traceService
    ) {
        this.repository = repository;
        this.vectorIndex = vectorIndex;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
    }

    @EventListener
    public void onReportPersisted(InvestmentReportPersistedEvent event) {
        if (!properties.isCapture() || event == null) {
            return;
        }
        try {
            capture(event.source(), event.report());
        } catch (Exception error) {
            // Report persistence has already succeeded. Memory is strictly best effort.
            log.warn("Research memory capture failed. errorType={}", error.getClass().getSimpleName());
        }
    }

    @Transactional
    public ResearchMemoryEntry capture(InvestmentReportVersion source, InvestmentReport report) {
        if (!eligible(source, report)) {
            return null;
        }
        String sourceId = String.valueOf(source.getId());
        var existing = repository.findByUserIdAndSourceTypeAndSourceId(
                source.getUserId(), SOURCE_TYPE, sourceId);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<String> citations = sourceCitations(report);
        String text = buildMemoryText(source, report, citations);
        ResearchMemoryEntry entry = new ResearchMemoryEntry();
        entry.setUserId(source.getUserId());
        entry.setTicker(normalizeTicker(source.getTicker()));
        entry.setSourceType(SOURCE_TYPE);
        entry.setSourceId(sourceId);
        entry.setSourceConversationId(source.getConversationId());
        entry.setSourceCitations(writeJson(citations));
        entry.setDataCutoffAt(source.getGeneratedAt());
        entry.setSnapshotHash(source.getDataSnapshotHash());
        entry.setContentHash(sha256(text));
        entry.setMemoryText(text);
        entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.PENDING);
        try {
            entry = repository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException duplicate) {
            return repository.findByUserIdAndSourceTypeAndSourceId(
                    source.getUserId(), SOURCE_TYPE, sourceId).orElseThrow(() -> duplicate);
        }
        if (properties.isIndex()) {
            indexOne(entry);
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
            List<ResearchMemoryEntry> entries = repository.findByIdInAndUserIdAndRevokedAtIsNull(
                            scores.keySet(), userId.trim())
                    .stream()
                    .filter(ResearchMemoryEntry::active)
                    .sorted(Comparator.comparingDouble(
                            entry -> -scores.getOrDefault(entry.getId(), 0.0)))
                    .limit(3)
                    .toList();
            traceRetrieval(traceId, entries, scores, elapsedMs(startedAt));
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
        if (entry == null || !entry.active()) {
            return false;
        }
        boolean wasIndexed = entry.getVectorStatus() == ResearchMemoryEntry.VectorStatus.INDEXED;
        entry.setRevokedAt(LocalDateTime.now());
        entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.REVOKED);
        entry.setVectorErrorCode(null);
        repository.save(entry);
        if (wasIndexed) {
            try {
                vectorIndex.delete(entry.getId());
            } catch (Exception error) {
                log.warn("Research memory vector delete failed. entryId={}, errorType={}",
                        entry.getId(), error.getClass().getSimpleName());
            }
        }
        return true;
    }

    private void indexOne(ResearchMemoryEntry entry) {
        if (entry == null || !entry.active()) {
            return;
        }
        try {
            vectorIndex.index(entry);
            entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
            entry.setVectorErrorCode(null);
        } catch (Exception error) {
            entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.FAILED);
            entry.setVectorErrorCode("VECTOR_INDEX_FAILED");
        }
        repository.save(entry);
    }

    private boolean eligible(InvestmentReportVersion source, InvestmentReport report) {
        if (source == null || source.getId() == null || report == null
                || source.getUserId() == null || source.getUserId().isBlank()
                || "DEMO".equalsIgnoreCase(source.getModelTier())
                || "offline-rule-fallback".equalsIgnoreCase(source.getModelName())) {
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
