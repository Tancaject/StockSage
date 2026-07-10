package com.stocksage.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.model.dto.KnowledgeIngestionResult;
import com.stocksage.service.KnowledgeIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时补充 RAG 市场背景的采集器。
 *
 * <p>它只摄取公开搜索摘要，适合补充短期市场背景；因此所有写入都带过期时间，
 * 避免旧新闻长期污染知识库。默认关闭，由配置显式启用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledRagCollector {

    private final DataServiceClient dataServiceClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeIngestionService knowledgeIngestionService;

    @Value("${stocksage.rag.scheduled-ingest.enabled:false}")
    private boolean enabled;

    @Value("${stocksage.rag.scheduled-ingest.run-on-startup:true}")
    private boolean runOnStartup;

    @Value("${stocksage.rag.scheduled-ingest.keywords:US stock market outlook,NVDA AAPL TSLA earnings,Fed monetary policy}")
    private String keywordsCsv;

    @Value("${stocksage.rag.scheduled-ingest.max-results:5}")
    private int maxResults;

    @Value("${stocksage.rag.scheduled-ingest.ttl-days:7}")
    private long ttlDays;

    @Value("${stocksage.rag.scheduled-ingest.min-content-length:200}")
    private int minContentLength;

    /**
     * 按配置的 cron 周期执行市场背景采集。
     */
    @Scheduled(
            cron = "${stocksage.rag.scheduled-ingest.cron:0 30 6 * * *}",
            zone = "${stocksage.rag.scheduler-zone:Asia/Shanghai}"
    )
    public void collectOnSchedule() {
        if (!enabled) {
            return;
        }
        collectNow();
    }

    /**
     * 应用启动后可选补跑一次，防止本地环境长期停机后缺少近期背景材料。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void collectOnStartup() {
        if (!enabled || !runOnStartup) {
            return;
        }
        log.info("Scheduled RAG collection is enabled; running startup catch-up once");
        collectNow();
    }

    /**
     * 立即执行所有配置关键词的采集，并返回每个来源的入库摘要。
     */
    public Map<String, Object> collectNow() {
        List<String> keywords = parseKeywords();
        List<KnowledgeIngestionResult> results = new ArrayList<>();
        for (String keyword : keywords) {
            results.addAll(collectKeyword(keyword));
        }

        int ingestedChunks = results.stream().mapToInt(KnowledgeIngestionResult::chunksIngested).sum();
        int duplicateChunks = results.stream().mapToInt(KnowledgeIngestionResult::chunksSkippedAsDuplicates).sum();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("keywords", keywords);
        summary.put("sourcesProcessed", results.size());
        summary.put("chunksIngested", ingestedChunks);
        summary.put("chunksSkippedAsDuplicates", duplicateChunks);
        summary.put("results", results);
        log.info("Scheduled RAG collection finished: {}", summary);
        return summary;
    }

    /**
     * 对单个关键词执行搜索、摘要过滤和短期知识入库。
     */
    private List<KnowledgeIngestionResult> collectKeyword(String keyword) {
        List<KnowledgeIngestionResult> results = new ArrayList<>();
        try {
            String response = dataServiceClient.webSearch(keyword, maxResults);
            JsonNode root = objectMapper.readTree(response);
            if (root.has("error") && !root.path("error").asText("").isBlank()) {
                log.warn("Scheduled RAG search failed for keyword={}: {}", keyword, root.path("error").asText());
                return results;
            }

            JsonNode items = root.path("results");
            if (!items.isArray()) {
                return results;
            }

            for (JsonNode item : items) {
                String title = item.path("title").asText("").trim();
                String link = item.path("link").asText("").trim();
                String snippet = item.path("snippet").asText("").trim();
                String content = buildContent(keyword, title, link, snippet);
                if (content.length() < minContentLength) {
                    continue;
                }

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", link.isBlank() ? keyword : link);
                metadata.put("doc_type", "scheduled_market_update");
                metadata.put("title", title.isBlank() ? keyword : title);
                metadata.put("knowledge_category", "market_update");
                metadata.put("query", keyword);
                metadata.put("link", link);
                metadata.put("origin", "scheduled_web_search");

                // sourceKey 绑定关键词、链接和标题；同一搜索结果重复出现时会被摄取层去重。
                String sourceKey = "scheduled:" + KnowledgeIngestionService.sha256(keyword + "|" + link + "|" + title);
                results.add(knowledgeIngestionService.ingestText(
                        sourceKey,
                        content,
                        metadata,
                        "scheduled",
                        Duration.ofDays(ttlDays),
                        true
                ));
            }
        } catch (Exception e) {
            log.warn("Scheduled RAG collection failed for keyword={}", keyword, e);
        }
        return results;
    }

    /**
     * 将搜索结果整理成可被 RAG 检索的短文本来源。
     */
    private String buildContent(String keyword, String title, String link, String snippet) {
        return """
                Title: %s
                Query: %s
                Source: %s

                Summary:
                %s
                """.formatted(
                title == null ? "" : title.trim(),
                keyword == null ? "" : keyword.trim(),
                link == null ? "" : link.trim(),
                snippet == null ? "" : snippet.trim()
        ).trim();
    }

    /**
     * 解析逗号分隔关键词配置，忽略空白项。
     */
    private List<String> parseKeywords() {
        List<String> keywords = new ArrayList<>();
        for (String keyword : keywordsCsv.split(",")) {
            String normalized = keyword.trim();
            if (!normalized.isBlank()) {
                keywords.add(normalized);
            }
        }
        return keywords;
    }
}
