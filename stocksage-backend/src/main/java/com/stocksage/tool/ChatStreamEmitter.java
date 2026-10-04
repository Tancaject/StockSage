package com.stocksage.tool;

import com.stocksage.research.ResearchDebateService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.ChatChunk;
import com.stocksage.trace.TraceEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把 {@link ChatChunk} 序列化后推送到 {@link TraceEventStore} 的统一出口。
 *
 * <p>ChatService 的进度提示和 ResearchDebateService 的流式辩论都经由此组件发送，
 * 避免各处重复“构造 ChatChunk JSON 再 emit”的样板代码；同时让 agent 层只依赖 tool 层，
 * 不必反向依赖 service 层。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamEmitter {

    /** 把序列化后的分片写入可回放的 Trace 事件通道。 */
    private final TraceEventStore traceEventStore;
    /** 将 ChatChunk DTO 转为 SSE/Redis 共用的 JSON 格式。 */
    private final ObjectMapper objectMapper;

    /**
     * 推送一条普通进度块（不分组）。
     *
     * @param traceId 当前链路 ID；为空时静默跳过
     * @param conversationId 会话 ID
     * @param type 分片类型，如 action、observation
     * @param content 前端可展示的简短内容
     */
    public void emit(String traceId, Long conversationId, String type, String content) {
        send(traceId, ChatChunk.builder()
                .type(type)
                .content(content)
                .traceId(traceId)
                .conversationId(conversationId)
                .build());
    }

    /**
     * 推送一条带分组标识的流式块。
     *
     * <p>前端会把 {@code section} 相同的连续数据块聚合到同一个推理块，
     * 这样逐 token 流式才不会在界面上碎成成百上千条独立条目。</p>
     *
     * @param traceId 当前链路 ID
     * @param conversationId 会话 ID
     * @param type 分片类型
     * @param section 稳定分组键
     * @param sectionLabel 前端显示的分组标题
     * @param content 当前 token 或文本片段
     */
    public void emitSection(String traceId, Long conversationId, String type,
                            String section, String sectionLabel, String content) {
        send(traceId, ChatChunk.builder()
                .type(type)
                .content(content)
                .section(section)
                .sectionLabel(sectionLabel)
                .traceId(traceId)
                .conversationId(conversationId)
                .build());
    }

    /**
     * 序列化并推送；traceId 为空时静默跳过，使无追踪场景（如测试）可优雅降级为“只收集不推送”。
     */
    private void send(String traceId, ChatChunk chunk) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            traceEventStore.append(traceId, objectMapper.writeValueAsString(chunk));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize streaming ChatChunk", e);
        }
    }
}
