package com.stocksage.agent;

import com.stocksage.model.dto.AnalysisState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 多空辩论中的空头参与者，负责从风险、估值压力、竞争、周期性和数据质量等角度挑战投资逻辑。
 */
@Service
public class BearResearcher {

    /** Round 1 是开局陈述，不强制反驳；Round 2+ 必须先逐条回应对方再展开新论据。 */
    private static final int OPENING_ROUND = 1;

    private final ChatClient chatClient;

    /**
     * 注入空头研究员专用 ChatClient。
     *
     * <p>该客户端不绑定工具，只对当前研究状态中的证据进行反方推演，保证风险论证可追溯。</p>
     */
    public BearResearcher(@Qualifier("bearResearcherChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 基于当前分析状态流式生成一轮空头论点。
     *
     * <p>Round 1 走开局陈述模板，Round 2+ 走带强制反驳指令的模板，并把
     * {@link AnalysisState#getDebateTurns() 完整辩论历史} 注入上下文，避免空头回避对方锐点。</p>
     *
     * @param state 当前研究流水线状态，包含分析师报告和此前所有辩论发言
     * @param round 辩论轮次，用于提示模型区分多轮论证
     * @return 本轮看空论据和风险提示的流式 token
     */
    public Flux<String> argue(AnalysisState state, int round) {
        String prompt = round <= OPENING_ROUND
                ? renderOpeningPrompt(state)
                : renderRebuttalPrompt(state, round);
        return chatClient.prompt().user(prompt).stream().content();
    }

    /**
     * 开局陈述模板：第 1 轮空头先摆出最强的风险与估值压力基线。
     */
    private String renderOpeningPrompt(AnalysisState state) {
        return """
                用户问题：%s
                辩论轮次：1（开局陈述）

                Fundamentals:
                %s

                Market:
                %s

                News:
                %s

                这是辩论的开局陈述，对方还没有发言。请基于上面证据，给出最强的看空基线：
                - 列出 3-5 条最具说服力的看空论据或风险提示，每条都标注引用的具体证据（财务数据、技术指标或新闻事件）。
                - 同时主动指出本方论证中最大的 1-2 个潜在弱点，留给多头反驳——表现公正性。
                """.formatted(
                state.getQuery(),
                truncate(safe(state.getFundamentalsReport()), 2500),
                truncate(safe(state.getMarketReport()), 2500),
                truncate(safe(state.getNewsReport()), 2500)
        );
    }

    /**
     * 反驳模板：Round 2+ 强制模型先逐条回应对方最强观点再展开新论据，杜绝平行独白。
     */
    private String renderRebuttalPrompt(AnalysisState state, int round) {
        return """
                用户问题：%s
                辩论轮次：%d

                Fundamentals:
                %s

                Market:
                %s

                News:
                %s

                === 完整辩论历史（按时间顺序）===
                %s

                === 本轮硬性要求（请严格遵守）===
                1. 先用 Markdown 引用块（> ...）逐条引用对方至今为止最强的 2-3 个观点。
                2. 对每条引用，明确标注 ✅承认 / ⚠️部分承认 / ❌反驳。
                3. 标注 ❌反驳 的，必须给出可核验的反例数据（财务数据、技术指标、新闻事件其一），否则降级为 ⚠️部分承认。
                4. 完成第 1-3 步之后，才能展开本轮的新风险论据；新论据同样需要标注具体证据来源。
                5. 不允许跳过反驳直接抛新论据，也不允许复读上一轮自己的论点。
                """.formatted(
                state.getQuery(),
                round,
                truncate(safe(state.getFundamentalsReport()), 2500),
                truncate(safe(state.getMarketReport()), 2500),
                truncate(safe(state.getNewsReport()), 2500),
                BullResearcher.renderDebateHistory(state.getDebateTurns(), 6000)
        );
    }

    /**
     * 将空值转换为空字符串，避免提示词中出现 {@code null}。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 截断过长上下文，控制单轮辩论提示词长度。
     *
     * <p>空头研究员需要完整证据方向，但不需要把所有原始报告无限制塞入同一轮 prompt。</p>
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...[truncated]";
    }
}
