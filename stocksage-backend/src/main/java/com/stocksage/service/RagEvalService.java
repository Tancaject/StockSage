package com.stocksage.service;

import com.stocksage.model.dto.RagEvalCitation;
import com.stocksage.model.dto.RagEvalContext;
import com.stocksage.model.dto.RagEvalRequest;
import com.stocksage.model.dto.RagEvalResponse;
import com.stocksage.model.dto.RagEvalRetrievalDetails;
import com.stocksage.rag.RagRetrievalEvaluation;
import com.stocksage.rag.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 完整 RAG 评估的后端适配器。
 *
 * <p>评估脚本通过 /api/eval/rag 调用本服务，收集打分所需的精确检索上下文、
 * 生成回答、引用信息，以及可选的中间检索阶段。</p>
 */
@Service
public class RagEvalService {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final RagService ragService;
    private final ChatClient ragEvalChatClient;

    /**
     * 注入 RAG 服务和评测专用回答模型。
     */
    public RagEvalService(RagService ragService,
                          @Qualifier("ragEvalChatClient") ChatClient ragEvalChatClient) {
        this.ragService = ragService;
        this.ragEvalChatClient = ragEvalChatClient;
    }

    /**
     * 端到端运行一个评估用例：检索、仅基于上下文回答、解析引用，
     * 并返回足够的元数据给 Python 打分器。
     */
    public RagEvalResponse evaluate(RagEvalRequest request) {
        RagRetrievalEvaluation retrieval = ragService.retrieveForEval(request.question());
        int maxContextChars = request.maxContextCharsOrDefault();

        List<RagEvalContext> finalContexts = toContexts(retrieval.finalContexts(), maxContextChars);
        String answer = generateAnswer(request.question(), finalContexts);
        List<RagEvalCitation> citations = extractCitations(answer, finalContexts);
        RagEvalRetrievalDetails details = toRetrievalDetails(retrieval, request.includeIntermediateOrDefault(), maxContextChars);

        return new RagEvalResponse(
                UUID.randomUUID().toString(),
                request.question(),
                request.referenceAnswer(),
                request.answerable(),
                retrieval.rewrittenQuery(),
                retrieval.filterExpression(),
                finalContexts,
                answer,
                citations,
                details
        );
    }

    /**
     * 评估回答刻意限制为只使用检索上下文，
     * 让回答质量指标反映 RAG 行为，而不是模型通用知识。
     */
    private String generateAnswer(String question, List<RagEvalContext> contexts) {
        String prompt = """
                Question:
                %s

                Retrieved SEC filing contexts:
                %s

                Instructions:
                - Answer in the same language as the question when possible.
                - Use only the retrieved contexts.
                - Add bracket citations like [1] after claims supported by a context.
                - If the contexts are insufficient, say the filing context does not disclose the requested information.
                """.formatted(question, buildNumberedContext(contexts));

        return ragEvalChatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * 将评测上下文拼成带编号的提示词片段。
     *
     * <p>编号与引用格式 [1]、[2] 对齐，方便后续从回答中反查引用来源。</p>
     */
    private String buildNumberedContext(List<RagEvalContext> contexts) {
        StringBuilder builder = new StringBuilder();
        for (RagEvalContext context : contexts) {
            builder.append("[")
                    .append(context.rank())
                    .append("] ");
            appendPart(builder, "ticker", context.ticker());
            appendPart(builder, "filing_type", context.filingType());
            appendPart(builder, "filing_date", context.filingDate());
            appendPart(builder, "section", context.section());
            appendPart(builder, "source_id", context.sourceId());
            builder.append("\n")
                    .append(context.content())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    /**
     * 构造可选的检索阶段诊断详情。
     *
     * <p>includeIntermediate 为 false 时只返回计数，避免评测响应过大。</p>
     */
    private RagEvalRetrievalDetails toRetrievalDetails(RagRetrievalEvaluation retrieval,
                                                       boolean includeIntermediate,
                                                       int maxContextChars) {
        int intermediateMaxChars = Math.min(maxContextChars, 2000);
        return new RagEvalRetrievalDetails(
                retrieval.originalQuery(),
                retrieval.rewrittenQuery(),
                retrieval.filterExpression(),
                retrieval.hybridSearchEnabled(),
                retrieval.rerankEnabled(),
                retrieval.topK(),
                retrieval.candidateTopK(),
                retrieval.vectorCandidates().size(),
                retrieval.keywordCandidates().size(),
                retrieval.fusedCandidates().size(),
                retrieval.rerankedContexts().size(),
                retrieval.finalContexts().size(),
                includeIntermediate ? toContexts(retrieval.vectorCandidates(), intermediateMaxChars) : List.of(),
                includeIntermediate ? toContexts(retrieval.keywordCandidates(), intermediateMaxChars) : List.of(),
                includeIntermediate ? toContexts(retrieval.fusedCandidates(), intermediateMaxChars) : List.of(),
                includeIntermediate ? toContexts(retrieval.rerankedContexts(), intermediateMaxChars) : List.of()
        );
    }

    /**
     * 将 Spring AI Document 列表转换为评测上下文 DTO。
     */
    private List<RagEvalContext> toContexts(List<Document> documents, int maxContentChars) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<RagEvalContext> contexts = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            contexts.add(toContext(documents.get(index), index + 1, maxContentChars));
        }
        return contexts;
    }

    /**
     * 将单个 Document 转换为带排名和归一化元数据的上下文。
     */
    private RagEvalContext toContext(Document doc, int rank, int maxContentChars) {
        Map<String, Object> metadata = doc.getMetadata();
        return new RagEvalContext(
                rank,
                truncate(safe(doc.getText()), maxContentChars),
                metadata,
                metadataValue(metadata, "ticker"),
                metadataValue(metadata, "filing_type"),
                metadataValue(metadata, "filing_date", "date", "ingested_date"),
                metadataValue(metadata, "section", "section_title"),
                metadataValue(metadata, "source_id", "source"),
                metadataValue(metadata, "doc_id"),
                documentScore(doc),
                metadataDouble(metadata, "rerank_score")
        );
    }

    /**
     * 从模型回答中提取方括号引用，并映射回上下文元数据。
     */
    private List<RagEvalCitation> extractCitations(String answer, List<RagEvalContext> contexts) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        Set<Integer> citedRanks = new LinkedHashSet<>();
        while (matcher.find()) {
            int rank = Integer.parseInt(matcher.group(1));
            if (rank >= 1 && rank <= contexts.size()) {
                citedRanks.add(rank);
            }
        }

        List<RagEvalCitation> citations = new ArrayList<>();
        for (Integer rank : citedRanks) {
            RagEvalContext context = contexts.get(rank - 1);
            citations.add(new RagEvalCitation(
                    rank,
                    context.sourceId(),
                    context.ticker(),
                    context.filingType(),
                    context.filingDate(),
                    context.section()
            ));
        }
        return citations;
    }

    /**
     * 安全读取 Document 自带分数。
     */
    private Double documentScore(Document doc) {
        try {
            return doc.getScore();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从元数据中读取 Double 值。
     */
    private Double metadataDouble(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 按候选 key 顺序读取第一个非空元数据字符串。
     */
    private String metadataValue(Map<String, Object> metadata, String... keys) {
        if (metadata == null) {
            return "";
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    /**
     * 向上下文头部追加非空元数据字段。
     */
    private void appendPart(StringBuilder builder, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(name).append("=").append(value).append("; ");
    }

    /**
     * 截断上下文正文，控制评测响应体大小。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n...[truncated]";
    }

    /**
     * 空字符串兜底。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
