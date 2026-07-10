package com.stocksage.client;

import com.stocksage.cache.ToolResultCache;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Python 数据服务的 HTTP 客户端。
 *
 * 为什么金融数据要单独用 Python 服务？
 * - baostock 和 AKShare 都是 Python 库，没有 Java 版本
 * - 在 Java 中嵌入 Python（如 Jython/GraalPy）增加复杂度且不稳定
 * - 独立服务解耦：数据源更换（如从 baostock 换成 tushare）只改 Python 端，Java 端无感
 *
 * 本客户端使用 Spring WebFlux 的 WebClient（非阻塞 HTTP 客户端）。
 * 所有方法都通过 .block() 同步等待结果，因为工具调用本身是同步的。
 * 超时时间从配置文件读取（默认 10 秒），超时会抛异常由上层处理。
 */
@Slf4j
@Component
public class DataServiceClient {

    private static final int MAX_RETRIES = 2;

    private static final Duration TTL_FINANCIAL  = Duration.ofHours(4);
    private static final Duration TTL_FINANCIAL_REPORT = Duration.ofHours(6);
    private static final Duration TTL_KLINE      = Duration.ofMinutes(30);
    private static final Duration TTL_TECHNICAL   = Duration.ofMinutes(30);
    private static final Duration TTL_COMPARE     = Duration.ofMinutes(30);
    private static final Duration TTL_MARKET      = Duration.ofMinutes(10);
    private static final Duration TTL_SEARCH      = Duration.ofMinutes(15);

    private static final String SEARCH_FALLBACK = "{\"error\":true,\"circuit\":\"open\","
            + "\"message\":\"Search service temporarily unavailable due to rate limiting. "
            + "Please answer based on knowledge base and available tool data.\"}";

    private final WebClient webClient;
    private final Duration timeout;
    private final Duration financialReportTimeout;
    private final Duration edgarTimeout;
    private final CircuitBreaker searchCircuitBreaker;
    private final ToolResultCache cache;

    /**
     * 初始化 Python 数据服务客户端。
     *
     * <p>构造时同时配置响应体大小、不同业务超时和搜索熔断器；搜索服务比行情接口更容易限流，
     * 因此独立用 circuit breaker 控制降级。</p>
     */
    public DataServiceClient(
            @Value("${stocksage.data-service.base-url}") String baseUrl,
            @Value("${stocksage.data-service.timeout-ms}") long timeoutMs,
            @Value("${stocksage.data-service.financial-report-timeout-ms:30000}") long financialReportTimeoutMs,
            @Value("${stocksage.data-service.edgar-timeout-ms:120000}") long edgarTimeoutMs,
            @Value("${stocksage.data-service.max-in-memory-mb:64}") int maxInMemoryMb,
            ToolResultCache cache) {
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(maxInMemoryMb * 1024 * 1024))
                .build();

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(exchangeStrategies)
                .build();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.financialReportTimeout = Duration.ofMillis(financialReportTimeoutMs);
        this.edgarTimeout = Duration.ofMillis(edgarTimeoutMs);

        this.searchCircuitBreaker = CircuitBreaker.of("search", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .slidingWindowSize(10)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                // 必须显式设置：默认值 100 远大于窗口大小 10，会导致失败率永远无法被评估、熔断器永不打开。
                .minimumNumberOfCalls(5)
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());

        searchCircuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Search circuit breaker: {}", event));
        this.cache = cache;
    }

    /** 获取 A 股/港股 K 线数据，短 TTL 缓存。 */
    public String getKLine(String code, String period, int days) {
        return cached("kline", new String[]{code, period, String.valueOf(days)}, TTL_KLINE,
                () -> getOnce("/api/stock/kline?code={code}&period={period}&days={days}", code, period, days));
    }

    /** 解析股票名称或代码到标准市场身份。 */
    public String resolveStock(String query) {
        return cached("stock-resolve", new String[]{query}, TTL_SEARCH,
                () -> get("/api/stock/resolve?query={query}", query));
    }

    /**
     * 搜索股票候选列表。
     *
     * <p>使用 {@code getOnce}（不重试）：股票搜索一旦超时，通常是上游目录接口卡死，
     * 重试只会成倍放大等待（曾出现 3×10s=30s 拖垮 SSE 流），fail-fast 更合适。</p>
     */
    public String searchStocks(String query, int maxResults) {
        return cached("stock-search-v2", new String[]{query, String.valueOf(maxResults)}, TTL_SEARCH,
                () -> getOnce("/api/stock/search?q={q}&max_results={max}", query, maxResults));
    }

    /** 获取核心财务或估值指标。 */
    public String getFinancialMetrics(String code) {
        return cached("financial", new String[]{code}, TTL_FINANCIAL,
                () -> getOnce("/api/stock/financial?code={code}", code));
    }

    /** 获取结构化财报数据，使用更长超时。 */
    public String getFinancialReports(String code, String period, int years) {
        return cached("financial-report", new String[]{code, period, String.valueOf(years)}, TTL_FINANCIAL_REPORT,
                () -> getWithRetries("/api/stock/financial-report?code={code}&period={period}&years={years}",
                        financialReportTimeout, 0, code, period, years));
    }

    /** 获取技术指标。 */
    public String getTechnicalIndicators(String code, String indicators) {
        return cached("technical", new String[]{code, indicators}, TTL_TECHNICAL,
                () -> getOnce("/api/stock/technical?code={code}&indicators={indicators}", code, indicators));
    }

    /** 获取指定股票近期新闻。 */
    public String getStockNews(String code, int days) {
        return cached("news", new String[]{code, String.valueOf(days)}, TTL_SEARCH,
                () -> get("/api/stock/news?code={code}&days={days}", code, days));
    }

    /** 获取行业/板块表现。 */
    public String getSectorPerformance(String sector) {
        return cached("sector", new String[]{sector}, TTL_MARKET,
                () -> get("/api/stock/sector?sector={sector}", sector));
    }

    /** 多股票横向对比。 */
    public String compareStocks(String codes, String dimensions) {
        return cached("compare", new String[]{codes, dimensions}, TTL_COMPARE,
                () -> get("/api/stock/compare?codes={codes}&dimensions={dimensions}", codes, dimensions));
    }

    /** 获取大盘概览。 */
    public String getMarketOverview() {
        return cached("market", new String[]{"overview"}, TTL_MARKET,
                () -> get("/api/stock/market-overview"));
    }

    // ===== 全球股票（美股 / 港股）=====

    /** 获取美股/港股全局 K 线数据。 */
    public String getGlobalKLine(String symbol, String period, int days) {
        return cached("global-kline", new String[]{symbol, period, String.valueOf(days)}, TTL_KLINE,
                () -> getOnce("/api/stock/global/kline?symbol={symbol}&period={period}&days={days}", symbol, period, days));
    }

    /** 获取美股/港股标的基础信息。 */
    public String getGlobalStockInfo(String symbol) {
        return cached("global-info", new String[]{symbol}, TTL_FINANCIAL,
                () -> getOnce("/api/stock/global/info?symbol={symbol}", symbol));
    }

    /** 获取美股/港股技术指标。 */
    public String getGlobalTechnicalIndicators(String symbol, String indicators) {
        return cached("global-technical", new String[]{symbol, indicators}, TTL_TECHNICAL,
                () -> getOnce("/api/stock/global/technical?symbol={symbol}&indicators={indicators}", symbol, indicators));
    }

    // ===== 网页搜索 =====

    /** 执行网页搜索，并由熔断器保护上游限流（默认 basic 检索深度）。 */
    public String webSearch(String query, int maxResults) {
        return webSearch(query, maxResults, null, false);
    }

    /** 执行网页搜索，可选时间范围过滤（默认 basic 检索深度）。 */
    public String webSearch(String query, int maxResults, String timeLimit) {
        return webSearch(query, maxResults, timeLimit, false);
    }

    /**
     * 执行网页搜索，可选时间范围过滤与检索深度。
     *
     * @param timeLimit 时间范围（d/w/m/y），null 或非法值表示不限制时间。
     * @param advanced  true 时请求 Tavily advanced 深度检索（召回更全、相关性更好，
     *                  但每次消耗 2 个 credit）；false 为 basic（1 credit）。
     */
    public String webSearch(String query, int maxResults, String timeLimit, boolean advanced) {
        String tl = normalizeTimeLimit(timeLimit);
        String depth = advanced ? "advanced" : "basic";
        return cached("web-search",
                new String[]{query, String.valueOf(maxResults), tl == null ? "" : tl, depth}, TTL_SEARCH,
                () -> tl == null
                        ? searchWithCircuitBreaker("/api/search/web?q={q}&max_results={max}&depth={d}",
                                query, maxResults, depth)
                        : searchWithCircuitBreaker(
                                "/api/search/web?q={q}&max_results={max}&timelimit={tl}&depth={d}",
                                query, maxResults, tl, depth));
    }

    /** 执行财经新闻搜索，并由熔断器保护上游限流（默认 basic 检索深度）。 */
    public String newsSearch(String query, int maxResults) {
        return newsSearch(query, maxResults, null, false);
    }

    /** 执行财经新闻搜索，可选时间范围过滤（默认 basic 检索深度）。 */
    public String newsSearch(String query, int maxResults, String timeLimit) {
        return newsSearch(query, maxResults, timeLimit, false);
    }

    /**
     * 执行财经新闻搜索，可选时间范围过滤与检索深度。
     *
     * @param timeLimit 时间范围（d/w/m），null 或非法值表示不限制时间。
     * @param advanced  true 时请求 Tavily advanced 深度检索（2 credit/次），false 为 basic（1 credit/次）。
     */
    public String newsSearch(String query, int maxResults, String timeLimit, boolean advanced) {
        String tl = normalizeTimeLimit(timeLimit);
        String depth = advanced ? "advanced" : "basic";
        return cached("news-search",
                new String[]{query, String.valueOf(maxResults), tl == null ? "" : tl, depth}, TTL_SEARCH,
                () -> tl == null
                        ? searchWithCircuitBreaker("/api/search/news?q={q}&max_results={max}&depth={d}",
                                query, maxResults, depth)
                        : searchWithCircuitBreaker(
                                "/api/search/news?q={q}&max_results={max}&timelimit={tl}&depth={d}",
                                query, maxResults, tl, depth));
    }

    /**
     * 收敛时间范围参数，只接受 DuckDuckGo 认可的取值（d/w/m/y），其余按不过滤处理。
     */
    private String normalizeTimeLimit(String timeLimit) {
        if (timeLimit == null) {
            return null;
        }
        String normalized = timeLimit.trim().toLowerCase();
        return switch (normalized) {
            case "d", "w", "m", "y" -> normalized;
            default -> null;
        };
    }

    // ===== SEC EDGAR 文件 =====

    private static final Duration TTL_EDGAR_INDEX = Duration.ofHours(12);
    private static final Duration TTL_EDGAR_XBRL  = Duration.ofHours(12);

    /** 获取 SEC EDGAR 公司公告索引。 */
    public String getEdgarFilings(String ticker, String filingType, int count) {
        return cached("edgar-filings",
                new String[]{ticker, filingType, String.valueOf(count)}, TTL_EDGAR_INDEX,
                () -> getWithTimeout("/api/edgar/filings?ticker={t}&type={type}&count={count}", edgarTimeout,
                        ticker, filingType, count));
    }

    /**
     * 下载并解析单份公告，拆成 Item 级别的章节。
     * 不走缓存，摄取流程中每份公告只调用一次。
     */
    public String getEdgarFilingContent(String documentUrl, String filingType) {
        return getWithTimeout("/api/edgar/filing-content?url={url}&type={type}", edgarTimeout,
                documentUrl, filingType);
    }

    /** 获取 SEC XBRL 结构化财务数据。 */
    public String getEdgarXbrl(String ticker) {
        return cached("edgar-xbrl", new String[]{ticker}, TTL_EDGAR_XBRL,
                () -> get("/api/edgar/xbrl?ticker={t}", ticker));
    }

    // ===== 文档解析 =====

    /**
     * 将 PDF 上传到 Python 服务，执行结构感知解析。
     * 返回包含结构化切片的 JSON，例如章节标题、页码和重叠信息。
     */
    public String parsePdf(Path filePath, int chunkSize, int overlap) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new FileSystemResource(filePath.toFile()));
        builder.part("chunk_size", String.valueOf(chunkSize));
        builder.part("overlap", String.valueOf(overlap));

        try {
            return webClient.post()
                    .uri("/api/document/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMinutes(5))
                    .block();
        } catch (Exception e) {
            log.error("PDF parsing failed for {}: {}", filePath.getFileName(), e.getMessage(), e);
            return "{\"error\":true,\"message\":\"PDF parsing failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 搜索供应商比本地行情端点更容易触发限流。
     * 熔断器会在上游持续失败时返回可读兜底结果，避免模型或工具调用反复等待。
     *
     * <p>关键点：底层 {@link #get} 把所有失败都转成 JSON 错误字符串而<b>不抛异常</b>，
     * 而熔断器只能通过异常统计失败。因此这里必须显式识别"失败响应体"并抛出
     * {@link UpstreamSearchException}，否则熔断器会把每一次失败都记成成功、永远无法打开。</p>
     */
    private String searchWithCircuitBreaker(String uri, Object... vars) {
        try {
            return searchCircuitBreaker.executeSupplier(() -> {
                String body = get(uri, vars);
                if (DataServicePayloads.isFailure(body)) {
                    throw new UpstreamSearchException(body);
                }
                return body;
            });
        } catch (CallNotPermittedException e) {
            log.warn("Search circuit breaker is OPEN, returning fallback for: {}", uri);
            return SEARCH_FALLBACK;
        } catch (UpstreamSearchException e) {
            // 失败已被熔断器计入统计；仍把真实的错误响应体返回上层，让模型据此说明数据缺口。
            return e.body();
        }
    }

    /**
     * 统一的缓存读写入口：失败响应（{@link DataServicePayloads#isFailure}）不写缓存，
     * 避免错误固化；错误契约的判定只此一处。
     */
    private String cached(String category, String[] keyParts, Duration ttl, Supplier<String> supplier) {
        return cache.getOrFetch(category, keyParts, ttl, supplier,
                result -> !DataServicePayloads.isFailure(result));
    }

    /** 使用默认超时和默认重试次数发起 GET。 */
    private String get(String uri, Object... vars) {
        return getWithRetries(uri, timeout, MAX_RETRIES, vars);
    }

    /** 使用默认超时且不重试发起 GET。 */
    private String getOnce(String uri, Object... vars) {
        return getWithRetries(uri, timeout, 0, vars);
    }

    /** 使用指定超时和默认重试次数发起 GET。 */
    private String getWithTimeout(String uri, Duration requestTimeout, Object... vars) {
        return getWithRetries(uri, requestTimeout, MAX_RETRIES, vars);
    }

    /**
     * 所有 Python 数据服务调用的统一 GET 包装。
     * 上层方法负责选择超时和重试策略；本方法把最终失败转换成 JSON 错误字符串，
     * 让模型仍可基于失败信息继续推理。
     */
    private String getWithRetries(String uri, Duration requestTimeout, int maxRetries, Object... vars) {
        Exception lastError = null;
        int maxAttempts = Math.max(0, maxRetries) + 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return webClient.get()
                        .uri(uri, vars)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(requestTimeout)
                        .block();
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    log.warn("Data service call failed, retrying ({}/{}): {}", attempt, maxRetries, uri, e);
                } else if (maxAttempts == 1) {
                    log.warn("Data service call failed: {} ({})", uri, rootMessage(e));
                } else {
                    log.error("Data service call failed after {} attempts: {}", maxAttempts, uri, e);
                }
            }
        }

        String message = lastError == null ? "unknown error" : lastError.getMessage();
        return "{\"error\":true,\"message\":\"Data service call failed after "
                + maxAttempts
                + " attempts: "
                + escapeJson(message)
                + "\"}";
    }

    /**
     * 转义异常文本，确保可以安全拼入 JSON 错误字符串。
     */
    private String escapeJson(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * 读取异常链最底层的错误消息。
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? error.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * 标记一次搜索调用在上游失败。
     *
     * <p>{@link #get} 从不抛异常，但熔断器只能通过异常来统计失败。该异常用于把
     * "失败响应体"转换成熔断器可识别的失败信号，同时携带原始响应体，供上层在
     * 失败被计入统计后仍能把真实错误返回给模型。</p>
     */
    private static final class UpstreamSearchException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String body;

        UpstreamSearchException(String body) {
            super("upstream search call returned a failure response");
            this.body = body;
        }

        String body() {
            return body;
        }
    }
}
