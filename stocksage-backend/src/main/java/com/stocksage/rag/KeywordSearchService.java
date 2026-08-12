package com.stocksage.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词检索服务 —— 基于 MySQL FULLTEXT 索引的 BM25 近似检索。
 *
 * 与向量检索互补：
 * - 向量检索擅长语义相似（"苹果的负债" → 匹配同义表述）
 * - 关键词检索擅长精确术语（"商誉减值" → 精确匹配该术语）
 *
 * 两者通过 RRF（倒数排名融合）在 RagService 中融合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    /** 从 Milvus 风格过滤表达式中提取 ticker 等值条件。 */
    private static final Pattern TICKER_FILTER_PATTERN = Pattern.compile("ticker\\s*==\\s*'([^']+)'");

    /** 执行 MySQL FULLTEXT 查询并映射结果。 */
    private final JdbcTemplate jdbcTemplate;

    /** 混合检索开关；关闭时关键词路径返回空列表。 */
    @Value("${stocksage.rag.hybrid-search.enabled:true}")
    private boolean enabled;

    /**
     * 使用 MySQL FULLTEXT 搜索 vector_documents 表。
     * MATCH AGAINST 在 NATURAL LANGUAGE MODE 下近似 BM25 排序。
     *
     * @param query 检索文本
     * @param topK 最大候选数
     * @return 按全文相关度排序的文档
     */
    public List<Document> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 使用与向量检索相同的 ticker 过滤条件执行全文检索。
     *
     * <p>当前只支持 ticker 等值或 OR 组合；遇到无法等价转换的过滤表达式时返回空列表，
     * 防止关键词路径绕过向量路径的元数据边界。</p>
     *
     * @param query 检索文本
     * @param topK 最大候选数
     * @param filterExpression RagService 生成的可选 ticker 过滤表达式
     * @return 全文候选；功能关闭、表达式不支持或查询失败时为空
     */
    public List<Document> search(String query, int topK, String filterExpression) {
        if (!enabled || query == null || query.isBlank()) {
            return List.of();
        }

        try {
            List<String> tickerFilters = extractTickerFilters(filterExpression);
            if (filterExpression != null && !filterExpression.isBlank() && tickerFilters.isEmpty()) {
                log.debug("Keyword search skipped because filter expression is unsupported: {}", filterExpression);
                return List.of();
            }

            StringBuilder sql = new StringBuilder("""
                    SELECT redis_key, content_full, content_preview, metadata,
                           MATCH(content_full) AGAINST(? IN NATURAL LANGUAGE MODE) AS relevance
                    FROM vector_documents
                    WHERE MATCH(content_full) AGAINST(? IN NATURAL LANGUAGE MODE)
                    """);
            List<Object> params = new ArrayList<>();
            params.add(query);
            params.add(query);

            if (!tickerFilters.isEmpty()) {
                sql.append(" AND JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.ticker')) IN (")
                        .append("?,".repeat(tickerFilters.size()));
                sql.setLength(sql.length() - 1);
                sql.append(")\n");
                params.addAll(tickerFilters);
            }

            sql.append("""
                    ORDER BY relevance DESC
                    LIMIT ?
                    """);
            params.add(topK);

            // JdbcTemplate 绑定 query、ticker 和 LIMIT 参数，避免把用户文本拼进 SQL。
            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
                String vectorId = rs.getString("redis_key");
                String contentFull = rs.getString("content_full");
                String contentPreview = rs.getString("content_preview");
                String content = contentFull != null ? contentFull : contentPreview;
                String metadataJson = rs.getString("metadata");

                Map<String, Object> metadata = Map.of(
                        "doc_id", vectorId != null ? vectorId : "",
                        "source", "keyword_search",
                        "keyword_relevance", rs.getDouble("relevance")
                );

                // 尝试合并已存储的元数据
                metadata = mergeMetadata(metadata, metadataJson);

                return new Document(content != null ? content : "", metadata);
            }, params.toArray());
        } catch (Exception e) {
            log.warn("Keyword search failed (FULLTEXT index may not exist yet): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 提取过滤表达式中的 ticker，并按出现顺序去重。
     *
     * @param filterExpression Milvus 风格过滤表达式
     * @return 规范化为大写的 ticker 列表
     */
    private List<String> extractTickerFilters(String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return List.of();
        }
        Matcher matcher = TICKER_FILTER_PATTERN.matcher(filterExpression);
        Set<String> tickers = new LinkedHashSet<>();
        while (matcher.find()) {
            String ticker = matcher.group(1).trim().toUpperCase();
            if (!ticker.isBlank()) {
                tickers.add(ticker);
            }
        }
        return List.copyOf(tickers);
    }

    /**
     * 合并数据库元数据与关键词检索诊断字段；诊断字段优先。
     *
     * @param base 本次检索生成的 doc_id、来源和相关度
     * @param metadataJson 数据库保存的原始元数据 JSON
     * @return 合并结果；JSON 损坏时仅返回基础字段
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeMetadata(Map<String, Object> base, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return base;
        try {
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> stored = objectMapper.readValue(metadataJson, Map.class);
            var merged = new java.util.LinkedHashMap<>(stored);
            merged.putAll(base);
            return merged;
        } catch (Exception e) {
            return base;
        }
    }
}
