package com.stocksage.trace;

import com.stocksage.agent.AgentStep;
import com.stocksage.config.PhoenixTraceProperties;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 向 Phoenix 发送 OpenInference 风格的链路片段，用于对话和 RAG 可观测性。
 *
 * <p>{@link TraceService} 保存的数据库链路负责支撑前端界面；
 * 本服务在启用时把同一生命周期镜像到 Phoenix，关闭追踪时则不执行任何操作。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoenixTraceService {

    private static final AttributeKey<String> SPAN_KIND = AttributeKey.stringKey("openinference.span.kind");
    private static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("input.value");
    private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("output.value");
    private static final AttributeKey<String> TRACE_ID = AttributeKey.stringKey("stocksage.trace_id");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("stocksage.user_id");
    private static final AttributeKey<Long> CONVERSATION_ID = AttributeKey.longKey("stocksage.conversation_id");
    private static final AttributeKey<Long> DURATION_MS = AttributeKey.longKey("stocksage.duration_ms");
    private static final AttributeKey<Long> TOKEN_COUNT = AttributeKey.longKey("llm.token_count.total");

    private final Tracer tracer;
    private final PhoenixTraceProperties properties;
    private final ConcurrentHashMap<String, Span> rootSpans = new ConcurrentHashMap<>();

    /**
     * 为单轮对话启动根链路片段。
     */
    public void startTrace(String traceId, String userId, Long conversationId, String userQuery) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Span span = tracer.spanBuilder("stocksage.chat")
                    .setAttribute(SPAN_KIND, "CHAIN")
                    .setAttribute(INPUT_VALUE, safe(userQuery))
                    .setAttribute(TRACE_ID, traceId)
                    .setAttribute(USER_ID, safe(userId))
                    .setAttribute(CONVERSATION_ID, conversationId == null ? 0L : conversationId)
                    .startSpan();
            rootSpans.put(traceId, span);
        } catch (Exception e) {
            log.warn("Failed to start Phoenix trace, traceId={}", traceId, e);
        }
    }

    /**
     * 为推理、检索或工具步骤追加子链路片段。
     */
    public void addStep(String traceId, AgentStep step) {
        if (!properties.isEnabled()) {
            return;
        }
        Span parent = rootSpans.get(traceId);
        if (parent == null || step == null) {
            return;
        }
        try {
            String spanName = spanName(step);
            Span span = tracer.spanBuilder(spanName)
                    .setParent(Context.root().with(parent))
                    .setAttribute(SPAN_KIND, spanKind(step))
                    .setAttribute(TRACE_ID, traceId)
                    .setAttribute(INPUT_VALUE, safe(step.getActionInput()))
                    .setAttribute(OUTPUT_VALUE, safe(step.getObservation()))
                    .setAttribute(DURATION_MS, step.getDurationMs())
                    .setAttribute(TOKEN_COUNT, (long) step.getTokenCount())
                    .startSpan();
            span.end();
        } catch (Exception e) {
            log.warn("Failed to add Phoenix trace step, traceId={}", traceId, e);
        }
    }

    /**
     * 关闭根对话链路片段，并附加最终状态和用量属性。
     */
    public void endTrace(String traceId, String status, int totalTokens, long durationMs) {
        if (!properties.isEnabled()) {
            return;
        }
        Span span = rootSpans.remove(traceId);
        if (span == null) {
            return;
        }
        try {
            span.setAttribute("stocksage.status", safe(status));
            span.setAttribute(DURATION_MS, durationMs);
            span.setAttribute(TOKEN_COUNT, (long) totalTokens);
            if (!"success".equalsIgnoreCase(status)) {
                span.setStatus(StatusCode.ERROR, safe(status));
            }
            span.end();
        } catch (Exception e) {
            log.warn("Failed to end Phoenix trace, traceId={}", traceId, e);
        }
    }

    /**
     * 记录发生在主对话流之外的文档搜索调用。
     */
    public void recordRetrieval(String query, int resultCount, long durationMs, String output) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Span span = tracer.spanBuilder("stocksage.docs_search")
                    .setAttribute(SPAN_KIND, "RETRIEVER")
                    .setAttribute(INPUT_VALUE, safe(query))
                    .setAttribute(OUTPUT_VALUE, safe(output))
                    .setAttribute("retrieval.documents.count", resultCount)
                    .setAttribute(DURATION_MS, durationMs)
                    .startSpan();
            span.end();
        } catch (Exception e) {
            log.warn("Failed to record Phoenix retrieval span", e);
        }
    }

    /**
     * 根据追踪步骤动作生成 Phoenix span 名称。
     */
    private String spanName(AgentStep step) {
        String action = step.getAction();
        if (action == null || action.isBlank()) {
            return "stocksage.step";
        }
        return "stocksage." + action.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /**
     * 将 StockSage 动作粗略映射到 OpenInference span kind。
     */
    private String spanKind(AgentStep step) {
        String action = step.getAction();
        if (action == null) {
            return "CHAIN";
        }
        String normalized = action.toLowerCase();
        if (normalized.contains("retrieval")) {
            return "RETRIEVER";
        }
        if (normalized.contains("tool") || normalized.contains("search") || normalized.contains("market")) {
            return "TOOL";
        }
        return "CHAIN";
    }

    /**
     * Phoenix 属性不接受 null，统一转为空字符串。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }
}
