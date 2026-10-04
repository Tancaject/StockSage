package com.stocksage.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.tool.ToolCallContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 只读的 IBKR Client Portal Web API 服务。
 *
 * <p>工具层通过本服务查询认证、账户、持仓、报价和历史 K 线；本服务再调用
 * {@link IbkrWebApiClient} 访问本地 Gateway，并统一处理配置开关、熔断与错误 JSON。</p>
 *
 * <p>边界：刻意不包含下单、撤单或改单调用，Gateway 登录和双重验证也必须由用户完成。</p>
 */
@Slf4j
@Service
public class IbkrReadOnlyService {

    /** IBKR 开关、端点、超时和快照字段配置。 */
    private final IbkrProperties properties;
    /** 执行本地 Client Portal Gateway HTTP 请求。 */
    private final IbkrWebApiClient client;
    /** 将用户代码解析为 IBKR 合约搜索条件。 */
    private final IbkrInstrumentResolver instrumentResolver;
    /** 构造并序列化统一 JSON 响应。 */
    private final ObjectMapper objectMapper;
    /** Gateway 连续失败时快速拒绝后续只读调用。 */
    private final CircuitBreaker ibkrCircuitBreaker;

    /**
     * 创建只读 IBKR 门面并初始化独立熔断器。
     *
     * @param properties IBKR 配置
     * @param client Gateway HTTP 客户端
     * @param instrumentResolver 股票代码解析器
     * @param objectMapper JSON 解析器
     */
    public IbkrReadOnlyService(IbkrProperties properties, IbkrWebApiClient client,
                               IbkrInstrumentResolver instrumentResolver, ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = client;
        this.instrumentResolver = instrumentResolver;
        this.objectMapper = objectMapper;
        // Client Portal Gateway 失效时常会连续失败；熔断可避免对本地网关反复打满请求。
        this.ibkrCircuitBreaker = CircuitBreaker.of("ibkr", CircuitBreakerConfig.custom()
                .ignoreExceptions(ResearchBudgetExceededException.class)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(120))
                .slidingWindowSize(5)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
        ibkrCircuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("IBKR circuit breaker: {}", event));
    }

    /**
     * 查询 Client Portal Gateway 当前认证状态。
     *
     * @return 认证状态 JSON；关闭或失败时返回统一错误 JSON
     */
    public String getAuthStatus() {
        return execute("authStatus", () -> client.postEmpty("/iserver/auth/status"));
    }

    /**
     * 调用 tickle 保持已登录会话活跃。
     *
     * @return Gateway tickle 响应 JSON
     */
    public String tickle() {
        return execute("tickle", () -> client.postEmpty("/tickle"));
    }

    /**
     * 读取当前登录用户可见的投资组合账户列表。
     *
     * @return 带 provider、endpoint 和 timestamp 的账户 JSON
     */
    public String getPortfolioAccounts() {
        return execute("portfolioAccounts", this::portfolioAccounts);
    }

    /**
     * 读取账户摘要；未传账户时按配置或账户列表自动选择。
     *
     * @param accountId 可选账户 ID
     * @return 带来源元数据的账户摘要 JSON
     */
    public String getAccountSummary(String accountId) {
        return execute("accountSummary", () -> {
            JsonNode accounts = rawPortfolioAccounts();
            String resolvedAccountId = resolveAccountId(accountId, accounts);
            // 调用 Gateway 的账户摘要端点；账户选择顺序由 resolveAccountId 统一控制。
            JsonNode summary = client.get(uriBuilder -> uriBuilder
                    .path("/portfolio/{accountId}/summary")
                    .build(resolvedAccountId));
            return withMetadata(summary, Map.of(
                    "provider", "IBKR_WEB_API",
                    "accountId", resolvedAccountId,
                    "endpoint", "/portfolio/{accountId}/summary"
            ));
        });
    }

    /**
     * 读取账户持仓；仅返回 IBKR 提供的只读持仓数据。
     *
     * @param accountId 可选账户 ID
     * @return 带来源元数据的持仓 JSON
     */
    public String getPositions(String accountId) {
        return execute("positions", () -> {
            JsonNode accounts = rawPortfolioAccounts();
            String resolvedAccountId = resolveAccountId(accountId, accounts);
            JsonNode positions = client.get(uriBuilder -> uriBuilder
                    .path("/portfolio/{accountId}/positions/0")
                    .build(resolvedAccountId));
            return withMetadata(positions, Map.of(
                    "provider", "IBKR_WEB_API",
                    "accountId", resolvedAccountId,
                    "endpoint", "/portfolio/{accountId}/positions/0"
            ));
        });
    }

    /**
     * 获取股票实时或延迟报价，并附带订阅可用性提示。
     *
     * @param code 用户输入的美股或港股代码
     * @return 归一化报价 JSON；不支持、未登录或熔断时返回错误 JSON
     */
    public String getRealtimeQuote(String code) {
        return execute("realtimeQuote", () -> {
            IbkrInstrument instrument = instrumentResolver.resolve(code);
            if (!instrument.supported()) {
                return errorPayload(instrument.input(), instrument.message());
            }

            // 先调用 secdef/search 取得 conid，后续快照端点只接受该 IBKR 合约 ID。
            JsonNode contract = resolveContract(instrument);
            String conid = text(contract, "conid");
            if (conid.isBlank()) {
                return errorPayload(code, "IBKR did not return a conid for " + instrument.symbol());
            }

            // IBKR 要求先初始化 brokerage accounts，再请求行情订阅快照。
            client.get("/iserver/accounts");
            JsonNode snapshot = null;
            JsonNode firstQuote = objectMapper.createObjectNode();
            for (int attempt = 0; attempt < 3; attempt++) {
                snapshot = marketDataSnapshot(conid);
                firstQuote = firstArrayItem(snapshot);
                if (!isMissingPrice(firstQuote)) {
                    break;
                }
                // IBKR 行情快照首次请求可能只触发订阅，稍后才返回价格字段。
                sleepQuietly(1000);
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("provider", "IBKR_WEB_API");
            result.put("input", instrument.input());
            result.put("market", instrument.market());
            result.put("symbol", instrument.symbol());
            result.put("exchange", instrument.exchange());
            result.put("currency", instrument.currency());
            result.put("conid", conid);
            result.put("timestamp", Instant.now().toString());
            result.put("availability", text(firstQuote, "6509"));
            result.put("lastPrice", text(firstQuote, "31"));
            result.put("bid", text(firstQuote, "84"));
            result.put("ask", text(firstQuote, "86"));
            result.put("volume", text(firstQuote, "87"));
            result.put("rawAvailabilityHint", availabilityHint(text(firstQuote, "6509")));
            result.set("contract", contract);
            result.set("raw", snapshot == null ? objectMapper.createArrayNode() : snapshot);
            return result;
        });
    }

    /**
     * 获取历史 K 线数据；period 和 bar 为空时使用保守默认值。
     *
     * @param code 用户输入的美股或港股代码
     * @param period IBKR 历史窗口，如 1d、1y
     * @param bar K 线粒度，如 5min、1d
     * @return 独立版本化的 IBKR 历史行情响应
     */
    public IbkrHistoricalResponse getHistoricalBars(String code, String period, String bar) {
        String safePeriod = defaultIfBlank(period, "1d");
        String safeBar = defaultIfBlank(bar, "1h");
        String result = execute("historicalBars", () -> {
            IbkrInstrument instrument = instrumentResolver.resolve(code);
            if (!instrument.supported()) {
                return objectMapper.valueToTree(IbkrHistoricalResponse.failure(code, safePeriod, safeBar,
                        IbkrHistoricalResponse.Status.UNSUPPORTED, "UNSUPPORTED_SYMBOL", instrument.message(), false));
            }

            JsonNode contract = resolveContract(instrument);
            String conid = text(contract, "conid");
            if (conid.isBlank()) {
                return errorPayload(code, "IBKR did not return a conid for " + instrument.symbol())
                        .put("errorCode", "IBKR_CONTRACT_NOT_FOUND").put("retryable", false);
            }

            // 调用只读 history 端点；outsideRth=true 让盘前盘后数据由 IBKR 决定是否返回。
            JsonNode history = client.get(uriBuilder -> uriBuilder
                    .path("/iserver/marketdata/history")
                    .queryParam("conid", conid)
                    .queryParam("exchange", instrument.exchange())
                    .queryParam("period", safePeriod)
                    .queryParam("bar", safeBar)
                    .queryParam("outsideRth", "true")
                    .queryParam("source", "Trades")
                    .build());

            return objectMapper.valueToTree(IbkrHistoricalResponse.fromGateway(history, instrument, contract, safePeriod, safeBar));
        });
        try {
            JsonNode payload = objectMapper.readTree(result);
            if (payload.has("schemaVersion")) return IbkrHistoricalResponse.fromJson(payload);
            return IbkrHistoricalResponse.failure(code, safePeriod, safeBar, IbkrHistoricalResponse.Status.ERROR,
                    payload.path("errorCode").asText("IBKR_GATEWAY_ERROR"), text(payload, "message"),
                    payload.path("retryable").isBoolean() ? payload.get("retryable").booleanValue() : null);
        } catch (Exception error) {
            return IbkrHistoricalResponse.failure(code, safePeriod, safeBar, IbkrHistoricalResponse.Status.ERROR,
                    "INVALID_PROVIDER_DATA", "IBKR history response could not be decoded; check the Gateway and retry.", false);
        }
    }

    /**
     * 为账户列表响应追加统一元数据。
     *
     * @return 可直接序列化的账户响应节点
     */
    private JsonNode portfolioAccounts() {
        return withMetadata(rawPortfolioAccounts(), Map.of(
                "provider", "IBKR_WEB_API",
                "endpoint", "/portfolio/accounts"
        ));
    }

    /**
     * 调用原始账户端点，不做错误包装，由外层 execute 统一处理。
     *
     * @return Gateway 原始账户数组或对象
     */
    private JsonNode rawPortfolioAccounts() {
        return client.get("/portfolio/accounts");
    }

    /**
     * 按“显式参数 -> 默认配置 -> 第一个可见账户”的顺序解析账户 ID。
     *
     * @param accountId 调用方显式账户 ID
     * @param accounts Gateway 返回的可见账户树
     * @return 最终账户 ID
     */
    private String resolveAccountId(String accountId, JsonNode accounts) {
        if (accountId != null && !accountId.isBlank()) {
            return accountId.trim();
        }
        if (properties.getDefaultAccountId() != null && !properties.getDefaultAccountId().isBlank()) {
            return properties.getDefaultAccountId().trim();
        }

        // 用户没有指定账户时，使用可见账户列表中的第一个账户，保持只读工具易用。
        String resolved = findFirstAccountId(accounts);
        if (resolved.isBlank()) {
            throw new IllegalStateException("No IBKR portfolio account was found. Log in to Client Portal Gateway first.");
        }
        return resolved;
    }

    /**
     * 搜索并选择 IBKR 合约定义，返回包含 conid 的合约节点。
     *
     * @param instrument 已归一化标的
     * @return 选中的合约节点
     */
    private JsonNode resolveContract(IbkrInstrument instrument) {
        JsonNode matches = client.get(uriBuilder -> uriBuilder
                .path("/iserver/secdef/search")
                .queryParam("symbol", instrument.symbol())
                .queryParam("secType", "STK")
                .build());
        JsonNode match = chooseContract(matches, instrument);
        if (match.isMissingNode() || match.isNull()) {
            throw new IllegalStateException("No IBKR contract found for " + instrument.symbol());
        }
        return match;
    }

    /**
     * 在同名合约中优先选择与解析市场一致的候选。
     *
     * @param matches secdef/search 返回的候选数组
     * @param instrument 目标市场和符号
     * @return 最合适的候选；无候选时返回 missing node
     */
    private JsonNode chooseContract(JsonNode matches, IbkrInstrument instrument) {
        if (!matches.isArray() || matches.size() == 0) {
            return objectMapper.getNodeFactory().missingNode();
        }

        // IBKR 搜索可能返回多个同名合约；优先匹配目标市场，避免港股/美股同名误选。
        for (JsonNode match : matches) {
            String symbol = text(match, "symbol");
            String description = (text(match, "description") + " " + text(match, "companyHeader") + " " + text(match, "listingExchange"))
                    .toUpperCase(Locale.ROOT);
            boolean symbolMatches = symbol.equalsIgnoreCase(instrument.symbol());
            boolean marketMatches = instrument.market().equals("HK")
                    ? description.contains("SEHK") || description.contains("HK") || description.contains("HONG KONG")
                    : description.contains("NASDAQ") || description.contains("NYSE") || description.contains("ARCA") || description.contains("SMART") || description.isBlank();

            if (symbolMatches && marketMatches) {
                return match;
            }
        }

        return matches.get(0);
    }

    /**
     * 请求 IBKR 市场快照端点，字段集合由配置控制。
     *
     * @param conid IBKR 合约 ID
     * @return Gateway 快照数组
     */
    private JsonNode marketDataSnapshot(String conid) {
        return client.get(uriBuilder -> uriBuilder
                .path("/iserver/marketdata/snapshot")
                .queryParam("conids", conid)
                .queryParam("fields", properties.getSnapshotFields())
                .build());
    }

    /**
     * 为第三方原始响应包一层统一元数据，便于模型判断来源和时间。
     *
     * @param payload Gateway 原始数据
     * @param metadata 来源、账户或合约等稳定元数据
     * @return 包含 timestamp、metadata 和 data 的对象
     */
    private JsonNode withMetadata(JsonNode payload, Map<String, String> metadata) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("timestamp", Instant.now().toString());
        metadata.forEach(result::put);
        result.set("data", payload);
        return result;
    }

    /**
     * 构造统一错误载荷，避免工具调用异常直接泄漏为非 JSON 文本。
     *
     * @param input 当前操作或用户输入
     * @param message 可向用户展示的失败原因
     * @return 顶层带 error=true 的 JSON 对象
     */
    private ObjectNode errorPayload(String input, String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("error", true);
        result.put("provider", "IBKR_WEB_API");
        result.put("input", input == null ? "" : input);
        result.put("message", message);
        result.put("errorCode", "IBKR_GATEWAY_ERROR");
        result.putNull("retryable");
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    /**
     * 所有 IBKR 操作的统一保护层：配置开关、熔断和异常 JSON 化都在这里处理。
     *
     * @param operation 日志和错误响应中的操作名
     * @param call 实际 Gateway 调用
     * @return 序列化后的成功或错误 JSON
     */
    private String execute(String operation, IbkrCall call) {
        ToolCallContext.remainingMillis(Long.MAX_VALUE);
        if (!properties.isEnabled()) {
            return write(errorPayload(operation,
                    "IBKR Web API integration is disabled in StockSage. Set stocksage.ibkr.enabled=true, then restart the backend. The local Gateway may already be running.")
                    .put("errorCode", "IBKR_DISABLED").put("retryable", false));
        }

        try {
            return ibkrCircuitBreaker.executeSupplier(() -> {
                try {
                    return write(call.invoke());
                } catch (WebClientResponseException e) {
                    if (isGatewaySessionFailure(e)) {
                        return handleGatewaySessionFailure(operation, e);
                    }
                    if (isGatewayServerFailure(e)) {
                        return handleGatewayServerFailure(operation, e);
                    }
                    throw e;
                }
            });
        } catch (CallNotPermittedException e) {
            log.warn("IBKR circuit breaker is OPEN for operation: {}", operation);
            return write(errorPayload(operation,
                    "IBKR service temporarily unavailable (circuit breaker open). The gateway may need re-login.")
                    .put("errorCode", "IBKR_CIRCUIT_OPEN").put("retryable", true));
        } catch (Exception e) {
            ResearchBudgetExceededException.rethrowIfPresent(e);
            ToolCallContext.remainingMillis(Long.MAX_VALUE);
            if (isGatewayTimeoutFailure(e)) {
                return handleGatewayTimeoutFailure(operation, e);
            }
            log.warn("IBKR Web API operation failed: {}", operation, e);
            if (e instanceof WebClientResponseException httpError) {
                int status = httpError.getStatusCode().value();
                return write(errorPayload(operation, "IBKR Web API call failed: " + e.getMessage())
                        .put("errorCode", "IBKR_HTTP_" + status).put("retryable", status == 429 || status >= 500));
            }
            return write(errorPayload(operation, "IBKR Web API call failed: " + e.getMessage()));
        }
    }

    /** 识别需要用户重新登录或刷新会话的 400/401 响应。 */
    private boolean isGatewaySessionFailure(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        return status == 400 || status == 401;
    }

    /** 识别 Gateway 的 5xx 服务端故障。 */
    private boolean isGatewayServerFailure(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        return status >= 500 && status < 600;
    }

    /** 沿异常链识别 WebClient/Reactor 包装后的超时。 */
    private boolean isGatewayTimeoutFailure(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 将会话失败转换为带重新登录指引的错误 JSON。 */
    private String handleGatewaySessionFailure(String operation, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        String message = gatewaySessionMessage(status, e.getStatusText());
        log.warn("IBKR Web API operation {} returned {} {}. {}", operation, status, e.getStatusText(), message);
        log.debug("IBKR Web API operation {} returned body: {}", operation, e.getResponseBodyAsString(), e);
        return write(errorPayload(operation, message)
                .put("errorCode", status == 401 ? "IBKR_AUTH_REQUIRED" : "IBKR_REQUEST_REJECTED").put("retryable", false));
    }

    /** 将 Gateway 5xx 转换为按操作定制的错误 JSON。 */
    private String handleGatewayServerFailure(String operation, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        String message = gatewayServerMessage(operation, status, e.getStatusText());
        log.warn("IBKR Web API operation {} returned {} {}. {}", operation, status, e.getStatusText(), message);
        log.debug("IBKR Web API operation {} returned body: {}", operation, e.getResponseBodyAsString(), e);
        return write(errorPayload(operation, message).put("errorCode", "IBKR_HTTP_" + status).put("retryable", true));
    }

    /** 将超时转换为可操作的重试提示，并保留详细堆栈到 DEBUG 日志。 */
    private String handleGatewayTimeoutFailure(String operation, Throwable e) {
        String message = gatewayTimeoutMessage(operation);
        log.warn("IBKR Web API operation {} timed out. {}", operation, message);
        log.debug("IBKR Web API operation {} timeout stack", operation, e);
        return write(errorPayload(operation, message).put("errorCode", "IBKR_TIMEOUT").put("retryable", true));
    }

    /** 生成会话错误的人类可读说明。 */
    private String gatewaySessionMessage(int status, String statusText) {
        String suffix = "Log in at https://localhost:5000, complete any required 2FA, then retry.";
        if (status == 401) {
            return "IBKR Client Portal Gateway session is not authenticated (401 "
                    + defaultIfBlank(statusText, "Unauthorized") + "). " + suffix;
        }
        return "IBKR Client Portal Gateway rejected the request (" + status + " "
                + defaultIfBlank(statusText, "Bad Request")
                + "). The Gateway browser session may be stale or contract search may not be ready. " + suffix;
    }

    /** 生成普通或历史行情端点的 5xx 说明。 */
    private String gatewayServerMessage(String operation, int status, String statusText) {
        String statusLabel = status + " " + defaultIfBlank(statusText, "Internal Server Error");
        if ("historicalBars".equals(operation)) {
            return "IBKR Client Portal Gateway returned " + statusLabel
                    + " from the history endpoint. The 1D intraday view uses period=24h and bar=5min; Gateway can reject it when the local session, market data subscription, pacing limit, or instrument history backend is not ready. Retry, refresh the Gateway session, or use the daily range.";
        }
        return "IBKR Client Portal Gateway returned " + statusLabel
                + ". Retry after refreshing the Gateway session at https://localhost:5000.";
    }

    /** 生成普通或历史行情端点的超时说明。 */
    private String gatewayTimeoutMessage(String operation) {
        if ("historicalBars".equals(operation)) {
            return "IBKR Client Portal Gateway timed out after 10000ms while calling the history endpoint. The Workbench daily cockpit uses period=1y and bar=1d, while the 1D intraday view uses period=24h and bar=5min; Gateway can stall when the local session, market data subscription, pacing limit, or instrument history backend is not ready. Retry, refresh the Gateway session, or use another range.";
        }
        return "IBKR Client Portal Gateway timed out after 10000ms. Retry after refreshing the Gateway session at https://localhost:5000.";
    }

    /** 将内部 JSON 节点序列化为 Spring AI 工具可返回的字符串。 */
    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize IBKR payload", e);
        }
    }

    /** 递归遍历不同 Gateway 版本的账户响应结构，查找第一个账户 ID。 */
    private String findFirstAccountId(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                String found = findAccountIdInObject(item);
                if (!found.isBlank()) {
                    return found;
                }
            }
        }

        if (node.isObject()) {
            String found = findAccountIdInObject(node);
            if (!found.isBlank()) {
                return found;
            }
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                found = findFirstAccountId(children.next());
                if (!found.isBlank()) {
                    return found;
                }
            }
        }

        return "";
    }

    /** 按多个兼容字段名从单个对象中读取账户 ID。 */
    private String findAccountIdInObject(JsonNode node) {
        for (String field : new String[]{"accountId", "account_id", "acctId", "id"}) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** 将 IBKR 字段 6509 映射为模型易理解的行情可用性。 */
    private String availabilityHint(String availability) {
        if (availability == null || availability.isBlank()) {
            return "UNKNOWN";
        }
        if (availability.contains("R")) {
            return "REALTIME";
        }
        if (availability.contains("D")) {
            return "DELAYED";
        }
        if (availability.contains("N")) {
            return "NO_SUBSCRIPTION";
        }
        return "UNKNOWN";
    }

    /** 判断快照是否尚未返回 last/bid/ask 任一价格。 */
    private boolean isMissingPrice(JsonNode quote) {
        return text(quote, "31").isBlank() && text(quote, "84").isBlank() && text(quote, "86").isBlank();
    }

    /** 读取快照数组第一项；缺失时返回空对象，简化调用方判空。 */
    private JsonNode firstArrayItem(JsonNode node) {
        if (node != null && node.isArray() && node.size() > 0) {
            return node.get(0);
        }
        return objectMapper.createObjectNode();
    }

    /** 容错读取 JSON 文本字段。 */
    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
    }

    /** 将 null 或空白文本替换为给定默认值。 */
    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 等待行情订阅生效；中断时恢复线程中断标记。 */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(ToolCallContext.remainingMillis(millis));
            ToolCallContext.remainingMillis(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 允许 execute 统一包装任意返回 JSON 节点的 Gateway 调用。 */
    @FunctionalInterface
    private interface IbkrCall {
        JsonNode invoke();
    }
}
