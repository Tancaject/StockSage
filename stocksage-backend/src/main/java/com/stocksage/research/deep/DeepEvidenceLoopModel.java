package com.stocksage.research.deep;

import com.stocksage.agent.runtime.ModelInvocationAdvisor;
import com.stocksage.agent.runtime.ModelInvocationContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 取证循环的模型适配器：带工具定义调用 STANDARD 模型，只取回模型提出的工具调用。
 *
 * <p>关闭 Spring AI 的自动工具执行；工具回调只提供定义，被调用时直接失败。真正的执行由
 * 研究编排在校验、授权之后经 CapabilityGateway 完成。循环关闭思考模式：思考模式下的工具调用
 * 需要逐轮回传 reasoning_content，且 tool_choice 受限。</p>
 */
@Service
public class DeepEvidenceLoopModel {

    private final ChatClient chatClient;
    private final String model;
    private final int maxOutputTokens;

    public DeepEvidenceLoopModel(@Qualifier("deepEvidenceLoopChatClient") ChatClient chatClient,
                                 @Value("${stocksage.chat.model-routing.standard-model:${STOCKSAGE_CHAT_MODEL:deepseek-flash}}") String model,
                                 @Value("${stocksage.agent.deep-react.max-output-tokens:1024}") int maxOutputTokens) {
        this.chatClient = chatClient;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    /** 发起一轮决策；返回的 AssistantMessage 可能包含工具调用，也可能只有文本。 */
    public AssistantMessage next(List<Message> conversation,
                                 List<DeepEvidenceToolMenu.ToolDefinition> tools,
                                 ModelInvocationContext invocationContext) {
        List<ToolCallback> callbacks = tools.stream().<ToolCallback>map(DefinitionOnly::new).toList();
        // 完整给出本次请求的选项，不依赖与客户端默认选项的合并规则。
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.0)
                .maxTokens(maxOutputTokens)
                .extraBody(Map.of("enable_thinking", false))
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .parallelToolCalls(true)
                .build();
        var request = chatClient.prompt().messages(conversation).options(options);
        if (invocationContext != null) {
            request.advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, invocationContext));
        }
        var response = request.call().chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new AssistantMessage("");
        }
        return response.getResult().getOutput();
    }

    /** 只携带工具定义的回调；任何自动执行都视为编程错误。 */
    private record DefinitionOnly(DeepEvidenceToolMenu.ToolDefinition definition) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return new DefaultToolDefinition(definition.name(), definition.description(), definition.parameters());
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("DEEP evidence tools are executed by the research orchestrator only");
        }
    }
}
