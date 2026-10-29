package com.stocksage.config;

import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;
import java.util.Set;

/** 百炼上默认开启思考的模型（DeepSeek 默认思考；GLM-5.3、Kimi-K3 无法关闭思考）。 */
public final class ThinkingModels {

    private static final Set<String> THINKING_BY_DEFAULT =
            Set.of("deepseek-v4-pro-0813", "deepseek-v4-pro", "deepseek-flash", "glm-5.3", "kimi-k3");

    private ThinkingModels() {
    }

    public static boolean thinksByDefault(String modelName) {
        return modelName != null && THINKING_BY_DEFAULT.contains(modelName.trim());
    }

    /** 百炼的思考预算；DeepSeek 官方路由会移除此参数，改用其开关与深度选项。 */
    public static OpenAiChatOptions capThinking(OpenAiChatOptions options, int thinkingBudget) {
        if (thinksByDefault(options.getModel())) {
            options.setExtraBody(Map.of("thinking_budget", thinkingBudget));
        }
        return options;
    }
}
