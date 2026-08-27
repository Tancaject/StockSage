package com.stocksage.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phoenix 链路追踪配置项。
 *
 * <p>所有配置都使用 {@code stocksage.phoenix.*} 前缀，默认关闭，避免本地未启动 Phoenix 时影响后端运行。</p>
 */
@ConfigurationProperties(prefix = "stocksage.phoenix")
@Getter
@Setter
public class PhoenixTraceProperties {

    /**
     * 启用后，后端对话链路会通过 OTLP 导出到 Phoenix。
     */
    private boolean enabled = false;

    /**
     * Phoenix OTLP HTTP 链路端点。
     */
    private String endpoint = "http://localhost:6006/v1/traces";

    /**
     * Phoenix 界面中显示的项目名称。
     */
    private String projectName = "stocksage-rag";

    /** OTLP 上报超时时间，单位毫秒。 */
    private long timeoutMs = 5000;

}
