package com.stocksage.ibkr;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * IBKR Client Portal Web API 的 Spring 配置载体。
 *
 * <p>{@link IbkrWebApiClient} 用它创建只读 HTTP 客户端，
 * {@link IbkrSessionKeepaliveScheduler} 用它控制会话保活；
 * 默认指向本地自签名 Gateway，且集成保持关闭。</p>
 */
@Component
@ConfigurationProperties(prefix = "stocksage.ibkr")
@Getter
@Setter
public class IbkrProperties {

    /** 是否启用 IBKR Client Portal 集成；默认关闭，避免本地未登录时影响普通功能。 */
    private boolean enabled = false;

    /** 本地 Client Portal Gateway API 地址。 */
    private String baseUrl = "https://localhost:5000/v1/api";

    /** 单次 Gateway 请求超时时间，单位毫秒。 */
    private long timeoutMs = 10000;

    /** 是否信任本地自签名 HTTPS 证书；开发环境通常需要开启。 */
    private boolean insecureSsl = true;

    /** 是否开启会话保活定时任务。 */
    private boolean keepaliveEnabled = true;

    /** 会话保活间隔，单位毫秒。 */
    private long keepaliveIntervalMs = 60000;

    /** 应用启动后首次保活延迟，单位毫秒。 */
    private long keepaliveInitialDelayMs = 15000;

}
