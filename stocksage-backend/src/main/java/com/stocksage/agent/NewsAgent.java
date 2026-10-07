package com.stocksage.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 聚焦实时新闻、政策、宏观事件和情绪面的专业分析 Agent。
 *
 * <p>上游提供标的与新闻/网页搜索证据；本类通过无工具 ChatClient 产出供普通回答
 * 或 DEEP 辩论消费的新闻报告。时效性事实必须来自本轮观察，不依赖模型的过期先验。</p>
 */
@Service
public class NewsAgent {

    /** 在 AgentConfig 中绑定新闻角色提示词、但不绑定工具的专用客户端。 */
    private final ChatClient chatClient;

    /**
     * 注入新闻分析师专用 ChatClient。
     *
     * <p>该客户端只分析上游已经取得的新闻搜索和网页搜索证据。</p>
     */
    public NewsAgent(@Qualifier("newsAgentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 生成新闻与市场情绪分析报告。
     *
     * @param query 用户原始问题
     * @param context 预取层提供的标的识别和新闻工具观察上下文
     * @return 新闻分析师输出的结构化文本报告
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
                        1. 先确认标的、市场、检索主题和用户所说的时间范围；标的不唯一时不要猜测。
                        2. 对时效性问题只使用上游提供的搜索证据，区分事件发生时间与发布时间；未提供的时间明确标注，只有摘要时保留原文未核验的限制。
                        3. 直接回答用户关心的事件；影响和反向解释仅在证据支持时展开，尚不能确认时说明缺口，不要把同期新闻直接写成价格变化的确定原因。
                        4. 按系统规定的固定章节输出，明确区分已核验事实、分析推断、市场情绪和仍待确认的信息。
                        """.formatted(query, context == null ? "" : context))
                .call()
                .chatResponse());
    }
}
