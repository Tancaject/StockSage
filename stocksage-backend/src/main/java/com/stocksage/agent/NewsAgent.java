package com.stocksage.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 聚焦实时新闻、政策、宏观事件和情绪面的分析师智能体。
 * 对时效性敏感的问题应由新闻工具或网页搜索观测支撑，而不是依赖模型的过期先验。
 */
@Service
public class NewsAgent {

    private final ChatClient chatClient;

    /**
     * 注入新闻分析师专用 ChatClient。
     *
     * <p>该客户端在配置层绑定了新闻搜索和网页搜索工具，用于获取最新事实。</p>
     */
    public NewsAgent(@Qualifier("newsAgentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 生成新闻与市场情绪分析报告。
     *
     * @param query 用户原始问题
     * @param context ChatService 提供的标的识别、RAG 引用或其他分析师上下文
     * @return 新闻分析师输出的结构化文本报告
     */
    public String analyze(String query, String context) {
        return chatClient.prompt()
                .user("""
                        用户问题：
                        %s

                        上下文：
                        %s

                        请输出新闻/情绪分析报告。
                        """.formatted(query, context == null ? "" : context))
                .call()
                .content();
    }
}
