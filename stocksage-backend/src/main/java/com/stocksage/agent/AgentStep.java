package com.stocksage.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 一条可持久化的智能体执行步骤。
 *
 * 规划-执行流程中的每一步产生一个 AgentStep，包含：
 *   - thought:     LLM 的思考过程（"我需要获取茅台的K线数据"）
 *   - action:      决定调用的工具名（"getStockKLine"），为 null 表示最终回答
 *   - actionInput: 工具调用参数的 JSON（{"code":"sh.600519","period":"daily","days":30}）
 *   - observation:  工具返回的结果
 *
 * <p>{@link com.stocksage.trace.TraceService} 为步骤编号并存入 {@code agent_traces.steps} JSON 数组，
 * Phoenix 可选地镜像同一信息，前端再渲染为执行时间线。该 DTO 只保存有界摘要；
 * 调用方不应把完整 prompt、敏感参数或超大工具结果放入其中。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStep {

    /** 步骤序号，从 0 开始 */
    private int index;

    /** LLM 的推理过程 */
    private String thought;

    /** 要调用的工具名，null 表示这是最终回答步骤 */
    private String action;

    /** 工具调用参数（JSON 字符串） */
    private String actionInput;

    /** 工具返回的原始结果 */
    private String observation;

    /** 本步骤耗时（毫秒） */
    private long durationMs;

    /** 本步骤消耗的令牌数（提示词 + 补全文本） */
    private int tokenCount;

    /** 可选结构化元数据；值必须适合持久化与可观测展示，不得包含敏感正文。 */
    private Map<String, Object> attributes;
}
