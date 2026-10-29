package com.stocksage.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.runtime.AgentStep;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/** 聊天搜索结果的知识摄取入口，拥有内容转换、来源身份和异步提交。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchResultIngestionService {
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor backgroundTaskExecutor;
    private final TraceService traceService;

    @Value("${stocksage.chat.search-ingest.enabled:true}")
    private boolean searchIngestEnabled;

    @Value("${stocksage.chat.search-ingest.ttl-days:7}")
    private long searchIngestTtlDays;

    /**
     * 异步把搜索结果摄取为带 TTL 的临时 RAG 知识。
     *
     * <p>这是后端拥有的异步摄取流程，刻意不是模型可调用的“写知识库”工具。提交时同步记一条 Trace 步骤，
     * 列出将要入库的条目及其来源身份；异步执行结果只写日志，不在回答结束后再改动 Trace。</p>
     */
    public void submit(String toolName, String rawResult, String userQuery, String traceId, String requestedTarget) {
        long started = System.nanoTime();
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("kind", "search-ingest");
        observation.put("tool", toolName);
        observation.put("query", userQuery);
        observation.put("requestedTarget", requestedTarget);
        List<Map<String, Object>> results = new ArrayList<>();
        observation.put("items", results);
        if (!searchIngestEnabled || rawResult == null || rawResult.isBlank()) {
            observation.put("status", "SKIPPED");
            observation.put("reason", !searchIngestEnabled ? "DISABLED" : "EMPTY_RESULT");
            record(traceId, observation, started);
            return;
        }
        List<Runnable> ingestions = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(rawResult);
            JsonNode items = root.path("results");
            for (String field : List.of("provider", "fetchedAt", "requestedTimelimit", "effectiveTimelimit", "requestedDays")) {
                if (root.hasNonNull(field)) observation.put(field, objectMapper.convertValue(root.get(field), Object.class));
            }
            if (!items.isArray()) {
                observation.put("status", "SKIPPED");
                observation.put("reason", "NO_RESULT_ARRAY");
                record(traceId, observation, started);
                return;
            }
            for (JsonNode item : items) {
                String title = item.path("title").asText("").trim();
                String link = item.path("link").asText("").trim();
                String snippet = item.path("snippet").asText("").trim();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("title", title);
                result.put("source", link);
                // 来源身份只能取结果自身字段，不能把请求标的复制成证据标的。
                for (String field : List.of("ticker", "symbol", "target", "fiscalYear", "reportPeriodEnd",
                        "publishedAt", "publishedDate", "publishedTimeKind", "publishedTimeBasis")) {
                    if (item.hasNonNull(field)) result.put(field, objectMapper.convertValue(item.get(field), Object.class));
                }
                results.add(result);

                String content = """
                        Title: %s
                        Query: %s
                        Source: %s

                        Summary:
                        %s
                        """.formatted(title, userQuery, link, snippet).trim();
                if (content.length() < 100) {
                    result.put("status", "SKIPPED");
                    result.put("reason", "CONTENT_TOO_SHORT");
                    continue;
                }
                result.put("status", "SUBMITTED");

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", link.isBlank() ? userQuery : link);
                metadata.put("doc_type", "chat_search_result");
                metadata.put("title", title.isBlank() ? userQuery : title);
                metadata.put("knowledge_category", "market_update");
                metadata.put("query", userQuery);
                metadata.put("tool", toolName);
                metadata.put("origin", "chat_driven");

                String sourceKey = "chat:" + KnowledgeIngestionService.sha256(userQuery + "|" + link + "|" + title);
                ingestions.add(() -> knowledgeIngestionService.ingestText(
                        sourceKey, content, metadata, "chat_driven",
                        Duration.ofDays(searchIngestTtlDays), true));
            }
        } catch (Exception e) {
            observation.put("status", "FAILED");
            observation.put("reason", e.getClass().getSimpleName());
            record(traceId, observation, started);
            log.warn("Search results of {} could not be parsed for ingestion: {}", toolName, e.getMessage());
            return;
        }
        if (ingestions.isEmpty()) {
            observation.put("status", "SKIPPED");
            observation.put("reason", "NO_INGESTIBLE_ITEMS");
            record(traceId, observation, started);
            return;
        }
        try {
            backgroundTaskExecutor.execute(() -> {
                for (Runnable ingestion : ingestions) {
                    try {
                        ingestion.run();
                    } catch (Exception e) {
                        log.warn("Async ingestion of {} search result failed, traceId={}: {}", toolName, traceId, e.getMessage());
                    }
                }
            });
            observation.put("status", "SUBMITTED");
        } catch (RejectedExecutionException rejected) {
            observation.put("status", "FAILED");
            observation.put("reason", "CAPACITY_REJECTED");
            results.stream().filter(result -> "SUBMITTED".equals(result.get("status")))
                    .forEach(result -> result.put("status", "NOT_SUBMITTED"));
            log.warn("Search ingestion not submitted, tool={}, reason=CAPACITY_REJECTED", toolName);
        }
        record(traceId, observation, started);
    }

    private void record(String traceId, Map<String, Object> observation, long started) {
        if (traceId == null || traceId.isBlank()) return;
        try {
            traceService.addStep(traceId, AgentStep.builder().action("Search Ingestion")
                    .observation("搜索结果入库：" + observation.get("status"))
                    .attributes(observation).durationMs((System.nanoTime() - started) / 1_000_000).build());
        } catch (Exception error) {
            // 观测写入失败不能取消或重复执行已经提交的知识入库。
            log.warn("Search ingestion trace not recorded, traceId={}, reason={}", traceId, error.getMessage());
        }
    }

}
