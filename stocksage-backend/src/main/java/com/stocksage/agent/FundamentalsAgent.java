package com.stocksage.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 聚焦财务报表、SEC 文件和经营质量的专业分析 Agent。
 *
 * <p>上游预取提供已解析标的与基本面工具证据；本类通过无工具 ChatClient
 * 产出文本报告，DEEP 时再由 Bull/Bear 与 ResearchManager 消费。它不负责路由、取数或评级。</p>
 */
@Service
public class FundamentalsAgent {

    /** 在 AgentConfig 中绑定基本面角色提示词、但不绑定工具的专用客户端。 */
    private final ChatClient chatClient;

    /**
     * 注入基本面分析师专用 ChatClient。
     *
     * <p>该客户端只分析上游已经取得的财报、公告和结构化财务证据。</p>
     */
    public FundamentalsAgent(@Qualifier("fundamentalsAgentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 生成基本面/财报分析报告。
     *
     * @param query 用户原始研究问题
     * @param context 预取层准备好的股票身份和基本面工具证据
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

                本轮任务：
                1. 先从问题与上下文确认分析标的、市场、财务期间和用户真正关心的基本面维度；标的不唯一时不要猜测。
                2. 只使用上游提供的本轮证据，并核对报告期、单位、币种与数据来源；缺少证据时直接说明。
                3. 重点回答用户问题，不为凑完整报告而扩写无关指标；同时给出最重要的反向证据和数据缺口。
                4. 按系统规定的固定章节输出，确保后续 Bull/Bear 与 Research Manager 能区分事实、推断和未知项。
                """.formatted(query, context == null ? "" : context);
    }
}
