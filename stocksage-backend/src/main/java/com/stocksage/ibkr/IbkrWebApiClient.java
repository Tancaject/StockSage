package com.stocksage.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * 本地 IBKR Client Portal Gateway 的轻量 JSON 客户端。
 *
 * <p>{@link IbkrReadOnlyService} 负责业务级熔断和错误降级；本类只负责 TLS、请求头、超时和 JSON 解析。</p>
 */
@Slf4j
@Component
public class IbkrWebApiClient {

    /** Gateway 地址、超时和自签名证书开关。 */
    private final IbkrProperties properties;
    /** 将响应体转换为 JSON 树。 */
    private final ObjectMapper objectMapper;
    /** 预配置 Gateway 基地址、Origin、Referer 和可选宽松 TLS 的客户端。 */
    private final WebClient webClient;
    /** 每个 Gateway 请求的超时。 */
    private final Duration timeout;

    /**
     * 初始化 IBKR Web API 客户端。
     *
     * <p>Client Portal Gateway 默认使用本地自签名 HTTPS，因此 WebClient 构建时会根据配置决定是否信任该证书。</p>
     *
     * @param properties IBKR Gateway 配置
     * @param objectMapper JSON 解析器
     */
    public IbkrWebApiClient(IbkrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = buildWebClient(properties);
        this.timeout = Duration.ofMillis(properties.getTimeoutMs());
    }

    /**
     * 对固定路径发起 GET 请求并解析 JSON。
     *
     * @param path 以 Gateway baseUrl 为基准的 API 路径
     * @return 解析后的 JSON 节点
     */
    public JsonNode get(String path) {
        return read(webClient.get().uri(path).retrieve().bodyToMono(String.class).timeout(timeout).block());
    }

    /**
     * 对需要动态查询参数的 URI 发起 GET 请求并解析 JSON。
     *
     * @param uriFunction WebClient URI 构建函数
     * @return 解析后的 JSON 节点
     */
    public JsonNode get(Function<UriBuilder, URI> uriFunction) {
        return read(webClient.get().uri(uriFunction).retrieve().bodyToMono(String.class).timeout(timeout).block());
    }

    /**
     * 向指定路径发送空 JSON 对象 POST。
     *
     * <p>IBKR 的 tickle 等接口要求 POST，但请求体可以为空对象。</p>
     *
     * @param path Gateway API 路径
     * @return 解析后的 JSON 节点
     */
    public JsonNode postEmpty(String path) {
        return read(webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .block());
    }

    /**
     * 将 IBKR 响应正文解析为 JsonNode。
     *
     * <p>空响应按空对象处理；非 JSON 响应通常意味着 Gateway 未登录、代理页返回 HTML 或本地服务异常。</p>
     *
     * @param body Gateway 原始响应体
     * @return JSON 树；空响应返回空对象
     */
    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("IBKR Web API returned non-JSON response: " + body, e);
        }
    }

    /**
     * 构造带 IBKR Gateway 必要请求头的 WebClient。
     *
     * <p>Origin 和 Referer 与 Gateway 本地地址保持一致，减少 Client Portal 对跨源请求的拒绝概率。</p>
     *
     * @param properties Gateway 配置
     * @return 可复用的 WebClient
     */
    private WebClient buildWebClient(IbkrProperties properties) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, "StockSage/0.1")
                .defaultHeader(HttpHeaders.ORIGIN, gatewayOrigin(properties.getBaseUrl()))
                .defaultHeader(HttpHeaders.REFERER, gatewayOrigin(properties.getBaseUrl()) + "/");

        if (properties.isInsecureSsl()) {
            try {
                // 调用 Netty 的信任全部证书管理器，仅用于用户明确开启的本地自签名 Gateway。
                SslContext sslContext = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
                HttpClient httpClient = HttpClient.create().secure(spec -> spec.sslContext(sslContext));
                builder.clientConnector(new ReactorClientHttpConnector(httpClient));
            } catch (SSLException e) {
                throw new IllegalStateException("Failed to configure IBKR Web API SSL client", e);
            }
        }

        return builder.build();
    }

    /**
     * 从 baseUrl 提取 Gateway origin。
     *
     * <p>解析失败时回退到 IBKR Client Portal 的默认本地地址。</p>
     *
     * @param baseUrl 完整 Gateway API 基地址
     * @return scheme、host 和可选 port 组成的 origin
     */
    private String gatewayOrigin(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception e) {
            return "https://localhost:5000";
        }
    }
}
