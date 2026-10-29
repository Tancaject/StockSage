package com.stocksage.knowledge;

import static com.stocksage.knowledge.ResearchMemoryService.SOURCE_TYPE;
import static com.stocksage.knowledge.ResearchMemoryService.effectiveHorizon;
import static com.stocksage.knowledge.ResearchMemoryService.effectiveReferenceAt;
import static com.stocksage.knowledge.ResearchMemoryService.sourceId;
import static com.stocksage.knowledge.ResearchMemoryService.sourceReports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.runtime.AgentStep;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryConflictGroup;
import com.stocksage.model.entity.ResearchMemoryEntry;
import com.stocksage.repository.InvestmentReportVersionRepository;
import com.stocksage.repository.ResearchMemoryConflictGroupRepository;
import com.stocksage.repository.ResearchMemoryEntryRepository;
import com.stocksage.trace.TraceService;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 研究记忆的读路径：向量召回、MySQL 真源回查、审核资格与冲突门禁、时间衰减排序，
 * 以及在提示词预算内拼装历史背景并把安全指标写入 Trace。
 *
 * <p>任何阶段失败都返回空记忆，不阻断主回答；写入、撤销和冲突调和由 {@link ResearchMemoryService} 负责。</p>
 */
@Slf4j
final class ResearchMemoryRetriever {

    /** 记忆行的数据库真源。 */
    private final ResearchMemoryEntryRepository repository;
    /** 读取冲突组当前赢家与未消解状态。 */
    private final ResearchMemoryConflictGroupRepository conflictGroupRepository;
    /** 回查来源报告的当前审核状态。 */
    private final InvestmentReportVersionRepository reportVersionRepository;
    /** 执行 Milvus 向量检索。 */
    private final ResearchMemoryVectorIndex vectorIndex;
    /** 检索、注入开关与预算。 */
    private final ResearchMemoryProperties properties;
    /** 读取来源引用 JSON。 */
    private final ObjectMapper objectMapper;
    /** 把检索命中和注入状态写入当前 Agent Trace。 */
    private final TraceService traceService;
    /** 对真源和冲突门禁后的候选执行时间衰减与自适应截断。 */
    private final ResearchMemoryRanker ranker;

    ResearchMemoryRetriever(
            ResearchMemoryEntryRepository repository,
            ResearchMemoryConflictGroupRepository conflictGroupRepository,
            InvestmentReportVersionRepository reportVersionRepository,
            ResearchMemoryVectorIndex vectorIndex,
            ResearchMemoryProperties properties,
            ObjectMapper objectMapper,
            TraceService traceService,
            ResearchMemoryRanker ranker
    ) {
        this.repository = repository;
        this.conflictGroupRepository = conflictGroupRepository;
        this.reportVersionRepository = reportVersionRepository;
        this.vectorIndex = vectorIndex;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.ranker = ranker;
    }

