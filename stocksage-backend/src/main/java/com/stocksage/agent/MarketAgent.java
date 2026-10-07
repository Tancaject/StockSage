package com.stocksage.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 聚焦市场数据、估值字段、价格走势和技术指标的专业分析 Agent。
 *
 * <p>上游在运行前提供已解析股票身份以及行情和 IBKR 只读证据；本类通过无工具 ChatClient
 * 产出供普通回答或 DEEP 辩论消费的市场报告。它不负责取数、下单或最终投资评级。</p>
 */
@Service
public class MarketAgent {

    /** 在 AgentConfig 中绑定市场角色提示词、但不绑定工具的专用客户端。 */
    private final ChatClient chatClient;

    /**
     * 注入市场分析师专用 ChatClient。
     *
     * <p>该客户端只分析上游已经取得的行情和 IBKR 只读证据。</p>
     */
    public MarketAgent(@Qualifier("marketAgentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 生成市场/技术面分析报告。
     *
     * @param query 用户原始问题
     * @param context 预取层整理的股票身份和市场工具观察上下文
     * @return 市场分析师输出的结构化文本报告
     */
    public String analyze(String query, String context) {
        return analyzeObserved(query, context).content();
    }

    public ModelCompletion.Output analyzeObserved(String query, String context) {
        return ModelCompletion.output(chatClient.prompt()
                .user("""
                        用户问题：
                        %s

                        上下文：
                        %s

                        本轮任务：
                        1. 先确认标的、市场、用户要求的时间范围和数据粒度；标的不唯一时不要猜测。
                        2. 仅用上游提供的本轮证据回答行情、K 线、指标、估值或账户问题，并保留 as-of 时间、时区、币种和数据质量状态。
                        3. 重点回答用户问题，不默认扩展到用户未要求的账户数据，也不把技术指标写成确定预测。
                        4. 按系统规定的固定章节输出，明确标注事实、分析观察和数据缺口，供后续研究角色复核。
                        """.formatted(query, context == null ? "" : context))
                .call()
                .chatResponse());
    }
}
