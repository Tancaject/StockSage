package com.stocksage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.CompanyRelation;
import com.stocksage.model.entity.VectorDocument;
import com.stocksage.repository.CompanyRelationRepository;
import com.stocksage.repository.VectorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 公司关系抽取服务（关系图谱构建的离线一刀）。
 *
 * <p>从已入库的【最新一份 10-K】的 Item 1. Business 父级切片中，用 LLM 抽取主体公司
 * 与其它实体的 竞争/客户/供应/合作 关系，每条边落库时携带逐字证据片段与出处。
 * 取数侧已收窄到最新年报的业务章节（见 {@code findLatest10KBusinessParents}），
 * 抽取侧再做关键词预筛 + 切片数上限，把单次抽取压到可控的数分钟。
 * 三道防幻觉闸门，缺一不可：</p>
 * <ol>
 *   <li>类型白名单——只接受四类已知关系；</li>
 *   <li>证据逐字命中——quote 必须能在来源原文中原样找到，杜绝模型脑补的边；</li>
 *   <li>置信度阈值——低于阈值的关系丢弃。</li>
 * </ol>
 *
 * <p>抽取按 ticker 幂等刷新：先清理旧边，再写入本次结果。</p>
 */
@Slf4j
@Service
public class RelationExtractionService {

    private static final Set<String> ALLOWED_TYPES =
            Set.of("COMPETITOR", "CUSTOMER", "SUPPLIER", "PARTNER");

    /** 太短的"引文"不足以作为证据，避免用一两个词去碰瓷原文。 */
    private static final int MIN_EVIDENCE_LENGTH = 8;

    /** 关系信号词：切片正文不含任一信号词时直接跳过，省掉注定无产出的 LLM 调用。 */
    private static final List<String> RELATION_SIGNALS = List.of(
            "compet", "supplier", "supply", "foundr", "fabricat", "manufactur",
            "customer", "partner", "collaborat", "vendor", "outsourc");

    /** 单次抽取最多送入 LLM 的切片数上限，给抽取耗时兜底（即便某标的业务章节异常庞大）。 */
    private static final int MAX_SOURCE_CHUNKS = 25;

    private final ChatClient chatClient;
    private final VectorDocumentRepository vectorDocumentRepository;
    private final CompanyRelationRepository companyRelationRepository;
    private final ObjectMapper objectMapper;

    @Value("${stocksage.relations.min-confidence:0.5}")
    private double minConfidence;

    @Value("${stocksage.relations.model-name:relation-extraction}")
    private String modelName;

