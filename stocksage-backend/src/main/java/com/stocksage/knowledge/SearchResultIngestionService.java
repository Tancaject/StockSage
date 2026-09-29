package com.stocksage.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

    @Value("${stocksage.chat.search-ingest.enabled:true}")
    private boolean searchIngestEnabled;

    @Value("${stocksage.chat.search-ingest.ttl-days:7}")
    private long searchIngestTtlDays;

    /**
     * 异步把搜索结果摄取为带 TTL 的临时 RAG 知识。
     *
     * <p>这是后端拥有的异步摄取流程，刻意不是模型可调用的“写知识库”工具。</p>
     */
    public void submit(String toolName, String rawResult, String userQuery) {
        if (!searchIngestEnabled || rawResult == null || rawResult.isBlank()) {
            return;
        }
        try {
            backgroundTaskExecutor.execute(() -> {
            try {
                JsonNode root = objectMapper.readTree(rawResult);
                JsonNode items = root.path("results");

                if (!items.isArray()) {
                    return;
                }
                for (JsonNode item : items) {
                    String title = item.path("title").asText("").trim();
                    String link = item.path("link").asText("").trim();
                    String snippet = item.path("snippet").asText("").trim();

                    String content = """
                            Title: %s
                            Query: %s
                            Source: %s

                            Summary:
                            %s
                            """.formatted(title, userQuery, link, snippet).trim();
                    if (content.length() < 100) {
                        continue;
                    }

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("source", link.isBlank() ? userQuery : link);
                    metadata.put("doc_type", "chat_search_result");
                    metadata.put("title", title.isBlank() ? userQuery : title);
                    metadata.put("knowledge_category", "market_update");
                    metadata.put("query", userQuery);
                    metadata.put("tool", toolName);
                    metadata.put("origin", "chat_driven");

                    String sourceKey = "chat:" + KnowledgeIngestionService.sha256(userQuery + "|" + link + "|" + title);
                    knowledgeIngestionService.ingestText(
                            sourceKey, content, metadata, "chat_driven",
                            Duration.ofDays(searchIngestTtlDays), true);
                }
            } catch (Exception e) {
                log.warn("Async ingestion of {} search results failed: {}", toolName, e.getMessage());
            }
            });
        } catch (RejectedExecutionException rejected) {
            log.warn("Search ingestion not submitted, tool={}, reason=CAPACITY_REJECTED", toolName);
        }
    }

}
