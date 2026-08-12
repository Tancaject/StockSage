package com.stocksage.capability;

/**
 * Gateway 返回给 Skill 的统一能力结果。
 *
 * <p>无论下游是本地 {@code @Tool} 还是 MCP，Skill 都只读取此契约；原始内容已按能力清单限长。</p>
 *
 * @param capabilityId 稳定能力 ID
 * @param providerId 实际提供结果的提供方
 * @param status 成功或被限长
 * @param content 可注入后续 Agent 上下文的结果文本
 * @param resultBytes 限长后内容的 UTF-8 字节数
 * @param durationMs 提供方调用耗时
 */
public record CapabilityResult(
        String capabilityId,
        String providerId,
        Status status,
        String content,
        int resultBytes,
        long durationMs
) {

    /** 当前统一结果只区分完整成功与成功但已截断。 */
    public enum Status {
        SUCCESS,
        TRUNCATED
    }
}
