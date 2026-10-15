package com.stocksage.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 按模型名分流对话请求：DeepSeek 官方模型名（及其别名）走 DeepSeek 官方 API，其余模型（路由用的 qwen3.8-flash、
 * 看图模型、百炼上托管的其他 deepseek 版本等）留在百炼。
 *
 * <p>上层（角色客户端、调用记录、令牌预算校验）统一使用百炼的参数写法 {@code enable_thinking}/{@code thinking_budget}；
 * 转发到官方 API 前在这里换成官方写法：{@code thinking: {type}} 和 {@code reasoning_effort}。官方没有思考 token 上限，
 * 而 max_tokens 包含思考内容，所以开思考的请求把输出上限提高到单独配置的值，避免思考挤掉正文。
 * 模型名按别名映射（如 deepseek-v4-pro-0813 → deepseek-v4-pro）。</p>
 */
public class ProviderRoutingChatModel implements ChatModel {

    /** DeepSeek 官方 API 的模型名（官方文档 create-chat-completion）。 */
    static final Set<String> OFFICIAL_MODELS = Set.of("deepseek-v4-pro", "deepseek-flash");

    private final ChatModel bailian;
    private final ChatModel deepseek;
    private final String reasoningEffort;
    private final int thinkingMaxTokens;
    private final Map<String, String> modelAliases;
    private final Set<String> routedModels;

    public ProviderRoutingChatModel(ChatModel bailian, ChatModel deepseek, String reasoningEffort,
                                    int thinkingMaxTokens, Map<String, String> modelAliases) {
        this.bailian = bailian;
        this.deepseek = deepseek;
        this.reasoningEffort = reasoningEffort;
        this.thinkingMaxTokens = thinkingMaxTokens;
        this.modelAliases = Map.copyOf(modelAliases);
        Set<String> routed = new HashSet<>(OFFICIAL_MODELS);
        routed.addAll(modelAliases.keySet());
        this.routedModels = Set.copyOf(routed);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return routed(prompt) ? deepseek.call(translate(prompt)) : bailian.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return routed(prompt) ? deepseek.stream(translate(prompt)) : bailian.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return bailian.getDefaultOptions();
    }

    /** 只有官方模型名和配置的别名分流；百炼上托管的其他 deepseek-* 版本不会被误发到官方 API。 */
    boolean routed(Prompt prompt) {
        String model = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
        return model != null && routedModels.contains(model);
    }

    Prompt translate(Prompt prompt) {
        OpenAiChatOptions options = prompt.getOptions() instanceof OpenAiChatOptions openAi
                ? openAi.copy()
                : ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class, OpenAiChatOptions.class);
        options.setModel(modelAliases.getOrDefault(options.getModel(), options.getModel()));
        Map<String, Object> extra = new HashMap<>(options.getExtraBody() == null ? Map.of() : options.getExtraBody());
        // DeepSeek 默认开思考；只有明确 enable_thinking=false（如取证循环）才关闭。
        boolean thinking = !Boolean.FALSE.equals(extra.remove("enable_thinking"));
        extra.remove("thinking_budget");
        extra.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        options.setExtraBody(extra);
        if (thinking) {
            if (options.getReasoningEffort() == null) options.setReasoningEffort(reasoningEffort);
            // 官方没有思考上限，max_tokens 同时包含思考和正文；只往上调，不降低调用方已给的上限。
            if (options.getMaxTokens() != null && options.getMaxTokens() < thinkingMaxTokens) {
                options.setMaxTokens(thinkingMaxTokens);
            }
        }
        return new Prompt(prompt.getInstructions(), options);
    }
}
