package com.stocksage.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 聚焦市场数据、估值字段、价格走势和技术指标的专业分析 Agent。
 *
 * <p>上游在运行前提供已解析股票身份；本类通过专用 ChatClient 使用行情和 IBKR 只读工具，
 * 产出供普通回答或 DEEP 辩论消费的市场报告。它不提供下单能力，也不决定最终投资评级。</p>
 */
@Service
public class MarketAgent {

    /** 在 AgentConfig 中绑定行情/只读 IBKR 工具的专用客户端。 */
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
