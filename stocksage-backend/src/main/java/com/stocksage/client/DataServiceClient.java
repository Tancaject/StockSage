package com.stocksage.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.stocksage.cache.ToolResultCache;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.tool.ToolCallContext;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

    /** 默认 GET 请求在首次失败后最多重试的次数。 */
    private static final int MAX_RETRIES = 2;

    /** 财务指标缓存时间；此类数据更新频率低于行情。 */
    private static final Duration TTL_FINANCIAL  = Duration.ofHours(4);
    /** 结构化财报缓存时间。 */
    private static final Duration TTL_FINANCIAL_REPORT = Duration.ofHours(6);
    /** K 线缓存时间。 */
    private static final Duration TTL_KLINE      = Duration.ofMinutes(30);
    /** 技术指标缓存时间。 */
    private static final Duration TTL_TECHNICAL   = Duration.ofMinutes(30);
    /** 市场概览和板块数据缓存时间。 */
    private static final Duration TTL_MARKET      = Duration.ofMinutes(10);
    /** 股票目录、新闻和网页搜索结果缓存时间。 */
    private static final Duration TTL_SEARCH      = Duration.ofMinutes(15);

    /** 指向 Python data-service 基地址的 HTTP 客户端。 */
    private final WebClient webClient;
    /** 普通行情和搜索请求的默认超时。 */
    private final Duration timeout;
    /** 结构化财报请求的独立超时。 */
    private final Duration financialReportTimeout;
    /** SEC EDGAR 下载和解析请求的独立长超时。 */
    private final Duration edgarTimeout;
    /** 仅保护易受限流影响的网页/新闻搜索端点。 */
    private final CircuitBreaker searchCircuitBreaker;
    /** 为幂等 GET 工具结果提供 Cache-Aside 缓存。 */
    private final ToolResultCache cache;
    private final ObjectMapper objectMapper;

    /**
     * 初始化 Python 数据服务客户端。
     *
     * <p>构造时同时配置响应体大小、不同业务超时和搜索熔断器；搜索服务比行情接口更容易限流，
     * 因此独立用 circuit breaker 控制降级。</p>
     *
     * @param baseUrl Python data-service 基地址
     * @param timeoutMs 普通请求超时毫秒数
     * @param financialReportTimeoutMs 财报请求超时毫秒数
     * @param edgarTimeoutMs EDGAR 请求超时毫秒数
     * @param maxInMemoryMb WebClient 可缓冲的最大响应体大小
     * @param cache 工具结果缓存
     */
    public DataServiceClient(
            @Value("${stocksage.data-service.base-url}") String baseUrl,
            @Value("${stocksage.data-service.timeout-ms}") long timeoutMs,
            @Value("${stocksage.data-service.financial-report-timeout-ms:30000}") long financialReportTimeoutMs,
            @Value("${stocksage.data-service.edgar-timeout-ms:120000}") long edgarTimeoutMs,
            @Value("${stocksage.data-service.max-in-memory-mb:64}") int maxInMemoryMb,
            ToolResultCache cache,
            ObjectMapper objectMapper) {
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
                .ignoreExceptions(ResearchBudgetExceededException.class)
                .build());

        searchCircuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Search circuit breaker: {}", event));
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取 A 股/港股 K 线数据，短 TTL 缓存。
     *
     * @param code 市场代码，如 {@code SH.600519} 或 {@code 0700.HK}
     * @param period K 线周期
     * @param days 返回的自然日窗口
     * @return Python K 线 v1 契约；HTTP 或协议错误以 ERROR 状态返回
     */
    public KLineResponse getKLine(String code, String period, int days) {
        String json = cached("kline:v1", new String[]{code, period, String.valueOf(days)}, TTL_KLINE,
                () -> objectMapper.valueToTree(fetchKLine(code, period, days)).toString());
        try {
            return KLineResponse.fromJson(objectMapper.readTree(json));
        } catch (Exception e) {
            rethrowBudgetFailure(e);
            return KLineResponse.failure(code, period, "INVALID_DATA_SERVICE_RESPONSE",
                    "K-line response does not match schemaVersion 1; check the data-service deployment.", false);
        }
    }

    private KLineResponse fetchKLine(String code, String period, int days) {
        JsonNode response;
        try {
            response = request(webClient.get()
                    .uri("/api/stock/kline?code={code}&period={period}&days={days}", code, period, days), timeout, JsonNode.class);
        } catch (WebClientResponseException e) {
            rethrowBudgetFailure(e);
            int status = e.getStatusCode().value();
            return KLineResponse.failure(code, period, "DATA_SERVICE_HTTP_" + status,
                    "K-line data-service returned HTTP " + status + "; check request parameters or service availability.",
                    status == 429 || status >= 500);
        } catch (org.springframework.core.codec.DecodingException e) {
            rethrowBudgetFailure(e);
            return KLineResponse.failure(code, period, "INVALID_DATA_SERVICE_RESPONSE",
                    "K-line data-service returned invalid JSON; check the data-service deployment.", false);
        } catch (Exception e) {
            rethrowBudgetFailure(e);
            log.warn("K-line data-service request failed: {}", rootMessage(e));
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            Boolean retryable = cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.net.ConnectException ? Boolean.TRUE : null;
            return KLineResponse.failure(code, period, "DATA_SERVICE_UNAVAILABLE",
                    "K-line data-service request failed; check service connectivity and retry.", retryable);
        }
        try {
            return KLineResponse.fromJson(response);
        } catch (RuntimeException e) {
            rethrowBudgetFailure(e);
            return KLineResponse.failure(code, period, "INVALID_DATA_SERVICE_RESPONSE",
                    "K-line response does not match schemaVersion 1; check the data-service deployment.", false);
        }
    }

    /**
     * 解析股票名称或代码到标准市场身份。
     *
     * @param query 用户输入的公司名或代码
     * @return 标准代码、市场和名称等解析结果 JSON
     */
    public String resolveStock(String query) {
        return cached("stock-resolve", new String[]{query}, TTL_SEARCH,
                () -> get("/api/stock/resolve?query={query}", query));
    }

    /**
     * 搜索股票候选列表。
     *
     * <p>使用 {@code getOnce}（不重试）：股票搜索一旦超时，通常是上游目录接口卡死，
     * 重试只会成倍放大等待（曾出现 3×10s=30s 拖垮 SSE 流），fail-fast 更合适。</p>
     *
     * @param query 股票名称或代码片段
     * @param maxResults 最大候选数
     * @return 候选数组 JSON；失败时返回结构化错误 JSON
     */
    public String searchStocks(String query, int maxResults) {
        return cached("stock-search-v2", new String[]{query, String.valueOf(maxResults)}, TTL_SEARCH,
                () -> getOnce("/api/stock/search?q={q}&max_results={max}", query, maxResults));
    }

    /**
     * 获取核心财务或估值指标。
     *
     * @param code 标准股票代码
     * @return 财务指标 JSON
     */
    public String getFinancialMetrics(String code) {
        return cached("financial", new String[]{code}, TTL_FINANCIAL,
                () -> getOnce("/api/stock/financial?code={code}", code));
    }

    /**
     * 获取结构化财报数据，使用更长超时且不重试。
     *
     * @param code 标准股票代码
     * @param period 报告周期，如 annual 或 quarterly
     * @param years 回溯年数
     * @return 多期财报 JSON
     */
    public String getFinancialReports(String code, String period, int years) {
        String json = cached("financial-report:v2", new String[]{code, period, String.valueOf(years)}, TTL_FINANCIAL_REPORT,
                () -> fetchFinancialReport(code, period, years));
        return validateFinancialReport(json, period, years);
    }

    private String fetchFinancialReport(String code, String period, int years) {
        try {
            String json = request(webClient.get()
                    .uri("/api/stock/financial-report?code={code}&period={period}&years={years}", code, period, years),
                    financialReportTimeout, String.class);
            return validateFinancialReport(json, period, years);
        } catch (WebClientResponseException error) {
            rethrowBudgetFailure(error);
            int status = error.getStatusCode().value();
            return financialReportError("DATA_SERVICE_HTTP_" + status,
                    "Financial report data-service returned HTTP " + status + "; check the request or service availability.",
                    status == 429 || status >= 500, period, years);
        } catch (Exception error) {
            rethrowBudgetFailure(error);
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            Boolean retryable = cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.net.ConnectException ? Boolean.TRUE : null;
            log.warn("Financial report data-service request failed: {}", rootMessage(error));
            return financialReportError("DATA_SERVICE_UNAVAILABLE",
                    "Financial report data-service request failed; check service connectivity and retry.", retryable, period, years);
        }
    }

    private String validateFinancialReport(String json, String requestedPeriod, int requestedYears) {
        try {
            JsonNode root = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(json);
            if (root == null) throw new IllegalArgumentException("Empty financial report response");
            if ("US".equals(root.path("market").asText()) || "SEC_EDGAR".equals(root.path("provider").asText())) {
                SecFinancialsResponse response = SecFinancialsResponse.fromJson(root);
                if (!java.util.Objects.equals(requestedPeriod, response.requestedPeriod())
                        || !java.util.Objects.equals(requestedYears, response.requestedYears())) {
                    throw new IllegalArgumentException("SEC financial report request scope differs from response");
                }
                return objectMapper.valueToTree(response).toString();
            }
            if ("A_SHARE".equals(root.path("market").asText()) || "baostock".equals(root.path("provider").asText())) {
                AShareFinancialsResponse response = AShareFinancialsResponse.fromJson(root);
                if (!java.util.Objects.equals(requestedPeriod, response.period()) || requestedYears != response.requestedYears()) {
                    throw new IllegalArgumentException("A-share financial report request scope differs from response");
                }
                return response.toJson();
            }
            if ("HK".equals(root.path("market").asText()) || "akshare".equals(root.path("provider").asText())) {
                HkFinancialsResponse response = HkFinancialsResponse.fromJson(root);
                if (!java.util.Objects.equals(requestedPeriod, response.requestedPeriod()) || requestedYears != response.requestedYears()) {
                    throw new IllegalArgumentException("HK financial report request scope differs from response");
                }
                return response.toJson();
            }
            return json;
        } catch (Exception error) {
            rethrowBudgetFailure(error);
            return financialReportError("INVALID_DATA_SERVICE_RESPONSE",
                    "Financial report response does not match the requested contract; check the data-service deployment.",
                    false, requestedPeriod, requestedYears);
        }
    }

    private String financialReportError(String errorCode, String message, Boolean retryable, String period, int years) {
        // 此入口也服务 A/H 股；失败不能凭请求代码伪造市场或供应商身份。
        return objectMapper.createObjectNode().put("error", true).put("status", "ERROR")
                .put("errorCode", errorCode).put("message", message).put("retryable", retryable)
                .put("requestedPeriod", period).put("requestedYears", years).toString();
    }

    /**
     * 获取技术指标。
     *
     * @param code 标准股票代码
     * @param indicators 逗号分隔的指标名
     * @return 技术指标 JSON
     */
    public String getTechnicalIndicators(String code, String indicators) {
        return cached("technical", new String[]{code, indicators}, TTL_TECHNICAL,
                () -> getOnce("/api/stock/technical?code={code}&indicators={indicators}", code, indicators));
    }

    /**
     * 获取指定股票近期新闻。
     *
     * @param code 标准股票代码
     * @param days 回溯天数
     * @return 新闻列表 JSON
     */
    public String getStockNews(String code, int days) {
        if (days < 1 || code == null || code.isBlank()) {
            return invalidSearchRequest("Stock news requires a non-empty code and days greater than zero.");
        }
        SearchRequest request = new SearchRequest(code, "news", days <= 1 ? "d" : days <= 7 ? "w" : "m", "basic", 8, days);
        String json = cached("news:v1", new String[]{code, String.valueOf(days)}, TTL_SEARCH,
                () -> fetchSearch(request, "/api/stock/news?code={code}&days={days}", code, days));
        return validateSearch(json, request).toJson();
    }

    /**
     * 获取行业/板块表现。
     *
     * @param sector 行业或板块名称
     * @return 板块表现 JSON
     */
    public String getSectorPerformance(String sector) {
        return cached("sector", new String[]{sector}, TTL_MARKET,
                () -> get("/api/stock/sector?sector={sector}", sector));
    }

    /** @return 主要指数和市场状态等概览 JSON。 */
    public String getMarketOverview() {
        return cached("market", new String[]{"overview"}, TTL_MARKET,
                () -> get("/api/stock/market-overview"));
    }

    // ===== 网页搜索 =====

    /**
     * 执行网页搜索，并由熔断器保护上游限流（默认 basic 检索深度）。
     *
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @return 搜索结果或熔断降级 JSON
     */
    public String webSearch(String query, int maxResults) {
        return webSearch(query, maxResults, null, false);
    }

    /**
     * 执行网页搜索，可选时间范围过滤（默认 basic 检索深度）。
     *
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @param timeLimit 时间范围 d/w/m/y；非法值按不限制处理
     * @return 搜索结果或熔断降级 JSON
     */
    public String webSearch(String query, int maxResults, String timeLimit) {
        return webSearch(query, maxResults, timeLimit, false);
    }

    /**
     * 执行网页搜索，可选时间范围过滤与检索深度。
     *
     * @param timeLimit 时间范围（d/w/m/y），null 或非法值表示不限制时间。
     * @param advanced  true 时请求 Tavily advanced 深度检索（召回更全、相关性更好，
     *                  但每次消耗 2 个 credit）；false 为 basic（1 credit）。
     * @return 搜索结果或熔断降级 JSON
     */
    public String webSearch(String query, int maxResults, String timeLimit, boolean advanced) {
        if (query == null || query.isBlank() || maxResults < 1 || maxResults > 20) {
            return invalidSearchRequest("Web search requires a non-empty query and maxResults between 1 and 20.");
        }
        String tl = normalizeTimeLimit(timeLimit);
        String depth = advanced ? "advanced" : "basic";
        SearchRequest request = new SearchRequest(query, "web", tl, depth, maxResults, null);
        String json = cached("web-search:v1",
                new String[]{query, String.valueOf(maxResults), tl == null ? "" : tl, depth}, TTL_SEARCH,
                () -> tl == null
                        ? searchWithCircuitBreaker(request, "/api/search/web?q={q}&max_results={max}&depth={d}",
                                query, maxResults, depth)
                        : searchWithCircuitBreaker(
                                request,
                                "/api/search/web?q={q}&max_results={max}&timelimit={tl}&depth={d}",
                                query, maxResults, tl, depth));
        return validateSearch(json, request).toJson();
    }

    /**
     * 执行财经新闻搜索，并由熔断器保护上游限流（默认 basic 检索深度）。
     *
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @return 财经新闻结果或熔断降级 JSON
     */
    public String newsSearch(String query, int maxResults) {
        return newsSearch(query, maxResults, null, false);
    }

    /**
     * 执行财经新闻搜索，可选时间范围过滤（默认 basic 检索深度）。
     *
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @param timeLimit 时间范围 d/w/m；非法值按不限制处理
     * @return 财经新闻结果或熔断降级 JSON
     */
    public String newsSearch(String query, int maxResults, String timeLimit) {
        return newsSearch(query, maxResults, timeLimit, false);
    }

    /**
     * 执行财经新闻搜索，可选时间范围过滤与检索深度。
     *
     * @param timeLimit 时间范围（d/w/m），null 或非法值表示不限制时间。
     * @param advanced  true 时请求 Tavily advanced 深度检索（2 credit/次），false 为 basic（1 credit/次）。
     * @return 财经新闻结果或熔断降级 JSON
     */
    public String newsSearch(String query, int maxResults, String timeLimit, boolean advanced) {
        if (query == null || query.isBlank() || maxResults < 1 || maxResults > 20) {
            return invalidSearchRequest("News search requires a non-empty query and maxResults between 1 and 20.");
        }
        String tl = normalizeTimeLimit(timeLimit);
        String depth = advanced ? "advanced" : "basic";
        SearchRequest request = new SearchRequest(query, "news", tl, depth, maxResults, null);
        String json = cached("news-search:v1",
                new String[]{query, String.valueOf(maxResults), tl == null ? "" : tl, depth}, TTL_SEARCH,
                () -> tl == null
                        ? searchWithCircuitBreaker(request, "/api/search/news?q={q}&max_results={max}&depth={d}",
                                query, maxResults, depth)
                        : searchWithCircuitBreaker(
                                request,
                                "/api/search/news?q={q}&max_results={max}&timelimit={tl}&depth={d}",
                                query, maxResults, tl, depth));
        return validateSearch(json, request).toJson();
    }

    /**
     * 收敛时间范围参数，只接受 DuckDuckGo 认可的取值（d/w/m/y），其余按不过滤处理。
     *
     * @param timeLimit 调用方提供的时间范围
     * @return 归一化取值；不受支持时返回 null
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

    /** EDGAR 公告索引缓存时间。 */
    private static final Duration TTL_EDGAR_INDEX = Duration.ofHours(12);
    /** EDGAR XBRL 公司事实缓存时间。 */
    private static final Duration TTL_EDGAR_XBRL  = Duration.ofHours(12);

    /**
     * 获取 SEC EDGAR 公司公告索引。
     *
     * @param ticker 美股 ticker
     * @param filingType 公告类型，如 10-K 或 10-Q
     * @param count 最大公告数
     * @return 公告元数据 JSON
     */
    public String getEdgarFilings(String ticker, String filingType, int count) {
        return cached("edgar-filings",
                new String[]{ticker, filingType, String.valueOf(count)}, TTL_EDGAR_INDEX,
                () -> getWithTimeout("/api/edgar/filings?ticker={t}&type={type}&count={count}", edgarTimeout,
                        ticker, filingType, count));
    }

    /**
     * 下载并解析单份公告，拆成 Item 级别的章节。
     * 不走缓存，摄取流程中每份公告只调用一次。
     *
     * @param documentUrl SEC 原始公告地址
     * @param filingType 公告类型
     * @return 章节化公告内容 JSON
     */
    public String getEdgarFilingContent(String documentUrl, String filingType) {
        return getWithTimeout("/api/edgar/filing-content?url={url}&type={type}", edgarTimeout,
                documentUrl, filingType);
    }

    /**
     * 获取 SEC XBRL 结构化财务数据。
     *
     * @param ticker 美股 ticker
     * @return 校验后的 SEC 年度公司事实；失败时为明确的 ERROR 状态
     */
    public SecFinancialsResponse getEdgarXbrl(String ticker) {
        String json = cached("edgar-xbrl:v1", new String[]{ticker}, TTL_EDGAR_XBRL,
                () -> objectMapper.valueToTree(fetchEdgarXbrl(ticker)).toString());
        try {
            return SecFinancialsResponse.fromJson(objectMapper.reader()
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(json));
        } catch (Exception error) {
            rethrowBudgetFailure(error);
            return SecFinancialsResponse.failure(ticker, "INVALID_DATA_SERVICE_RESPONSE",
                    "SEC financials response does not match schemaVersion 1; check the data-service deployment.", false);
        }
    }

    private SecFinancialsResponse fetchEdgarXbrl(String ticker) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            String json;
            try {
                json = request(webClient.get().uri("/api/edgar/xbrl?ticker={t}", ticker), timeout, String.class);
            } catch (WebClientResponseException error) {
                rethrowBudgetFailure(error);
                if (attempt < MAX_RETRIES) continue;
                int status = error.getStatusCode().value();
                return SecFinancialsResponse.failure(ticker, "DATA_SERVICE_HTTP_" + status,
                        "SEC financials data-service returned HTTP " + status + "; check the ticker or service availability.",
                        status == 429 || status >= 500);
            } catch (Exception error) {
                rethrowBudgetFailure(error);
                if (attempt < MAX_RETRIES) continue;
                Throwable cause = error;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                Boolean retryable = cause instanceof java.util.concurrent.TimeoutException
                        || cause instanceof java.net.ConnectException ? Boolean.TRUE : null;
                log.warn("SEC financials data-service request failed: {}", rootMessage(error));
                return SecFinancialsResponse.failure(ticker, "DATA_SERVICE_UNAVAILABLE",
                        "SEC financials data-service request failed; check service connectivity and retry.", retryable);
            }
            try {
                return SecFinancialsResponse.fromJson(objectMapper.reader()
                        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(json));
            } catch (Exception error) {
                rethrowBudgetFailure(error);
                return SecFinancialsResponse.failure(ticker, "INVALID_DATA_SERVICE_RESPONSE",
                        "SEC financials response does not match schemaVersion 1; check the data-service deployment.", false);
            }
        }
        throw new IllegalStateException("SEC request attempt limit was not reached");
    }

    // ===== 文档解析 =====

    /**
     * 将 PDF 上传到 Python 服务，执行结构感知解析。
     * 返回包含结构化切片的 JSON，例如章节标题、页码和重叠信息。
     *
     * @param filePath 后端可读取的 PDF 路径
     * @param chunkSize 目标切片字符数
     * @param overlap 相邻切片重叠字符数
     * @return 解析结果 JSON；异常时返回结构化错误 JSON
     */
    public String parsePdf(Path filePath, int chunkSize, int overlap) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new FileSystemResource(filePath.toFile()));
        builder.part("chunk_size", String.valueOf(chunkSize));
        builder.part("overlap", String.valueOf(overlap));

        try {
            // 调用 data-service 的 multipart 文档解析端点；这里使用独立五分钟超时。
            return request(webClient.post()
                    .uri("/api/document/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build())), Duration.ofMinutes(5), String.class);
        } catch (Exception e) {
            rethrowBudgetFailure(e);
            log.error("PDF parsing failed for {}: {}", filePath.getFileName(), e.getMessage(), e);
            return "{\"error\":true,\"message\":\"PDF parsing failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 搜索供应商比本地行情端点更容易触发限流。
     * 熔断器会在上游持续失败时返回可读兜底结果，避免模型或工具调用反复等待。
     *
     * <p>关键点：底层搜索请求把 HTTP 或协议失败转成 JSON 错误字符串而<b>不抛异常</b>，
     * 而熔断器只能通过异常统计失败。因此这里必须显式识别"失败响应体"并抛出
     * {@link UpstreamSearchException}，否则熔断器会把每一次失败都记成成功、永远无法打开。</p>
     *
     * @param uri 搜索端点 URI 模板
     * @param vars URI 模板变量
     * @return 上游响应、真实错误响应或熔断降级响应
     */
    private String searchWithCircuitBreaker(SearchRequest request, String uri, Object... vars) {
        try {
            return searchCircuitBreaker.executeSupplier(() -> {
                String body = fetchSearch(request, uri, vars);
                if (DataServicePayloads.isFailure(body)) {
                    throw new UpstreamSearchException(body);
                }
                return body;
            });
        } catch (CallNotPermittedException e) {
            rethrowBudgetFailure(e);
            log.warn("Search circuit breaker is OPEN, returning fallback for: {}", uri);
            return request.failure("CIRCUIT_OPEN",
                    "Search circuit is open after repeated failures; retry later or use available evidence.", true).toJson();
        } catch (UpstreamSearchException e) {
            rethrowBudgetFailure(e);
            // 失败已被熔断器计入统计；仍把真实的错误响应体返回上层，让模型据此说明数据缺口。
            return e.body();
        }
    }

    /** 一次逻辑搜索可重试 HTTP 失败；协议错误不重试，并由外层熔断器计入同一次失败。 */
    private String fetchSearch(SearchRequest request, String uri, Object... vars) {
        SearchResponse failure = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String json = request(webClient.get().uri(uri, vars), timeout, String.class);
                return validateSearch(json, request).toJson();
            } catch (WebClientResponseException error) {
                rethrowBudgetFailure(error);
                int status = error.getStatusCode().value();
                failure = request.failure("DATA_SERVICE_HTTP_" + status,
                        "Search data-service returned HTTP " + status + "; check the request or service availability.",
                        status == 429 || status >= 500);
            } catch (Exception error) {
                rethrowBudgetFailure(error);
                Throwable cause = error;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                Boolean retryable = cause instanceof java.util.concurrent.TimeoutException
                        || cause instanceof java.net.ConnectException ? Boolean.TRUE : null;
                failure = request.failure("DATA_SERVICE_UNAVAILABLE",
                        "Search data-service request failed; check service connectivity and retry.", retryable);
            }
            log.warn("Search data-service attempt {}/{} failed for {}: {}", attempt + 1, MAX_RETRIES + 1, uri, failure.errorCode());
        }
        return failure.toJson();
    }

    private SearchResponse validateSearch(String json, SearchRequest request) {
        try {
            SearchResponse response = SearchResponse.fromJson(objectMapper.readTree(json));
            if (!request.searchType().equals(response.searchType()) || !request.depth().equals(response.requestedDepth())
                    || !java.util.Objects.equals(request.timeLimit(), response.requestedTimelimit())
                    || request.maxResults() != response.requestedMaxResults()
                    || !java.util.Objects.equals(request.days(), response.requestedDays())
                    // ToolResultCache 的查询键已按 trim/lowercase 合并；验收采用同一等价关系。
                    || !request.query().trim().equalsIgnoreCase(response.query().trim())) {
                throw new IllegalArgumentException("Search response differs from the requested scope");
            }
            return response;
        } catch (Exception error) {
            rethrowBudgetFailure(error);
            return request.failure("INVALID_DATA_SERVICE_RESPONSE",
                    "Search response does not match the requested contract; check the data-service deployment.", false);
        }
    }

    private String invalidSearchRequest(String message) {
        return objectMapper.createObjectNode().put("error", true).put("status", "ERROR")
                .put("errorCode", "INVALID_REQUEST").put("message", message).put("retryable", false).toString();
    }

    private record SearchRequest(String query, String searchType, String timeLimit, String depth, int maxResults, Integer days) {
        SearchResponse failure(String code, String message, Boolean retryable) {
            return SearchResponse.failure(query, searchType, timeLimit, depth, maxResults, days, code, message, retryable);
        }
    }

    /**
     * 统一的缓存读写入口：失败响应（{@link DataServicePayloads#isFailure}）不写缓存，
     * 避免错误固化；错误契约的判定只此一处。
     *
     * @param category 缓存类别
     * @param keyParts 构成缓存键的业务参数
     * @param ttl 缓存有效期
     * @param supplier 缓存未命中时的 HTTP 调用
     * @return 缓存值或数据源响应
     */
    private String cached(String category, String[] keyParts, Duration ttl, Supplier<String> supplier) {
        checkDeadline();
        String cachedValue = cache.getOrFetch(category, keyParts, ttl, supplier,
                result -> !DataServicePayloads.isFailure(result));
        checkDeadline();
        return cachedValue;
    }

    private <T> T request(WebClient.RequestHeadersSpec<?> request, Duration localTimeout, Class<T> responseType) {
        var run = ToolCallContext.currentRunDeadline();
        long timeoutMs = localTimeout.toMillis();
        if (run != null) {
            long remainingMs = Math.min(run.remainingMillis(), 1_800_000L);
            timeoutMs = Math.min(timeoutMs, remainingMs);
            // A local endpoint timeout must not be presented as exhaustion of the whole research run.
            request.header("X-StockSage-Remaining-Ms", Long.toString(remainingMs));
        }
        try {
            T response = request.retrieve().bodyToMono(responseType).timeout(Duration.ofMillis(timeoutMs)).block();
            checkDeadline();
            return response;
        } catch (WebClientResponseException error) {
            if (run != null && error.getStatusCode().value() == 504) {
                try {
                    JsonNode body = objectMapper.readTree(error.getResponseBodyAsString());
                    if (body != null && "RESEARCH_BUDGET_EXCEEDED".equals(body.path("code").asText())) {
                        throw new ResearchBudgetExceededException(run.runId(), ResearchBudgetExceededException.Reason.DEADLINE);
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalidBody) {
                    // Non-budget HTTP failures retain the endpoint's existing error contract.
                }
            }
            rethrowBudgetFailure(error);
            throw error;
        } catch (RuntimeException error) {
            rethrowBudgetFailure(error);
            throw error;
        }
    }

    private static void checkDeadline() {
        var run = ToolCallContext.currentRunDeadline();
        if (run != null) run.remainingMillis();
    }

    private static void rethrowBudgetFailure(Throwable error) {
        ResearchBudgetExceededException.rethrowIfPresent(error);
        checkDeadline();
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
     *
     * @param uri URI 模板
     * @param requestTimeout 单次尝试超时
     * @param maxRetries 首次调用后的最大重试次数
     * @param vars URI 模板变量
     * @return 成功响应或结构化错误 JSON
     */
    private String getWithRetries(String uri, Duration requestTimeout, int maxRetries, Object... vars) {
        Exception lastError = null;
        int maxAttempts = Math.max(0, maxRetries) + 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 通过 WebClient 调用 Python 服务，并同步等待以适配 Spring AI 工具的同步契约。
                return request(webClient.get().uri(uri, vars), requestTimeout, String.class);
            } catch (Exception e) {
                rethrowBudgetFailure(e);
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
     * <p>搜索 HTTP 边界返回结构化错误，但熔断器只能通过异常来统计失败。该异常用于把
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
