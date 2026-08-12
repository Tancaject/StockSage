package com.stocksage.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.model.dto.ChatChunk;
import com.stocksage.service.KLinePayloadMapper;
import com.stocksage.trace.TraceEventStore;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AOP 拦截 @Tool 方法，在执行前后推送实时状态到 SSE 流。
 *
 * 前端效果：用户能看到"正在搜索: NVDA news"、"搜索到 5 条结果"等实时状态。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolCallAspect {

    /** 持久化参数和 observation 的最大字符数，防止大 JSON 撑大 Trace 行。 */
    private static final int TRACE_TEXT_LIMIT = 1200;

    /** 实时 action/observation 与 chart 分片写入入口。 */
    private final TraceEventStore traceEventStore;
    /** 序列化 ChatChunk、参数摘要和图表 payload。 */
    private final ObjectMapper objectMapper;
    /** 把每次工具调用追加为可审计的 AgentStep。 */
    private final TraceService traceService;
    /** 仅把 K 线类工具结果转换为前端图表协议。 */
    private final KLinePayloadMapper kLinePayloadMapper;

    /** 方法名到新人可读中文动作名的稳定映射。 */
    private static final Map<String, String> TOOL_LABELS = Map.ofEntries(
            Map.entry("webSearch", "搜索网页"),
            Map.entry("searchNews", "搜索新闻"),
            Map.entry("getStockKLine", "获取K线数据"),
            Map.entry("getFinancialMetrics", "获取财务指标"),
            Map.entry("getTechnicalIndicators", "获取技术指标"),
            Map.entry("getStockNews", "获取股票新闻"),
            Map.entry("getSectorPerformance", "获取板块行情"),
            Map.entry("compareStocks", "对比股票数据"),
            Map.entry("getMarketOverview", "获取大盘概览"),
            Map.entry("getIbkrConnectionGuide", "查看IBKR连接指南"),
            Map.entry("getIbkrAuthStatus", "检查IBKR连接"),
            Map.entry("tickleIbkrSession", "保持IBKR会话"),
            Map.entry("getIbkrPortfolioAccounts", "读取IBKR账户列表"),
            Map.entry("getIbkrAccountSummary", "读取账户摘要"),
            Map.entry("getIbkrPositions", "读取持仓"),
            Map.entry("getIbkrRealtimeQuote", "获取实时报价"),
            Map.entry("getIbkrHistoricalBars", "获取IBKR K线"),
            Map.entry("ingestCompanyFilings", "入库SEC财报"),
            Map.entry("getStructuredFinancials", "获取结构化财务数据")
    );

    /**
     * 拦截所有 Spring AI 工具调用并同步推送执行状态。
     *
     * <p>没有 traceId 的调用直接放行；有 traceId 时会在调用前推 action、调用后推 observation，
     * 并把结果写入 TraceService 供推理面板回放。</p>
     */
    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object interceptToolCall(ProceedingJoinPoint joinPoint) throws Throwable {
        if (ToolCallContext.isObservationSuppressed()) {
            // CapabilityGateway 已自行记录能力调用；此时直接执行，避免同一本地 @Tool 被追踪两次。
            return joinPoint.proceed();
        }
        String traceId = ToolCallContext.getTraceId();
        if (traceId == null) {
            return joinPoint.proceed();
        }

        Long conversationId = ToolCallContext.getConversationId();
        String methodName = joinPoint.getSignature().getName();
        String label = TOOL_LABELS.getOrDefault(methodName, methodName);
        String args = summarizeArgs(joinPoint.getArgs());

        // 推送 action 状态：正在调用...
        emitChunk(traceId, "action", label + (args.isEmpty() ? "" : ": " + args), conversationId);

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;

            // 推送 observation 状态：工具返回摘要
            String summary = summarizeResult(methodName, result, duration);
            emitChunk(traceId, "observation", summary, conversationId);
            // K 线结果额外映射为 chart 分片；普通工具不会进入该分支。
            emitChartChunk(traceId, methodName, joinPoint.getArgs(), result, conversationId);
            recordTraceStep(traceId, methodName, label, joinPoint.getArgs(), result, duration, null);

            return result;
        } catch (Throwable e) {
            long duration = System.currentTimeMillis() - start;
            String summary = "工具调用失败: " + truncate(e.getMessage(), 300) + " (" + duration + "ms)";
            emitChunk(traceId, "observation", summary, conversationId);
            recordTraceStep(traceId, methodName, label, joinPoint.getArgs(), null, duration, e);
            throw e;
        }
    }

    /**
     * 从工具参数中提取适合 SSE 展示的短摘要。
     *
     * <p>多数工具的第一个参数就是股票代码或搜索词，截取它可以让前端状态更易读。</p>
     */
    private String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) return "";
        // 取第一个参数作为主要标识（通常是股票代码或搜索关键词）
        String first = String.valueOf(args[0]);
        return first.length() > 80 ? first.substring(0, 80) + "..." : first;
    }

    /**
     * 生成工具结果的前端观察摘要。
     *
     * <p>搜索类工具会尝试估算结果数量；其他工具只返回获取成功和耗时，避免把大体积 JSON 推入 SSE 状态栏。</p>
     */
    private String summarizeResult(String methodName, Object result, long duration) {
        if (result == null) return "无结果 (" + duration + "ms)";
        String text = result.toString();
        int len = text.length();
        String sizeHint = len > 1000 ? " (" + (len / 1000) + "KB, " + duration + "ms)"
                                     : " (" + duration + "ms)";

        // 针对搜索类工具，尝试提取条数
        if (methodName.contains("search") || methodName.contains("Search") || methodName.contains("News")) {
            long count = text.chars().filter(c -> c == '{').count();
            if (count > 1) return "找到 " + (count - 1) + " 条结果" + sizeHint;
        }

        return "已获取数据" + sizeHint;
    }

    /**
     * 把工具调用记录为推理链路步骤。
     *
     * <p>记录内容会被截断，防止工具返回的大 JSON 撑爆追踪面板或数据库字段。</p>
     */
    private void recordTraceStep(String traceId,
                                 String methodName,
                                 String label,
                                 Object[] args,
                                 Object result,
                                 long duration,
                                 Throwable error) {
        try {
            traceService.addStep(traceId, AgentStep.builder()
                    .thought(error == null ? "Called tool: " + label : "Tool failed: " + label)
                    .action(methodName)
                    .actionInput(summarizeTraceArgs(methodName, args))
                    .observation(summarizeTraceObservation(methodName, result, duration, error))
                    .durationMs(duration)
                    .tokenCount(0)
                    .build());
        } catch (Exception traceError) {
            log.warn("Failed to record tool call trace step, traceId={}, tool={}", traceId, methodName, traceError);
        }
    }

    /**
     * 将工具入参序列化为追踪面板可读文本。
     */
    private String summarizeTraceArgs(String methodName, Object[] args) {
        return safeJson(args);
    }

    /**
     * 将工具结果或异常转换为追踪面板观察文本。
     */
    private String summarizeTraceObservation(String methodName, Object result, long duration, Throwable error) {
        if (error != null) {
            return "Error after " + duration + "ms: " + truncate(error.getMessage(), TRACE_TEXT_LIMIT);
        }
        if (result == null) {
            return "No result (" + duration + "ms)";
        }
        return summarizeResult(methodName, result, duration);
    }

    /**
     * 安全序列化对象，失败时退回到 {@code toString()}。
     */
    private String safeJson(Object value) {
        try {
            return truncate(objectMapper.writeValueAsString(value), TRACE_TEXT_LIMIT);
        } catch (Exception e) {
            return truncate(String.valueOf(value), TRACE_TEXT_LIMIT);
        }
    }

    /** 将 K 线工具结果转换为 chart 事件；转换失败不影响原始工具结果。 */
    private void emitChartChunk(String traceId, String methodName, Object[] args, Object result, Long conversationId) {
        if (!KLinePayloadMapper.isKlineTool(methodName) || result == null) {
            return;
        }
        try {
            kLinePayloadMapper.toChartPayload(methodName, result, args)
                    .ifPresent(payload -> emitChartPayload(traceId, payload, conversationId));
        } catch (Exception e) {
            log.debug("Failed to build chart chunk for tool={}", methodName, e);
        }
    }

    /** 序列化前端图表 payload 并复用普通事件出口。 */
    private void emitChartPayload(String traceId, Map<String, Object> payload, Long conversationId) {
        try {
            emitChunk(traceId, "chart", objectMapper.writeValueAsString(payload), conversationId);
        } catch (Exception e) {
            log.debug("Failed to serialize chart payload, traceId={}", traceId, e);
        }
    }

    /**
     * 截断过长文本。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /**
     * 向工具调用事件总线发送 SSE 分片。
     *
     * <p>发送失败只记录日志，不中断真实工具调用流程。</p>
     */
    private void emitChunk(String traceId, String type, String content, Long conversationId) {
        try {
            String json = objectMapper.writeValueAsString(ChatChunk.builder()
                    .type(type)
                    .content(content)
                    .traceId(traceId)
                    .conversationId(conversationId)
                    .build());
            traceEventStore.append(traceId, json);
        } catch (Exception e) {
            log.warn("Failed to emit tool call event", e);
        }
    }
}