    /**
     * 注入专用于关系抽取的 STANDARD 档 ChatClient，与主聊天链路隔离。
     */
    public RelationExtractionService(
            @Qualifier("relationExtractionChatClient") ChatClient chatClient,
            VectorDocumentRepository vectorDocumentRepository,
            CompanyRelationRepository companyRelationRepository,
            ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.vectorDocumentRepository = vectorDocumentRepository;
        this.companyRelationRepository = companyRelationRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> extractForTicker(String ticker) {
        return extractForTicker(ticker, null);
    }

    /**
     * 为指定 ticker 抽取并刷新公司关系图谱。
     *
     * @param progressCallback 可选的进度回调，每处理完一个切片后调用
     * @return 抽取结果摘要（来源切片数、候选数、采纳数、各类丢弃计数与关系列表）
     */
    public Map<String, Object> extractForTicker(String ticker, Consumer<Map<String, Object>> progressCallback) {
        String normTicker = ticker == null ? "" : ticker.trim().toUpperCase();
        if (normTicker.isEmpty()) {
            return Map.of("error", true, "message", "ticker is required");
        }

        List<VectorDocument> parents = vectorDocumentRepository.findLatest10KBusinessParents(normTicker);
        if (parents.isEmpty()) {
            log.warn("No latest-10-K Item 1. Business parent chunks for {}; ingest the 10-K first.", normTicker);
            return Map.of(
                    "ticker", normTicker,
                    "error", true,
                    "message", "未找到该标的最新 10-K 的 Item 1. Business 章节，请先入库 10-K 再抽取关系");
        }

        List<CompanyRelation> accepted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int candidateCount = 0;
        int droppedNoEvidence = 0;
        int droppedLowConfidence = 0;
        int droppedBadType = 0;
        int skippedNoSignal = 0;
        int processedChunks = 0;
        String sourceAccession = "";

        // 预扫描计算需要 LLM 处理的切片总数，用于进度汇报
        int totalEligible = 0;
        for (VectorDocument p : parents) {
            if (totalEligible >= MAX_SOURCE_CHUNKS) break;
            String t = p.getContentFull() != null ? p.getContentFull() : p.getContentPreview();
            if (t != null && !t.isBlank() && hasRelationSignal(t)) totalEligible++;
        }

        for (VectorDocument parent : parents) {
            if (processedChunks >= MAX_SOURCE_CHUNKS) {
                break;
            }
            String text = parent.getContentFull() != null ? parent.getContentFull() : parent.getContentPreview();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!hasRelationSignal(text)) {
                skippedNoSignal++;
                continue;
            }
            processedChunks++;
            Map<String, Object> meta = parseMetadata(parent.getMetadata());
            String company = str(meta.get("company"));
            String section = str(meta.get("section"));
            String accession = str(meta.get("accession"));
            String filingDate = str(meta.get("filing_date"));
            String docId = str(meta.get("doc_id"));
            if (sourceAccession.isEmpty()) {
                sourceAccession = accession;
            }

            for (JsonNode candidate : callExtraction(normTicker, company, section, text)) {
                candidateCount++;
                String type = candidate.path("type").asText("").trim().toUpperCase();
                String target = candidate.path("target").asText("").trim();
                String quote = candidate.path("quote").asText("").trim();
                double confidence = candidate.path("confidence").asDouble(0.0);

                if (!ALLOWED_TYPES.contains(type)) {
                    droppedBadType++;
                    continue;
                }
                // 反幻觉核心：证据必须能在原文中逐字命中，否则视为模型脑补
                if (target.isBlank() || !containsVerbatim(text, quote)) {
                    droppedNoEvidence++;
                    continue;
                }
                if (confidence < minConfidence) {
                    droppedLowConfidence++;
                    continue;
                }
                if (!seen.add(type + "::" + target.toLowerCase())) {
                    continue;
                }

                CompanyRelation relation = new CompanyRelation();
                relation.setSourceTicker(normTicker);
                relation.setSourceName(company);
                relation.setTargetName(target);
                relation.setTargetTicker(null);
                relation.setRelationType(type);
                relation.setConfidence(confidence);
                relation.setEvidenceSnippet(quote);
                relation.setEvidenceDocId(docId);
                relation.setEvidenceAccession(accession);
                relation.setEvidenceSection(section);
                relation.setFilingDate(filingDate);
                relation.setExtractionModel(modelName);
                accepted.add(relation);
            }

            if (progressCallback != null) {
                progressCallback.accept(Map.of(
                        "phase", "extracting",
                        "processed", processedChunks,
                        "total", totalEligible,
                        "accepted", accepted.size()));
            }
        }

        // 幂等刷新：先清理旧边再写入本次结果
        companyRelationRepository.deleteBySourceTicker(normTicker);
        companyRelationRepository.saveAll(accepted);

        log.info("Relation extraction {} (accession={}): fetched={}, processed={}, skippedNoSignal={}, "
                        + "candidates={}, accepted={}, droppedNoEvidence={}, droppedLowConfidence={}, droppedBadType={}",
                normTicker, sourceAccession, parents.size(), processedChunks, skippedNoSignal,
                candidateCount, accepted.size(), droppedNoEvidence, droppedLowConfidence, droppedBadType);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ticker", normTicker);
        summary.put("sourceAccession", sourceAccession);
        summary.put("sourceChunks", parents.size());
        summary.put("processedChunks", processedChunks);
        summary.put("skippedNoSignal", skippedNoSignal);
        summary.put("candidates", candidateCount);
        summary.put("accepted", accepted.size());
        summary.put("droppedNoEvidence", droppedNoEvidence);
        summary.put("droppedLowConfidence", droppedLowConfidence);
        summary.put("droppedBadType", droppedBadType);
        summary.put("relations", accepted.stream().map(this::toView).toList());
        return summary;
    }

