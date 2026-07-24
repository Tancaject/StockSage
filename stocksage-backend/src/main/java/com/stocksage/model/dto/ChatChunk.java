package com.stocksage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * SSE 推送给前端的单个数据块。
 *
 * 前端通过 type 字段区分当前智能体的阶段：
 * - "thought":     智能体的思考过程（"我需要获取茅台的K线..."）
 * - "action":      智能体正在调用工具（"调用 getStockKLine..."）
 * - "observation":  工具返回的结果摘要
 * - "model":       最终回答实际使用的模型信息
 * - "answer":      最终回答的文本片段（流式逐字输出）
 * - "error":       错误信息
 *
 * 序列化为 JSON 后通过 SSE 的 data: 字段发送。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatChunk {

    /** 数据类型：meta / thought / action / observation / model / answer / error */
    private String type;

    /** 文本内容 */
    private String content;

    /** 最终回答使用的模型能力层级：FAST / STANDARD / STRONG */
    private String modelTier;

    /** 最终回答实际调用的 DashScope 模型名 */
    private String modelName;

    /** 关联的 traceId，前端可据此跳转到链路追踪页面 */
    private String traceId;

    /** 会话 ID，前端用于后续消息关联和新建会话时获取 ID */
    private Long conversationId;

    /** 流式分组 id：section 相同的连续数据块会被前端聚合成同一个推理块（用于多空辩论逐 token 流式） */
    private String section;

    /** 流式分组的展示标题，例如“看多方 · 第 1 轮” */
    private String sectionLabel;

    /** Optional structured metadata for non-token events such as route decisions. */
    private Map<String, Object> metadata;
}
