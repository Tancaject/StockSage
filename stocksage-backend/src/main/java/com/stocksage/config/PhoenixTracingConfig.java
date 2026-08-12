package com.stocksage.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 装配 Phoenix 可观测性使用的专用 OpenTelemetry 管线。
 *
 * <p>这些 Bean 与 Spring 默认遥测隔离，使 StockSage 能将对话和 RAG 链路片段保持为可选能力，
 * 并统一由 {@link PhoenixTraceProperties} 控制。</p>
 */
@Configuration
@EnableConfigurationProperties(PhoenixTraceProperties.class)
public class PhoenixTracingConfig {

    /**
     * 创建 Phoenix 专用 TracerProvider。
     *
     * <p>即使 Phoenix 上报关闭，也会返回可用 Provider；区别只是不开启 OTLP exporter。
     * 这样业务代码可以始终注入 Tracer，而不需要在调用侧判断观测功能是否启用。</p>
     *
     * @param properties Phoenix 开关、端点、项目名和超时配置
     * @return 可选挂载 OTLP exporter 的追踪提供器
     */
    @Bean(destroyMethod = "close")
    public SdkTracerProvider phoenixTracerProvider(PhoenixTraceProperties properties) {
        Resource resource = Resource.getDefault().merge(Resource.builder()
                .put("service.name", "stocksage-backend")
                .put("openinference.project.name", properties.getProjectName())
                .build());

        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .setResource(resource);

        if (properties.isEnabled()) {
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(properties.getEndpoint())
                    .setTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .build();
            builder.addSpanProcessor(SimpleSpanProcessor.create(exporter));
        }

        return builder.build();
    }

    /**
     * 基于 Phoenix TracerProvider 构造独立 OpenTelemetry 实例。
     *
     * <p>该实例与 Spring 默认遥测隔离，避免可选 Phoenix 配置影响其他监控链路。</p>
     *
     * @param phoenixTracerProvider Phoenix 专用追踪提供器
     * @return 业务代码可注入的独立 OpenTelemetry 实例
     */
    @Bean
    public OpenTelemetry phoenixOpenTelemetry(SdkTracerProvider phoenixTracerProvider) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(phoenixTracerProvider)
                .build();
    }

    /**
     * 暴露 StockSage 业务追踪使用的 Tracer。
     *
     * @param phoenixOpenTelemetry Phoenix 专用 OpenTelemetry 实例
     * @return 使用 {@code stocksage-backend} instrumentation scope 的 Tracer
     */
    @Bean
    public Tracer stockSageTracer(OpenTelemetry phoenixOpenTelemetry) {
        return phoenixOpenTelemetry.getTracer("stocksage-backend");
    }
}
