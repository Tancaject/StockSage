package com.stocksage.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 聚焦财务报表、SEC 文件和经营质量的专业分析 Agent。
 *
 * <p>上游预取/ChatService 提供已解析标的和 RAG 上下文；本类通过专用 ChatClient 使用最小基本面
 * 工具集合并产出文本报告，DEEP 时再由 Bull/Bear 与 ResearchManager 消费。它不负责路由或评级。</p>
 */
@Service
public class FundamentalsAgent {

    /** 在 AgentConfig 中绑定基本面工具和角色提示词的专用客户端。 */
    private final ChatClient chatClient;

    /**
     * 注入基本面分析师专用 ChatClient。
     *
     * <p>该客户端在配置层绑定了财报、公告和结构化财务数据工具。</p>
     */
    public FundamentalsAgent(@Qualifier("fundamentalsAgentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 生成基本面/财报分析报告。
     *
     * @param query 用户原始研究问题
     * @param context ChatService 准备好的股票身份、RAG 引用和其他上下文
     * @return 基本面分析师输出的结构化文本报告
     */
    public String analyze(String query, String context) {
        return chatClient.prompt()
                .user(buildPrompt(query, context))
                .call()
                .content();
    }

    /**
     * 拼装基本面分析师提示词。
     *
     * <p>空上下文会被转换为空字符串，避免向模型传入 {@code null} 影响提示词可读性。</p>
     */
    private String buildPrompt(String query, String context) {
        return """
                用户问题：
                %s

                上下文：
                %s

                请输出基本面/财报分析报告。
                """.formatted(query, context == null ? "" : context);
    }
}
