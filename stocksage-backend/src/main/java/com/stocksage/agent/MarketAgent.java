package com.stocksage.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 聚焦市场数据、估值字段、价格走势和技术指标的分析师智能体。
 * ChatService 会在该智能体运行前提供已解析的股票身份，确保分析绑定到用户想查的标的。
 */
@Service
public class MarketAgent {

    private final ChatClient chatClient;

    /**
     * 注入市场分析师专用 ChatClient。
     *
     * <p>该客户端在 {@link com.stocksage.config.AgentConfig} 中绑定了行情和 IBKR 只读工具。</p>
     */
    public MarketAgent(@Qualifier("marketAgentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 生成市场/技术面分析报告。
     *
     * @param query 用户原始问题
     * @param context ChatService 预先整理的股票身份、RAG 引用或工具观察上下文
     * @return 市场分析师输出的结构化文本报告
     */
    public String analyze(String query, String context) {
        return chatClient.prompt()
                .user("""
                        用户问题：
                        %s

                        上下文：
                        %s

                        请输出市场/技术面分析报告。
                        """.formatted(query, context == null ? "" : context))
                .call()
                .content();
    }
}
