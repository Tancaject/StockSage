package com.stocksage.knowledge;

import com.stocksage.tool.ToolCallContext;
import com.stocksage.exception.ResearchBudgetExceededException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.rag.ContextualEnricher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SEC EDGAR 财报入库服务。
 *
 * 采用父子分块策略：
 * - 父级切片（默认 3000 字符）：提供完整上下文，被子块引用
 * - 子级切片（默认 800 字符）：精细粒度，用于向量检索命中
 * - 优先在段落、换行、句子边界切分，并合并过短尾块
 *
 * 检索时命中子块 → 通过 parent_vector_id 追溯父块 → 返回完整上下文。
 * 这样兼顾了检索精度（小块语义更集中）和回答质量（大块上下文更完整）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdgarIngestionService {

    /** 父切片默认目标字符数。 */
    private static final int DEFAULT_PARENT_CHUNK_SIZE = 3000;
    /** 父切片默认重叠字符数。 */
    private static final int DEFAULT_PARENT_CHUNK_OVERLAP = 300;
    /** 子切片默认目标字符数。 */
    private static final int DEFAULT_CHILD_CHUNK_SIZE = 800;
    /** 子切片默认重叠字符数。 */
    private static final int DEFAULT_CHILD_CHUNK_OVERLAP = 120;
    /** 小于目标大小该比例的尾块会尝试并回前一块。 */
    private static final double SMALL_TAIL_RATIO = 0.25;
    /** 合并尾块后允许相对目标大小的最大比例。 */
    private static final double MAX_MERGED_CHUNK_RATIO = 1.15;
    /** 参与内容哈希的默认切片契约版本。 */
    private static final String DEFAULT_CHUNKING_VERSION = "edgar-v4-contextual-gist-child-vector";

    /** 调用 Python 服务获取公告索引和结构化章节正文。 */
    private final DataServiceClient dataServiceClient;
    /** 统一写入来源索引、MySQL 父/子镜像和 Milvus 子向量。 */
    private final KnowledgeIngestionService ingestionService;
    /** 解析 data-service 响应 JSON。 */
    private final ObjectMapper objectMapper;
    /** 可选为父块或子块生成上下文 gist。 */
    private final ContextualEnricher contextualEnricher;

    /** 可配置父切片大小。 */
    @Value("${stocksage.rag.edgar.parent-chunk-size:3000}")
    private int parentChunkSize = DEFAULT_PARENT_CHUNK_SIZE;

    /** 可配置父切片重叠。 */
    @Value("${stocksage.rag.edgar.parent-chunk-overlap:300}")
    private int parentChunkOverlap = DEFAULT_PARENT_CHUNK_OVERLAP;

    /** 可配置子切片大小。 */
    @Value("${stocksage.rag.edgar.child-chunk-size:800}")
    private int childChunkSize = DEFAULT_CHILD_CHUNK_SIZE;

    /** 可配置子切片重叠。 */
    @Value("${stocksage.rag.edgar.child-chunk-overlap:120}")
    private int childChunkOverlap = DEFAULT_CHILD_CHUNK_OVERLAP;

    /** 当前切片与 embedding 文本契约版本。 */
    @Value("${stocksage.rag.edgar.chunking-version:edgar-v4-contextual-gist-child-vector}")
    private String chunkingVersion = DEFAULT_CHUNKING_VERSION;

    /** contextual gist 由每个 parent 共享还是每个 child 单独生成。 */
    @Value("${stocksage.rag.contextual.granularity:child}")
    private String contextualGranularity = "child";

    /** Current ingestion defaults; historical documents retain their own provenance. */
    public Map<String, Object> runtimeConfiguration() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("scope", "CURRENT_INGESTION_DEFAULTS");
        settings.put("chunkingVersion", normalizeChunkingVersion());
        settings.put("parentSize", Math.max(1, parentChunkSize));
        settings.put("parentOverlap", Math.max(0, Math.min(parentChunkOverlap, Math.max(1, parentChunkSize) / 2)));
        settings.put("childSize", Math.max(1, childChunkSize));
        settings.put("childOverlap", Math.max(0, Math.min(childChunkOverlap, Math.max(1, childChunkSize) / 2)));
        settings.put("smallTailRatio", SMALL_TAIL_RATIO);
        settings.put("maxMergedChunkRatio", MAX_MERGED_CHUNK_RATIO);
        settings.put("contextualGranularity", "parent".equalsIgnoreCase(contextualGranularity) ? "parent" : "child");
        settings.put("contextual", contextualEnricher.runtimeConfiguration());
        return Map.copyOf(settings);
    }

    /**
     * 入库指定公司的最近 N 份财报。
     *
     * @param ticker 美股 ticker
     * @param filingType SEC 公告类型，如 10-K 或 10-Q
     * @param count 最近公告数量
     * @return 入库结果摘要
     */
    public Map<String, Object> ingestFilings(String ticker, String filingType, int count) {
        ToolCallContext.checkRunDeadline();
        log.info("Starting EDGAR ingestion: ticker={}, type={}, count={}", ticker, filingType, count);

        // 1. 获取公告文件索引
        // 调用 Python EDGAR 索引端点，Java 侧只负责编排切片和持久化。
        String filingsJson = dataServiceClient.getEdgarFilings(ticker, filingType, count);
        JsonNode filingsRoot = parseJson(filingsJson);
        if (filingsRoot == null || filingsRoot.has("error")) {
            String msg = filingsRoot != null ? filingsRoot.path("message").asText() : "Failed to fetch filings";
            log.error("EDGAR filings fetch failed: {}", msg);
            return Map.of("error", true, "message", msg);
        }

        String companyName = filingsRoot.path("company_name").asText(ticker);
        JsonNode filingsArray = filingsRoot.path("filings");

        List<Map<String, Object>> results = new ArrayList<>();
        int totalChunks = 0;

        // 2. 逐份公告文件下载并入库
        for (JsonNode filing : filingsArray) {
            ToolCallContext.checkRunDeadline();
            String documentUrl = filing.path("document_url").asText();
            String filingDate = filing.path("filing_date").asText();
            String accession = filing.path("accession_number").asText();

            String sourceId = String.format("edgar:%s:%s:%s", ticker.toUpperCase(), filingType, filingDate);

            try {
                int chunks = ingestSingleFiling(
                        sourceId, documentUrl, filingType,
                        ticker, companyName, filingDate, accession
                );
                totalChunks += chunks;
                results.add(Map.of(
                        "filing_date", filingDate,
                        "source_id", sourceId,
                        "chunks", chunks,
                        "status", "ingested"
                ));
            } catch (Exception e) {
                ResearchBudgetExceededException.rethrowIfPresent(e);
                ToolCallContext.checkRunDeadline();
                log.error("Failed to ingest filing {}: {}", sourceId, e.getMessage(), e);
                results.add(Map.of(
                        "filing_date", filingDate,
                        "source_id", sourceId,
                        "status", "failed",
                        "message", e.getMessage() != null ? e.getMessage() : "unknown error"
                ));
            }
        }

        log.info("EDGAR ingestion complete: ticker={}, filings={}, totalChunks={}",
                ticker, results.size(), totalChunks);

        return Map.of(
                "ticker", ticker.toUpperCase(),
                "company_name", companyName,
                "filing_type", filingType,
                "filings_processed", results.size(),
                "total_chunks", totalChunks,
                "details", results
        );
    }

    /**
     * 下载、切分并入库单份 SEC 公告。
     *
     * <p>该方法同时生成父级上下文切片和子级检索切片：父级用于回答时扩展上下文，
     * 子级用于向量召回命中。</p>
     *
     * @return 本次实际向量化的子切片数
     */
    private int ingestSingleFiling(
            String sourceId,
            String documentUrl,
            String filingType,
            String ticker,
            String companyName,
            String filingDate,
            String accession) {

        // 下载并解析公告文件
        // 调用 Python 公告解析端点，把 HTML 转为按 Item/章节组织的正文。
        String contentJson = dataServiceClient.getEdgarFilingContent(documentUrl, filingType);
        JsonNode contentRoot = parseJson(contentJson);
        if (contentRoot == null || contentRoot.has("error")) {
            throw new RuntimeException("Filing content fetch failed: "
                    + (contentRoot != null ? contentRoot.path("message").asText() : "parse error"));
        }

        JsonNode sections = contentRoot.path("sections");
        List<Document> allChunks = new ArrayList<>();
        int parentIndex = 0;

        // 父子分块策略
        for (JsonNode section : sections) {
            ToolCallContext.checkRunDeadline();
            String sectionName = section.path("section").asText();
            String content = section.path("content").asText();

            // 1. 生成父级切片（提供完整上下文）
            List<String> parentChunks = splitIntoChunks(content, parentChunkSize, parentChunkOverlap);

            for (int i = 0; i < parentChunks.size(); i++) {
                ToolCallContext.checkRunDeadline();
                String parentText = parentChunks.get(i);
                String parentVectorId = buildParentId(sourceId, sectionName, parentIndex);

                // 父级文档
                Map<String, Object> parentMeta = new LinkedHashMap<>();
                parentMeta.put("ticker", ticker.toUpperCase());
                parentMeta.put("company", companyName);
                parentMeta.put("filing_type", filingType);
                parentMeta.put("filing_date", filingDate);
                parentMeta.put("accession", accession);
                parentMeta.put("section", sectionName);
                parentMeta.put("chunk_of_section", i);
                parentMeta.put("source", "sec_edgar");
                parentMeta.put("is_parent", true);
                parentMeta.put("doc_id", parentVectorId);
                parentMeta.put("chunk_size", parentChunkSize);
                parentMeta.put("chunk_overlap", parentChunkOverlap);
                parentMeta.put("chunking_version", normalizeChunkingVersion());

                allChunks.add(new Document(parentVectorId, parentText, parentMeta));

                // 2. 生成子级切片（精细检索用）
                List<String> childChunks = splitIntoChunks(parentText, childChunkSize, childChunkOverlap);

                // Contextual Retrieval：parent 粒度时整个父块只生成一次 gist，由其子块共享
                String parentGist = null;
                boolean parentGranularity = "parent".equalsIgnoreCase(contextualGranularity);
                if (contextualEnricher.isEnabled() && parentGranularity) {
                    // 调用 ContextualEnricher 为整个父块生成一次 gist，供其全部子块复用。
                    parentGist = contextualEnricher.generateGist(
                            parentText, parentText, "parent",
                            ticker, companyName, filingType, filingDate, sectionName);
                }

                for (int j = 0; j < childChunks.size(); j++) {
                    ToolCallContext.checkRunDeadline();
                    String contextualGist = parentGist;
                    if (contextualEnricher.isEnabled() && !parentGranularity) {
                        contextualGist = contextualEnricher.generateGist(
                                parentText, childChunks.get(j), "child",
                                ticker, companyName, filingType, filingDate, sectionName);
                    }

                    Map<String, Object> childMeta = new LinkedHashMap<>();
                    childMeta.put("ticker", ticker.toUpperCase());
                    childMeta.put("company", companyName);
                    childMeta.put("filing_type", filingType);
                    childMeta.put("filing_date", filingDate);
                    childMeta.put("accession", accession);
                    childMeta.put("section", sectionName);
                    childMeta.put("source", "sec_edgar");
                    childMeta.put("is_parent", false);
                    childMeta.put("parent_vector_id", parentVectorId);
                    childMeta.put("child_index", j);
                    childMeta.put("chunk_size", childChunkSize);
                    childMeta.put("chunk_overlap", childChunkOverlap);
                    childMeta.put("chunking_version", normalizeChunkingVersion());
                    childMeta.put("embedding_context",
                            contextualGist == null || contextualGist.isBlank()
                                    ? "company_filing_section"
                                    : "company_filing_section_context");
                    if (contextualGist != null && !contextualGist.isBlank()) {
                        childMeta.put("contextual_gist", contextualGist);
                    }

                    allChunks.add(new Document(
                            buildChildEmbeddingText(
                                    childChunks.get(j),
                                    ticker,
                                    companyName,
                                    filingType,
                                    filingDate,
                                    accession,
                                    sectionName,
                                    contextualGist
                            ),
                            childMeta
                    ));
                }
                parentIndex++;
            }
        }

        if (allChunks.isEmpty()) {
            log.warn("No chunks extracted from filing: {}", sourceId);
            return 0;
        }

        // 入库（永久层，无 TTL，不做语义去重）
        // 调用统一摄取服务：父块仅写 MySQL，子块写 MySQL 并进入向量库。
        ToolCallContext.checkRunDeadline();
        var result = ingestionService.ingestDocuments(
                sourceId,
                buildContentHash(sourceId, filingDate, accession, documentUrl),
                allChunks,
                "edgar",
                null  // 无过期时间，永久保存
        );

        return result.chunksIngested();
    }

    /**
     * 构造父级切片 ID。
     *
     * <p>父级切片只持久化到 MySQL，用于子切片命中后的上下文扩展。</p>
     *
     * @return 由来源、章节和序号派生的稳定父切片 ID
     */
    private String buildParentId(String sourceId, String sectionName, int parentIndex) {
        return "parent_" + KnowledgeIngestionService.sha256(
                sourceId + ":" + sectionName + ":" + parentIndex).substring(0, 24);
    }

    /**
     * 构造来源内容哈希。
     *
     * <p>哈希中包含切片策略版本和参数，确保切片规则变化后会重新入库，而不是误判为来源未变化。</p>
     *
     * @return 来源和全部切片配置的 SHA-256
     */
    private String buildContentHash(String sourceId, String filingDate, String accession, String documentUrl) {
        return KnowledgeIngestionService.sha256(String.join("|",
                sourceId,
                filingDate,
                accession == null ? "" : accession,
                documentUrl == null ? "" : documentUrl,
                "chunkingVersion=" + normalizeChunkingVersion(),
                "parentSize=" + parentChunkSize,
                "parentOverlap=" + parentChunkOverlap,
                "childSize=" + childChunkSize,
                "childOverlap=" + childChunkOverlap,
                "smallTailRatio=" + SMALL_TAIL_RATIO,
                "maxMergedChunkRatio=" + MAX_MERGED_CHUNK_RATIO,
                "htmlParser=structured-markdown-like",
                "childEmbeddingText=company-filing-section-prefixed",
                "contextual=" + (contextualEnricher.isEnabled()
                        ? contextualGranularity.toLowerCase() : "off"),
                "parentIndex=mysql",
                "childIndex=milvus"
        ));
    }

    /** 归一化切片策略版本号。 */
    private String normalizeChunkingVersion() {
        return chunkingVersion == null || chunkingVersion.isBlank()
                ? DEFAULT_CHUNKING_VERSION
                : chunkingVersion.trim();
    }

    /**
     * 为子级切片构造带公司、公告和章节信息的 embedding 文本。
     *
     * <p>这些前缀能让短切片在向量空间中保留公司身份和财报上下文，降低跨公司误召回。
     * 启用 Contextual Retrieval 时再追加一行 LLM 情境说明（Context:），
     * 向量与 Lucene BM25 两条检索腿共用该文本。</p>
     *
     * @return 带公司、公告、章节和可选 gist 前缀的嵌入正文
     */
    private String buildChildEmbeddingText(
            String childText,
            String ticker,
            String companyName,
            String filingType,
            String filingDate,
            String accession,
            String sectionName,
            String contextualGist) {
        StringBuilder builder = new StringBuilder();
        builder.append("Company: ")
                .append(companyName == null || companyName.isBlank() ? ticker.toUpperCase() : companyName)
                .append(" (")
                .append(ticker.toUpperCase())
                .append(")\n");
        builder.append("Filing: ").append(filingType).append("\n");
        builder.append("Filing date: ").append(filingDate).append("\n");
        if (accession != null && !accession.isBlank()) {
            builder.append("Accession: ").append(accession).append("\n");
        }
        builder.append("Section: ").append(sectionName).append("\n");
        if (contextualGist != null && !contextualGist.isBlank()) {
            builder.append("Context: ").append(contextualGist.trim()).append("\n");
        }
        builder.append("\n");
        builder.append(childText == null ? "" : childText.trim());
        return builder.toString().trim();
    }

    /**
     * 按字符数分块，优先在段落、换行、句子边界处断开，并合并过短尾块。
     *
     * @param text 原始章节或父块正文
     * @param chunkSize 目标字符数
     * @param overlap 相邻块重叠字符数，最多为 chunkSize 一半
     * @return 保持原顺序的非空切片
     */
    static List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        String normalizedText = text == null ? "" : text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (normalizedText.isBlank()) {
            return List.of();
        }

        int normalizedChunkSize = Math.max(1, chunkSize);
        int normalizedOverlap = Math.max(0, Math.min(overlap, normalizedChunkSize / 2));
        if (normalizedText.length() <= normalizedChunkSize) {
            return List.of(normalizedText);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalizedText.length()) {
            int end = Math.min(start + normalizedChunkSize, normalizedText.length());

            if (end < normalizedText.length()) {
                int searchFrom = start + Math.max(normalizedChunkSize / 2, normalizedChunkSize * 2 / 3);
                int breakPoint = findBreakPoint(normalizedText, searchFrom, end);
                if (breakPoint > start) {
                    end = breakPoint;
                }
            }

            addChunk(chunks, normalizedText.substring(start, end).trim(), normalizedChunkSize);
            if (end >= normalizedText.length()) {
                break;
            }

            start = Math.max(start + 1, end - normalizedOverlap);
        }
        return chunks;
    }

    /**
     * 在给定窗口中寻找优先级最高的切分点。
     *
     * <p>优先段落、换行、句子边界，最后才退到任意空白字符。</p>
     */
    private static int findBreakPoint(String text, int searchFrom, int searchTo) {
        int from = Math.max(0, Math.min(searchFrom, text.length()));
        int to = Math.max(from, Math.min(searchTo, text.length()));

        int paragraphBreak = text.lastIndexOf("\n\n", Math.max(from, to - 1));
        if (paragraphBreak >= from && paragraphBreak < to) {
            return paragraphBreak + 2;
        }

        int lineBreak = text.lastIndexOf('\n', Math.max(from, to - 1));
        if (lineBreak >= from && lineBreak < to) {
            return lineBreak + 1;
        }

        for (int i = to - 1; i >= from; i--) {
            if (isSentenceBoundary(text, i)) {
                return i + 1;
            }
        }

        for (int i = to - 1; i >= from; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return to;
    }

    /**
     * 判断某个字符是否可以作为句子边界。
     */
    private static boolean isSentenceBoundary(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return false;
        }
        char c = text.charAt(index);
        if (".?!;:。！？；：".indexOf(c) < 0) {
            return false;
        }
        if (index + 1 >= text.length()) {
            return true;
        }
        char next = text.charAt(index + 1);
        return Character.isWhitespace(next) || next == '"' || next == '\'' || next == ')' || next == ']';
    }

    /**
     * 添加切片，并把过短尾块尽量合并回前一块。
     */
    private static void addChunk(List<String> chunks, String chunk, int chunkSize) {
        if (chunk == null || chunk.isBlank()) {
            return;
        }
        int minUsefulSize = Math.max(120, (int) Math.round(chunkSize * SMALL_TAIL_RATIO));
        if (!chunks.isEmpty() && chunk.length() < minUsefulSize) {
            int previousIndex = chunks.size() - 1;
            String merged = mergeOverlapping(chunks.get(previousIndex), chunk);
            if (merged.length() <= Math.round(chunkSize * MAX_MERGED_CHUNK_RATIO)) {
                chunks.set(previousIndex, merged);
                return;
            }
        }
        chunks.add(chunk);
    }

    /**
     * 合并两个可能存在重叠的切片文本。
     */
    private static String mergeOverlapping(String left, String right) {
        int maxOverlap = Math.min(Math.min(left.length(), right.length()), 500);
        for (int length = maxOverlap; length >= 20; length--) {
            if (left.regionMatches(left.length() - length, right, 0, length)) {
                return (left + right.substring(length)).trim();
            }
        }
        return (left + "\n\n" + right).trim();
    }

    /**
     * 安全解析 JSON，失败时返回 null 由调用方决定错误处理。
     */
    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to parse JSON response: {}", e.getMessage());
            return null;
        }
    }
}