    ResearchMemoryService.RetrievalResult retrieve(ResearchMemoryQuery query) {
        if (!properties.isRetrieve() || query == null
                || query.userId().isBlank() || query.query().isBlank()) {
            return ResearchMemoryService.RetrievalResult.empty();
        }
        long startedAt = System.nanoTime();
        try {
            List<ResearchMemoryVectorIndex.Hit> hits = vectorIndex.search(
                    query.userId(), query.ticker(), query.query(), properties.getCandidateK());
            Map<Long, Double> scores = new LinkedHashMap<>();
            hits.forEach(hit -> scores.merge(hit.entryId(), hit.score(), Math::max));
            if (scores.isEmpty()) {
                traceRetrieval(query, 0, 0, 0, 0,
                        List.of(), Map.of(), 0, elapsedMs(startedAt));
                return ResearchMemoryService.RetrievalResult.empty();
            }

            List<ResearchMemoryEntry> truthEntries = repository
                    .findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                            scores.keySet(), query.userId(), ResearchMemoryEntry.VectorStatus.INDEXED)
                    .stream()
                    .filter(ResearchMemoryEntry::active)
                    .filter(entry -> entry.getVectorStatus() == ResearchMemoryEntry.VectorStatus.INDEXED)
                    .toList();
            int truthCount = truthEntries.size();
            Map<Long, InvestmentReportVersion> sources = sourceReports(reportVersionRepository, query.userId(), truthEntries);
            Map<Long, MemoryQualification> qualifications = new LinkedHashMap<>();
            truthEntries.forEach(entry -> qualifications.put(entry.getId(), qualification(entry, sources)));
            truthEntries = truthEntries.stream()
                    .filter(entry -> !qualifications.get(entry.getId()).negativeReview())
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
            String prompt = properties.isInject()
                    ? buildPrompt(selected, rankedById, qualifications, includeConflictWarning)
                    : "";

            traceRetrieval(
                    query,
                    hits.size(),
                    truthCount,
                    conflictGate.currentWinners().size(),
                    conflictGate.unresolvedGroupCount(),
                    ranked,
                    qualifications,
                    prompt.length(),
                    elapsedMs(startedAt));
            return new ResearchMemoryService.RetrievalResult(prompt);
        } catch (Exception error) {
            log.warn("Research memory retrieval failed. errorType={}",
                    error.getClass().getSimpleName());
            return ResearchMemoryService.RetrievalResult.empty();
        }
    }

    /** 资格从来源关系和当前报告读取，不把审核状态复制到向量或记忆正文中。 */
    private MemoryQualification qualification(ResearchMemoryEntry entry,
                                             Map<Long, InvestmentReportVersion> sources) {
        boolean reportSource = SOURCE_TYPE.equals(entry.getSourceType());
        Long reportId = reportSource ? sourceId(entry) : null;
        InvestmentReportVersion source = reportId == null ? null : sources.get(reportId);
        return new MemoryQualification(
                reportSource ? "HISTORICAL_GENERATED_CONCLUSION" : "UNKNOWN",
                reportId,
                source == null || source.getReviewStatus() == null ? "UNKNOWN" : source.getReviewStatus().name(),
                source == null ? null : source.getGeneratedAt(),
                false);
    }

    /** 当前资格快照；人工审核不改变生成结论的知识类别。 */
    public record MemoryQualification(String knowledgeKind, Long sourceReportId, String reviewStatus,
                                      LocalDateTime reportGeneratedAt, boolean currentEvidence) {
        private boolean negativeReview() {
            return "REJECTED".equals(reviewStatus) || "NEEDS_RESEARCH".equals(reviewStatus);
        }
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

    /** 在总预算内为每条最终记忆动态分配空间，避免选中后又静默丢弃。 */
    private String buildPrompt(
            List<ResearchMemoryEntry> entries,
            Map<Long, ResearchMemoryRanker.ScoredMemory> rankedById,
            Map<Long, MemoryQualification> qualifications,
            boolean includeConflictWarning
    ) {
        if ((entries == null || entries.isEmpty()) && !includeConflictWarning) {
            return "";
        }
        StringBuilder prompt = new StringBuilder("""
                以下内容是历史生成结论，不是原始来源事实或用户确认事实，只能用于回顾历史判断、变化和未解决问题。审核通过也不使其成为当前事实；不得计入当前证据充分性或用 [M] 代替当前来源引用。若与当前 RAG 或工具数据冲突，以当前证据为准。报告生成时间不代表底层证据业务时点。
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
            // 为每条记录前的换行预留一个字符，确保记录编号只统计完整写入的记录。
            int distributableBudget = remainingBudget - remainingEntries;
            if (distributableBudget < 100) {
                break;
            }
            int rowBudget = Math.min(1000, distributableBudget / remainingEntries);
            ResearchMemoryEntry entry = safeEntries.get(index);
            String row = promptRow(entry, rankedById.get(entry.getId()), qualifications.get(entry.getId()), included + 1, rowBudget);
            if (row.isBlank()) {
                continue;
            }
            prompt.append('\n').append(row);
            included++;
        }
        return prompt.toString().trim();
    }

    /** 先保留结构化元数据和来源，再用剩余预算压缩正文。 */
    private String promptRow(
            ResearchMemoryEntry entry,
            ResearchMemoryRanker.ScoredMemory scored,
            MemoryQualification qualification,
            int index,
            int maxChars
    ) {
        String metadata = "[M%d] 标的=%s；期限=%s；建议=%s；参考时点=%s".formatted(
                index,
                entry.getTicker(),
                effectiveHorizon(entry.getAnalysisHorizon()),
                entry.getRecommendation() == null ? "未知" : entry.getRecommendation(),
                effectiveReferenceAt(entry) == null ? "未知" : effectiveReferenceAt(entry));
        metadata += "；类型=" + qualification.knowledgeKind()
                + "；来源报告=" + (qualification.sourceReportId() == null ? "未知" : qualification.sourceReportId())
                + "；审核=" + qualification.reviewStatus()
                + "；当前证据=false；报告生成时间="
                + (qualification.reportGeneratedAt() == null ? "未知" : qualification.reportGeneratedAt());
        // 资格头不能被裁成看似独立的事实；空间不足时整条不注入。
        if (metadata.length() > maxChars) return "";
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
            Map<Long, MemoryQualification> qualifications,
            int promptChars,
            long durationMs
    ) {
        if (query == null || query.traceId().isBlank()) {
            return;
        }
        List<ResearchMemoryEntry> selected = ranked.stream()
                .map(ResearchMemoryRanker.ScoredMemory::entry)
                .toList();
        List<Map<String, Object>> safeHits = safeTraceHits(ranked, qualifications);
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
            List<ResearchMemoryRanker.ScoredMemory> ranked,
            Map<Long, MemoryQualification> qualifications
    ) {
        return ranked.stream().map(scored -> {
            ResearchMemoryEntry entry = scored.entry();
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("entryId", entry.getId());
            hit.put("qualification", qualifications.get(entry.getId()));
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
}
