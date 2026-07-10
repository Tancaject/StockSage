package com.stocksage.service;

import com.stocksage.model.dto.KnowledgeIngestionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手动知识写入入口。
 *
 * <p>本服务负责切分运维或用户提供的文本，并委托 {@link KnowledgeIngestionService} 建索引；
 * 可被模型调用的工具不应直接写入知识库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final int TARGET_CHUNK_SIZE = 1800;

    private final KnowledgeIngestionService knowledgeIngestionService;

    /**
     * 将手动知识来源保存为持久化 RAG 内容。
     *
     * <p>调用方传入标题、正文、来源和分类后，本方法会按段落切分、补充元数据，
     * 再交给 KnowledgeIngestionService 统一写入 MySQL 元数据和向量库。</p>
     */
    public int saveLearnedKnowledge(String title, String content, String source, String category) {
        String normalizedTitle = normalize(title, "Untitled manual knowledge");
        String normalizedContent = normalize(content, "");
        String normalizedSource = normalize(source, "manual");
        String normalizedCategory = normalize(category, "general").toLowerCase();

        if (normalizedContent.isBlank()) {
            throw new IllegalArgumentException("Knowledge content must not be empty");
        }

        List<String> chunks = splitContent(normalizedContent);
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", normalizedSource);
            metadata.put("doc_type", "manual_" + normalizedCategory);
            metadata.put("title", normalizedTitle);
            metadata.put("knowledge_category", normalizedCategory);
            metadata.put("origin", "manual_knowledge");
            metadata.put("ingested_date", LocalDate.now().toString());
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", chunks.size());
            documents.add(new Document(chunks.get(i), metadata));
        }

        String sourceId = "manual:knowledge:" + KnowledgeIngestionService.sha256(normalizedSource + ":" + normalizedTitle);
        KnowledgeIngestionResult result = knowledgeIngestionService.ingestDocuments(
                sourceId,
                KnowledgeIngestionService.sha256(normalizedContent),
                documents,
                "manual",
                null
        );

        log.info("Saved manual knowledge: title={}, category={}, status={}, chunks={}",
                normalizedTitle,
                normalizedCategory,
                result.status(),
                result.chunksIngested());
        return result.chunksIngested();
    }

    /**
     * 按段落把长文本切成适合向量化的块。
     *
     * <p>优先保留段落边界；当当前块超过目标长度时再开启新块，避免把语义连续内容切得过碎。</p>
     */
    private List<String> splitContent(String content) {
        List<String> paragraphs = List.of(content.split("\\n\\s*\\n+"));
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (current.length() > 0 && current.length() + trimmed.length() + 2 > TARGET_CHUNK_SIZE) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }

            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        if (chunks.isEmpty()) {
            chunks.add(content.trim());
        }
        return chunks;
    }

    /**
     * 标准化可选输入。
     *
     * <p>空值或空白字符串使用 fallback，非空值去掉首尾空格。</p>
     */
    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
