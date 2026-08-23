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

                        本轮任务：
                        1. 先确认标的、市场、用户要求的时间范围和数据粒度；标的不唯一时不要猜测。
                        2. 仅用本轮工具结果回答行情、K 线、指标、估值或账户问题，并保留 as-of 时间、时区、币种和数据质量状态。
                        3. 重点回答用户问题，不默认扩展到用户未要求的账户数据，也不把技术指标写成确定预测。
                        4. 按系统规定的固定章节输出，明确标注事实、分析观察和数据缺口，供后续研究角色复核。
                        """.formatted(query, context == null ? "" : context))
                .call()
                .content();
    }
}
