package com.stocksage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * SSE 推送给前端的单个数据块。
 *
 * <p>前端通过 type 区分 meta、thought、route_decision、action、observation、model、
 * answer、heartbeat 和 error 等事件；同一 DTO 既用于当前 SSE，也会写入可回放的 Trace 事件流。</p>
 *
 * <p>answer 可以逐 token 推送，section 相同的连续块会在前端合并显示。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatChunk {

    /** 事件类型，决定前端把该块显示为进度、工具结果、回答、心跳或错误。 */
    private String type;

    /** 当前事件的文本载荷；纯结构化事件可为空。 */
    private String content;

    /** 最终回答使用的模型能力层级：FAST、STANDARD 或 STRONG；普通进度事件为空。 */
    private String modelTier;

    /** 最终回答实际调用的模型名；非 model 事件或规则降级结果可为空。 */
    private String modelName;

    /** 关联的 traceId，前端据此回放事件或跳转链路详情。 */
    private String traceId;

    /** 会话主键；新会话的首个 meta 事件会把后端创建的 ID 返回给前端。 */
    private Long conversationId;

    /** 可选流式分组 ID；相同 section 的连续块会聚合成同一个推理卡片。 */
    private String section;

    /** 流式分组的展示标题，例如“看多方 · 第 1 轮”；未分组事件为空。 */
    private String sectionLabel;

    /** 非文本事件的可选结构化元数据，例如路由决策和后台任务标识。 */
    private Map<String, Object> metadata;
}
