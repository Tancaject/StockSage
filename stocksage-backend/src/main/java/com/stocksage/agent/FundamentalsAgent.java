package com.stocksage.agent;

import com.stocksage.evolution.AgentPolicyBundle;
import com.stocksage.evolution.FundamentalsMethodRegistry;
import com.stocksage.evolution.FundamentalsRuntimeIdentity;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聚焦财务报表、SEC 文件和经营质量的专业分析 Agent。
 *
 * <p>上游预取提供已解析标的与基本面工具证据；本类通过无工具 ChatClient
 * 产出普通 FUNDAMENTALS 路线的文本报告。它不负责路由、取数或评级。</p>
 */
@Service
public class FundamentalsAgent {

    /** 在 AgentConfig 中绑定基本面角色提示词、但不绑定工具的专用客户端。 */
    private final ChatClient chatClient;
    private final FundamentalsMethodRegistry methods;
    private final AgentRuntimeConfiguration runtime;

    /**
     * 注入基本面分析师专用 ChatClient。
     *
     * <p>该客户端只分析上游已经取得的财报、公告和结构化财务证据。</p>
     */
    public FundamentalsAgent(@Qualifier("fundamentalsAgentChatClient") ChatClient chatClient,
                             FundamentalsMethodRegistry methods, AgentRuntimeConfiguration runtime) {
        this.chatClient = chatClient;
        this.methods = methods;
        this.runtime = runtime;
    }

    /**
     * 生成基本面/财报分析报告。
     *
     * @param query 用户原始研究问题
     * @param context 预取层准备好的股票身份和基本面工具证据
     * @return 基本面分析师输出的结构化文本报告
     */
    public String analyze(String query, String context) {
        return analyze(query, context, pinMethod());
    }

    public AgentPolicyBundle pinMethod() {
        return methods.active();
    }

    public FundamentalsMethodRegistry.Selection selectMethod(String userId, java.util.Set<String> taskTags,
            java.util.Set<String> evidenceTags, FundamentalsRuntimeIdentity.Request request, long timeoutSeconds) {
        Map<String, String> conditions = null;
        if (methods.hasApproved() && request != null && request.eligibleRouteAndModel() && !request.finalInvocation().isEmpty()) {
            conditions = FundamentalsRuntimeIdentity.conditions(FundamentalsRuntimeIdentity.modelConfiguration(
                    runtime, java.util.List.of(request.finalInvocation()), timeoutSeconds, request.promptMaxChars()));
        }
        return methods.select(userId, taskTags, evidenceTags,
                java.util.Set.of("evidence-reading", "period-comparison", "unit-comparison", "arithmetic"), conditions);
    }

    /** 调用方保留此包用于本次执行与归属记录，撤回不得改变进行中的方法。 */
    public String analyze(String query, String context, AgentPolicyBundle pinned) {
        return invoke(query, context, pinned).content();
    }

    /** 回放只选择启动时已登记的方法；不会建立研究任务或持久化调用记录。 */
    public Analysis analyzeObserved(String query, String context, String bundleId) {
        return invoke(query, context, methods.require(bundleId));
    }

    /** Ordinary calls retain their approved selection; the profile-gated evaluator may supply a registered experiment. */
    public Analysis analyzeObserved(String query, String context, AgentPolicyBundle bundle) {
        return invoke(query, context, bundle);
    }

    private Analysis invoke(String query, String context, AgentPolicyBundle bundle) {
        String system = FundamentalsPrompts.system(bundle.method());
        String user = FundamentalsPrompts.task(query, context);
        var response = chatClient.prompt().system(system).user(user).call().chatResponse();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("usageSource", "NO_DATA");
        facts.put("usageSemantics", "INVOCATION_SNAPSHOT");
        if (response != null) {
            var metadata = response.getMetadata();
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                facts.put("actualModel", metadata.getModel());
            }
            var usage = metadata.getUsage();
            if (usage != null && !(usage instanceof EmptyUsage)) {
                if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage) {
                    facts.put("usageSource", "PROVIDER");
                    if (nativeUsage.promptTokens() != null) facts.put("inputTokens", nativeUsage.promptTokens());
                    if (nativeUsage.completionTokens() != null) facts.put("outputTokens", nativeUsage.completionTokens());
                    if (nativeUsage.totalTokens() != null) facts.put("totalTokens", nativeUsage.totalTokens());
                } else {
                    facts.put("usageSource", "SDK_NORMALIZED");
                }
            }
        }
        ModelCompletion.Output output = ModelCompletion.output(response);
        facts.put("finishReason", output.completion().finishReason());
        facts.put("completionStatus", output.completion().status().name());
        return new Analysis(output.content(), system, user, bundle.identity(), Map.copyOf(facts), output.completion());
    }

    public record Analysis(String content, String systemPrompt, String userPrompt,
                           Map<String, String> methodBundle, Map<String, Object> responseMetadata,
                           ModelCompletion completion) {}
}
