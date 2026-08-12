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

    /** 手动文本切片的目标字符数，优先在段落边界切分。 */
    private static final int TARGET_CHUNK_SIZE = 1800;

    /** 统一完成来源版本控制、元数据落库和向量写入。 */
    private final KnowledgeIngestionService knowledgeIngestionService;

    /**
     * 将手动知识来源保存为持久化 RAG 内容。
     *
     * <p>调用方传入标题、正文、来源和分类后，本方法会按段落切分、补充元数据，
     * 再交给 KnowledgeIngestionService 统一写入 MySQL 元数据和向量库。</p>
     *
     * @param title 文档标题
     * @param content 必填正文
     * @param source 来源名称或 URL
     * @param category 业务分类
     * @return 本次实际写入的切片数；内容版本未变时可为 0
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
        // 调用统一摄取服务，让手动知识与 SEC/PDF 共用版本去重和向量索引协议。
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
     *
     * @param content 已校验的非空正文
     * @return 至少包含一个切片的列表
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
     *
     * @param value 原始值
     * @param fallback 空值默认值
     * @return 归一化文本
     */
    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
