package com.stocksage.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 本服务刻意不包含下单、撤单或改单调用。
 */
@Slf4j
@Service
public class IbkrReadOnlyService {

    private final IbkrProperties properties;
    private final IbkrWebApiClient client;
    private final IbkrInstrumentResolver instrumentResolver;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker ibkrCircuitBreaker;

    public IbkrReadOnlyService(IbkrProperties properties, IbkrWebApiClient client,
                               IbkrInstrumentResolver instrumentResolver, ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = client;
        this.instrumentResolver = instrumentResolver;
        this.objectMapper = objectMapper;
        // Client Portal Gateway 失效时常会连续失败；熔断可避免对本地网关反复打满请求。
        this.ibkrCircuitBreaker = CircuitBreaker.of("ibkr", CircuitBreakerConfig.custom()
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
     */
    public String getAuthStatus() {
        return execute("authStatus", () -> client.postEmpty("/iserver/auth/status"));
    }

    /**
     * 调用 tickle 保持已登录会话活跃。
     */
    public String tickle() {
        return execute("tickle", () -> client.postEmpty("/tickle"));
    }

    /**
     * 读取当前登录用户可见的投资组合账户列表。
     */
    public String getPortfolioAccounts() {
        return execute("portfolioAccounts", this::portfolioAccounts);
    }

    /**
     * 读取账户摘要；未传账户时按配置或账户列表自动选择。
     */
    public String getAccountSummary(String accountId) {
        return execute("accountSummary", () -> {
            JsonNode accounts = rawPortfolioAccounts();
            String resolvedAccountId = resolveAccountId(accountId, accounts);
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
     */
    public String getRealtimeQuote(String code) {
        return execute("realtimeQuote", () -> {
            IbkrInstrument instrument = instrumentResolver.resolve(code);
            if (!instrument.supported()) {
                return errorPayload(instrument.input(), instrument.message());
            }

            JsonNode contract = resolveContract(instrument);
            String conid = text(contract, "conid");
            if (conid.isBlank()) {
                return errorPayload(code, "IBKR did not return a conid for " + instrument.symbol());
            }

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
     */
    public String getHistoricalBars(String code, String period, String bar) {
        return execute("historicalBars", () -> {
            IbkrInstrument instrument = instrumentResolver.resolve(code);
            if (!instrument.supported()) {
                return errorPayload(instrument.input(), instrument.message());
            }

            JsonNode contract = resolveContract(instrument);
            String conid = text(contract, "conid");
            if (conid.isBlank()) {
                return errorPayload(code, "IBKR did not return a conid for " + instrument.symbol());
            }

            String safePeriod = defaultIfBlank(period, "1d");
            String safeBar = defaultIfBlank(bar, "1h");
            JsonNode history = client.get(uriBuilder -> uriBuilder
                    .path("/iserver/marketdata/history")
                    .queryParam("conid", conid)
                    .queryParam("exchange", instrument.exchange())
                    .queryParam("period", safePeriod)
                    .queryParam("bar", safeBar)
                    .queryParam("outsideRth", "true")
                    .queryParam("source", "Trades")
                    .build());

            return withMetadata(history, Map.of(
                    "provider", "IBKR_WEB_API",
                    "input", instrument.input(),
                    "market", instrument.market(),
                    "symbol", instrument.symbol(),
                    "exchange", instrument.exchange(),
                    "currency", instrument.currency(),
                    "conid", conid,
                    "period", safePeriod,
                    "bar", safeBar,
                    "note", "IBKR history endpoint returns up to 1000 data points and is subject to Web API pacing limits."
            ));
        });
    }

    /**
     * 为账户列表响应追加统一元数据。
     */
    private JsonNode portfolioAccounts() {
        return withMetadata(rawPortfolioAccounts(), Map.of(
                "provider", "IBKR_WEB_API",
                "endpoint", "/portfolio/accounts"
        ));
    }

    /**
     * 调用原始账户端点，不做错误包装，由外层 execute 统一处理。
     */
    private JsonNode rawPortfolioAccounts() {
        return client.get("/portfolio/accounts");
    }

    /**
     * 按“显式参数 -> 默认配置 -> 第一个可见账户”的顺序解析账户 ID。
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
     */
    private ObjectNode errorPayload(String input, String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("error", true);
        result.put("provider", "IBKR_WEB_API");
        result.put("input", input == null ? "" : input);
        result.put("message", message);
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    /**
     * 所有 IBKR 操作的统一保护层：配置开关、熔断和异常 JSON 化都在这里处理。
     */
    private String execute(String operation, IbkrCall call) {
        if (!properties.isEnabled()) {
            return write(errorPayload(operation,
                    "IBKR Web API integration is disabled in StockSage. Set stocksage.ibkr.enabled=true, then restart the backend. The local Gateway may already be running."));
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
                    "IBKR service temporarily unavailable (circuit breaker open). The gateway may need re-login."));
        } catch (Exception e) {
            if (isGatewayTimeoutFailure(e)) {
                return handleGatewayTimeoutFailure(operation, e);
            }
            log.warn("IBKR Web API operation failed: {}", operation, e);
            return write(errorPayload(operation, "IBKR Web API call failed: " + e.getMessage()));
        }
    }

    private boolean isGatewaySessionFailure(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        return status == 400 || status == 401;
    }

    private boolean isGatewayServerFailure(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        return status >= 500 && status < 600;
    }

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

    private String handleGatewaySessionFailure(String operation, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        String message = gatewaySessionMessage(status, e.getStatusText());
        log.warn("IBKR Web API operation {} returned {} {}. {}", operation, status, e.getStatusText(), message);
        log.debug("IBKR Web API operation {} returned body: {}", operation, e.getResponseBodyAsString(), e);
        return write(errorPayload(operation, message));
    }

    private String handleGatewayServerFailure(String operation, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        String message = gatewayServerMessage(operation, status, e.getStatusText());
        log.warn("IBKR Web API operation {} returned {} {}. {}", operation, status, e.getStatusText(), message);
        log.debug("IBKR Web API operation {} returned body: {}", operation, e.getResponseBodyAsString(), e);
        return write(errorPayload(operation, message));
    }

    private String handleGatewayTimeoutFailure(String operation, Throwable e) {
        String message = gatewayTimeoutMessage(operation);
        log.warn("IBKR Web API operation {} timed out. {}", operation, message);
        log.debug("IBKR Web API operation {} timeout stack", operation, e);
        return write(errorPayload(operation, message));
    }

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

    private String gatewayServerMessage(String operation, int status, String statusText) {
        String statusLabel = status + " " + defaultIfBlank(statusText, "Internal Server Error");
        if ("historicalBars".equals(operation)) {
            return "IBKR Client Portal Gateway returned " + statusLabel
                    + " from the history endpoint. The 1D intraday view uses period=24h and bar=5min; Gateway can reject it when the local session, market data subscription, pacing limit, or instrument history backend is not ready. Retry, refresh the Gateway session, or use the daily range.";
        }
        return "IBKR Client Portal Gateway returned " + statusLabel
                + ". Retry after refreshing the Gateway session at https://localhost:5000.";
    }

    private String gatewayTimeoutMessage(String operation) {
        if ("historicalBars".equals(operation)) {
            return "IBKR Client Portal Gateway timed out after 10000ms while calling the history endpoint. The Workbench daily cockpit uses period=1y and bar=1d, while the 1D intraday view uses period=24h and bar=5min; Gateway can stall when the local session, market data subscription, pacing limit, or instrument history backend is not ready. Retry, refresh the Gateway session, or use another range.";
        }
        return "IBKR Client Portal Gateway timed out after 10000ms. Retry after refreshing the Gateway session at https://localhost:5000.";
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize IBKR payload", e);
        }
    }

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

    private String findAccountIdInObject(JsonNode node) {
        for (String field : new String[]{"accountId", "account_id", "acctId", "id"}) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

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

    private boolean isMissingPrice(JsonNode quote) {
        return text(quote, "31").isBlank() && text(quote, "84").isBlank() && text(quote, "86").isBlank();
    }

    private JsonNode firstArrayItem(JsonNode node) {
        if (node != null && node.isArray() && node.size() > 0) {
            return node.get(0);
        }
        return objectMapper.createObjectNode();
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface IbkrCall {
        JsonNode invoke();
    }
}
