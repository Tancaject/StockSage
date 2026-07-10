package com.stocksage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 公司关系抽取的后台作业协调器。
 *
 * <p>抽取要跑数分钟（串行 LLM），不能阻塞 HTTP 请求线程，因此放到单线程守护执行器里异步跑：
 * <ul>
 *   <li>单线程——串行化多个标的的抽取，避免并发打爆同一 LLM API 配额；</li>
 *   <li>守护线程——随 JVM 退出，杜绝"抽取线程吊住进程退不出"的僵尸问题；</li>
 *   <li>进行中集合去重——同一 ticker 已在跑时不重复触发，并供读取接口回报 {@code extracting} 状态。</li>
 * </ul>
 * 若该标的尚无最新 10-K 切片，则先入库其 10-K 再抽取，让前端"生成"按钮对任意美股自助可用。</p>
 */
@Slf4j
@Component
public class RelationExtractionJobs {

    private final RelationExtractionService extractionService;
    private final EdgarIngestionService edgarIngestionService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "relation-extraction");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Object>> progress = new ConcurrentHashMap<>();

    public RelationExtractionJobs(RelationExtractionService extractionService,
                                  EdgarIngestionService edgarIngestionService) {
        this.extractionService = extractionService;
        this.edgarIngestionService = edgarIngestionService;
    }

    /**
     * 提交某 ticker 的后台抽取作业；已在跑则返回 false（不重复触发）。
     */
    public boolean submit(String ticker) {
        String norm = normalize(ticker);
        if (norm.isEmpty() || !running.add(norm)) {
            return false;
        }
        executor.submit(() -> {
            try {
                runFor(norm);
            } catch (Exception e) {
                log.warn("Async relation extraction failed for {}: {}", norm, e.getMessage());
            } finally {
                running.remove(norm);
            }
        });
        return true;
    }

    /** 该 ticker 是否有抽取作业进行中。 */
    public boolean isRunning(String ticker) {
        return running.contains(normalize(ticker));
    }

    /** 返回该 ticker 当前抽取进度快照；未在抽取中则返回空 map。 */
    public Map<String, Object> getProgress(String ticker) {
        Map<String, Object> snap = progress.get(normalize(ticker));
        return snap == null ? Collections.emptyMap() : Map.copyOf(snap);
    }

    /**
     * 先抽取；若该标的尚无 10-K 业务章节切片（extractForTicker 返回 error），
     * 则入库最新 10-K 后再抽一次，让按钮对未入库的美股也能自助生成。
     */
    private void runFor(String ticker) {
        progress.put(ticker, new ConcurrentHashMap<>(Map.of("phase", "extracting", "processed", 0, "total", 0, "accepted", 0)));
        try {
            Map<String, Object> result = extractionService.extractForTicker(ticker, p -> progress.put(ticker, new ConcurrentHashMap<>(p)));
            if (Boolean.TRUE.equals(result.get("error"))) {
                log.info("No ingested 10-K for {}; ingesting latest 10-K then retrying extraction.", ticker);
                progress.put(ticker, new ConcurrentHashMap<>(Map.of("phase", "ingesting", "processed", 0, "total", 0, "accepted", 0)));
                try {
                    edgarIngestionService.ingestFilings(ticker, "10-K", 1);
                    progress.put(ticker, new ConcurrentHashMap<>(Map.of("phase", "extracting", "processed", 0, "total", 0, "accepted", 0)));
                    extractionService.extractForTicker(ticker, p -> progress.put(ticker, new ConcurrentHashMap<>(p)));
                } catch (Exception e) {
                    log.warn("10-K ingest+extract fallback failed for {}: {}", ticker, e.getMessage());
                }
            }
        } finally {
            progress.remove(ticker);
        }
    }

    private String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase();
    }
}
