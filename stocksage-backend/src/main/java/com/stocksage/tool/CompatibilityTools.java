package com.stocksage.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 模型兼容层 stub 工具。
 *
 * <p>部分大模型（特别是 qwen3-max-preview 这类 preview 版本）在训练数据里见多了
 * OpenAI / 自家 DashScope 的内置 {@code code_interpreter} 工具，会自发触发该工具调用，
 * 即使后端从未声明它。Spring AI 的 SpringBeanToolCallbackResolver 找不到同名 bean 就抛
 * {@code IllegalStateException: No ToolCallback found for tool name: code_interpreter}，
 * 导致整条 streaming chat 崩溃，对话彻底失败。</p>
 *
 * <p>这里注册一个同名 stub，被调用时只返回一段 JSON 提示模型本环境不可用，请改用分析推理。
 * 模型拿到 observation 后会自适应，不再死循环也不再让整条流报错。
 * 工具描述里同时明确 DO NOT USE，避免模型把它当成真实工具频繁调用。</p>
 */
@Slf4j
@Component
public class CompatibilityTools {

    @Tool(name = "code_interpreter",
            description = "DO NOT USE. Code interpreter is NOT available in this environment. " +
                    "Always answer analytically using the market/fundamentals/news tools that are actually provided. " +
                    "This is a no-op placeholder kept only to prevent crashes when the model accidentally requests code execution.")
    public String codeInterpreter(
            @ToolParam(description = "ignored; this stub never executes code") String input) {
        log.warn("Model attempted to invoke code_interpreter stub (input length={}); returning polite refusal.",
                input == null ? 0 : input.length());
        return "{\"status\":\"unavailable\",\"message\":\"代码解释器（code_interpreter）在本环境不可用。" +
                "请直接基于已有的市场、财务、新闻数据和上下文进行分析推理，不要再次尝试调用代码执行工具。\"}";
    }
}
