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
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 只读的 IBKR Client Portal Web API 服务。
 *
 * <p>工具层通过本服务查询认证状态和历史 K 线；本服务再调用
 * {@link IbkrWebApiClient} 访问本地 Gateway，并统一处理配置开关、熔断与错误 JSON。</p>
 *
 * <p>边界：刻意不包含下单、撤单或改单调用，Gateway 登录和双重验证也必须由用户完成。</p>
 */
@Slf4j
@Service
public class IbkrReadOnlyService {

    /** IBKR 开关、端点和超时配置。 */
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

    /** 允许 execute 统一包装任意返回 JSON 节点的 Gateway 调用。 */
    @FunctionalInterface
    private interface IbkrCall {
        JsonNode invoke();
    }
}