    /**
     * 调用 LLM 抽取单个切片的关系候选，返回 JSON 数组节点；任何失败都返回空列表（fail-open）。
     */
    private List<JsonNode> callExtraction(String ticker, String company, String section, String text) {
        try {
            String raw = chatClient.prompt()
                    .user(buildUserPrompt(ticker, company, section, text))
                    .call()
                    .content();
            JsonNode array = objectMapper.readTree(extractJsonArray(raw));
            if (array == null || !array.isArray()) {
                return List.of();
            }
            List<JsonNode> candidates = new ArrayList<>();
            array.forEach(candidates::add);
            return candidates;
        } catch (Exception e) {
            log.warn("Relation extraction failed for {} section '{}': {}", ticker, section, e.getMessage());
            return List.of();
        }
    }

    /**
     * 构造抽取用户提示词：主体公司、来源章节、财报原文。
     */
    private String buildUserPrompt(String ticker, String company, String section, String text) {
        return """
                主体公司：%s（%s）
                来源章节：%s

                财报原文：
                <filing>
                %s
                </filing>

                按系统规则抽取关系，只输出 JSON 数组。
                """.formatted(
                company == null || company.isBlank() ? ticker : company,
                ticker, section, text);
    }

    /**
     * 从模型输出中截取 JSON 数组：去掉代码块围栏与数组前后的多余文字。
     */
    private String extractJsonArray(String raw) {
        if (raw == null) {
            return "[]";
        }
        String stripped = raw.strip()
                .replaceAll("(?is)^```[a-zA-Z]*\\s*", "")
                .replaceAll("(?is)\\s*```$", "")
                .trim();
        int start = stripped.indexOf('[');
        int end = stripped.lastIndexOf(']');
        if (start >= 0 && end >= start) {
            return stripped.substring(start, end + 1);
        }
        return "[]";
    }

    /**
     * 切片正文是否含至少一个关系信号词（大小写不敏感）。
     *
     * <p>不含信号词的切片（如纯粹的"人力资本""可获取信息"等小节）即便送进 LLM 也抽不出
     * 命名关系，这里提前跳过以压缩抽取耗时与成本。</p>
     */
    private boolean hasRelationSignal(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : RELATION_SIGNALS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断证据片段是否在原文中逐字出现（容忍空白差异）。
     *
     * <p>这是整条管线的防幻觉命门：模型若改写、翻译或凭空生成引文，这里就拦下。</p>
     */
    private boolean containsVerbatim(String source, String quote) {
        if (source == null || quote == null) {
            return false;
        }
        String normalizedQuote = quote.replaceAll("\\s+", " ").trim();
        if (normalizedQuote.length() < MIN_EVIDENCE_LENGTH) {
            return false;
        }
        return source.replaceAll("\\s+", " ").contains(normalizedQuote);
    }

    /**
     * 解析 MySQL 中保存的切片元数据 JSON。
     */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Failed to parse vector document metadata: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 把一条关系边整理成对外视图。
     */
    private Map<String, Object> toView(CompanyRelation relation) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("target", relation.getTargetName());
        view.put("type", relation.getRelationType());
        view.put("confidence", relation.getConfidence());
        view.put("section", relation.getEvidenceSection());
        view.put("accession", relation.getEvidenceAccession());
        view.put("filingDate", relation.getFilingDate());
        view.put("snippet", relation.getEvidenceSnippet());
        return view;
    }

    /**
     * 空安全地把元数据值转成字符串。
     */
    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
