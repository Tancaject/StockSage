package com.stocksage.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 智能体推理的一个步骤。
 *
 * 规划-执行流程中的每一步产生一个 AgentStep，包含：
 *   - thought:     LLM 的思考过程（"我需要获取茅台的K线数据"）
 *   - action:      决定调用的工具名（"getStockKLine"），为 null 表示最终回答
 *   - actionInput: 工具调用参数的 JSON（{"code":"sh.600519","period":"daily","days":30}）
 *   - observation:  工具返回的结果
 *
 * 多个 AgentStep 组成一条完整链路，存储在 agent_traces.steps 字段中（JSON 数组）。
 * 前端链路视图读取这个数组，渲染成时间线界面。
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

    /** Optional structured metadata. Values must be safe for persistence and observability. */
    private Map<String, Object> attributes;
}
