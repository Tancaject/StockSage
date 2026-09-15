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
import java.util.Locale;

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

    /** 正文通过 capture-content 单独授权；不把用户标识写入观测存储。 */
    private static final AttributeKey<String> SPAN_KIND = AttributeKey.stringKey("openinference.span.kind");
    private static final AttributeKey<String> INPUT_VALUE = AttributeKey.stringKey("input.value");
    private static final AttributeKey<String> OUTPUT_VALUE = AttributeKey.stringKey("output.value");
    private static final AttributeKey<String> TRACE_ID = AttributeKey.stringKey("stocksage.trace_id");
    private static final AttributeKey<Long> DURATION_MS = AttributeKey.longKey("stocksage.duration_ms");
    private static final AttributeKey<Long> TOKEN_COUNT = AttributeKey.longKey("llm.token_count.total");

    /** OpenTelemetry span 创建入口；禁用 Phoenix 时所有公开方法直接返回。 */
    private final Tracer tracer;
    /** 控制 Phoenix 是否启用及其导出配置。 */
    private final PhoenixTraceProperties properties;
    /** 进程内尚未结束的根 span，按 StockSage traceId 关联子步骤。 */
    private final ConcurrentHashMap<String, Span> rootSpans = new ConcurrentHashMap<>();

    /**
     * 为单轮对话启动根链路片段。
     *
     * @param traceId StockSage 持久化链路 ID
     * @param userId 当前用户 ID，不导出到 Phoenix
     * @param conversationId 会话 ID，不导出到 Phoenix
     * @param userQuery 用户问题，作为根 span 输入
     */
    public void startTrace(String traceId, String userId, Long conversationId, String userQuery) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Span span = tracer.spanBuilder("stocksage.chat")
                    .setAttribute(SPAN_KIND, "CHAIN")
                    .setAttribute(TRACE_ID, traceId)
                    .startSpan();
            setTextAttribute(span, INPUT_VALUE.getKey(), userQuery);
            rootSpans.put(traceId, span);
        } catch (Exception e) {
            log.warn("Failed to start Phoenix trace, traceId={}", traceId, e);
        }
    }

    /**
     * 为推理、检索或工具步骤追加并立即结束一个子 span。
     *
     * @param traceId 父根 span 对应的 StockSage 链路 ID
     * @param step 已由 {@link TraceService} 接收的同一业务步骤
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
                    .setAttribute(DURATION_MS, step.getDurationMs())
                    .setAttribute(TOKEN_COUNT, (long) step.getTokenCount())
                    .startSpan();
            setTextAttribute(span, INPUT_VALUE.getKey(), step.getActionInput());
            setTextAttribute(span, OUTPUT_VALUE.getKey(), step.getObservation());
            setTextAttribute(span, "stocksage.action", step.getAction());
            addRoutingAttributes(span, step);
            span.end();
        } catch (Exception e) {
            log.warn("Failed to add Phoenix trace step, traceId={}", traceId, e);
        }
    }

    /** 路由说明也属于正文，统一受 capture-content 开关控制。 */
    private void addRoutingAttributes(Span span, AgentStep step) {
        if (step.getAttributes() == null
                || !"routing-decision".equals(step.getAttributes().get("kind"))) {
            return;
        }
        setTextAttribute(span, "stocksage.routing.source", step.getAttributes().get("source"));
        setTextAttribute(span, "stocksage.routing.raw_route", step.getAttributes().get("rawRoute"));
        setTextAttribute(span, "stocksage.routing.route", step.getAttributes().get("route"));
        setTextAttribute(span, "stocksage.routing.intent_summary", step.getAttributes().get("intentSummary"));
        setTextAttribute(span, "stocksage.routing.fine_intent", step.getAttributes().get("fineIntent"));
        setTextAttribute(span, "stocksage.routing.intent_group", step.getAttributes().get("intentGroup"));
        setTextAttribute(span, "stocksage.routing.time_sensitivity", step.getAttributes().get("timeSensitivity"));
        setTextAttribute(span, "stocksage.routing.analysis_depth", step.getAttributes().get("analysisDepth"));
        setTextAttribute(span, "stocksage.routing.rationale", step.getAttributes().get("rationale"));
        setTextAttribute(span, "stocksage.routing.source_scores", step.getAttributes().get("sourceScores"));
        setTextAttribute(span, "stocksage.routing.reason_codes", step.getAttributes().get("reasonCodes"));
        setTextAttribute(span, "stocksage.routing.outcome", step.getAttributes().get("outcome"));
        setTextAttribute(span, "stocksage.routing.fallback_reason", step.getAttributes().get("fallbackReason"));
        Object confidence = step.getAttributes().get("confidence");
        if (confidence instanceof Number number) {
            span.setAttribute("stocksage.routing.confidence", number.doubleValue());
        }
        Object ragHitCount = step.getAttributes().get("ragHitCount");
        if (ragHitCount instanceof Number number) {
            span.setAttribute("stocksage.routing.rag_hit_count", number.longValue());
        }
        Object needsClarification = step.getAttributes().get("needsClarification");
        if (needsClarification instanceof Boolean value) {
            span.setAttribute("stocksage.routing.needs_clarification", value);
        }
    }

    /**
     * 关闭根对话链路片段，并附加最终状态和用量属性。
     *
     * @param traceId 要结束的链路 ID
     * @param status success、error 或 cancelled 等最终状态
     * @param totalTokens 本轮总 token 数
     * @param durationMs 本轮总耗时
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
            String finalStatus = switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
                case "success" -> "success";
                case "error" -> "error";
                case "cancelled" -> "cancelled";
                default -> "unknown";
            };
            span.setAttribute("stocksage.status", finalStatus);
            span.setAttribute(DURATION_MS, durationMs);
            span.setAttribute(TOKEN_COUNT, (long) totalTokens);
            if (!"success".equals(finalStatus)) {
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
        } catch (Exception e) {
            log.warn("Failed to end Phoenix trace, traceId={}", traceId, e);
        }
    }

    /**
     * 记录发生在主对话流之外的文档搜索调用。
     *
     * @param query 检索词
     * @param resultCount 返回文档数
     * @param durationMs 检索耗时
     * @param output 受调用方控制的检索摘要
     */
    public void recordRetrieval(String query, int resultCount, long durationMs, String output) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Span span = tracer.spanBuilder("stocksage.docs_search")
                    .setAttribute(SPAN_KIND, "RETRIEVER")
                    .setAttribute("retrieval.documents.count", resultCount)
                    .setAttribute(DURATION_MS, durationMs)
                    .startSpan();
            setTextAttribute(span, INPUT_VALUE.getKey(), query);
            setTextAttribute(span, OUTPUT_VALUE.getKey(), output);
            span.end();
        } catch (Exception e) {
            log.warn("Failed to record Phoenix retrieval span", e);
        }
    }

    /**
     * 使用受控类型作名称，避免动态动作文本成为旁路正文出口。
     */
    private String spanName(AgentStep step) {
        return "stocksage." + spanKind(step).toLowerCase(Locale.ROOT);
    }

    /**
     * 将 StockSage 动作粗略映射到 OpenInference span kind。
     */
    private String spanKind(AgentStep step) {
        String action = step.getAction();
        if (action == null) {
            return "CHAIN";
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        if (normalized.contains("retrieval")) {
            return "RETRIEVER";
        }
        if (normalized.contains("tool") || normalized.contains("search") || normalized.contains("market")) {
            return "TOOL";
        }
        return "CHAIN";
    }

    /**
     * 只记录正文长度；原文导出需要独立、显式的配置授权。
     */
    private void setTextAttribute(Span span, String key, Object value) {
        if (value == null) return;
        String text = String.valueOf(value);
        span.setAttribute(key + ".length", (long) text.length());
        if (properties.isCaptureContent()) {
            span.setAttribute(key, text);
        }
    }
}
