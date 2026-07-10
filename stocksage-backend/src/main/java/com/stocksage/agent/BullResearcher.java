package com.stocksage.agent;

import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.AnalysisState.DebateTurn;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 多空辩论中的多头参与者，负责提出最有证据支撑的正面观点。
 * 它不直接调用工具，只基于已经收集到 AnalysisState 中的分析师报告展开论证。
 */
@Service
public class BullResearcher {

    /** Round 1 是开局陈述，不强制反驳；Round 2+ 必须先逐条回应对方再展开新论据。 */
    private static final int OPENING_ROUND = 1;

    private final ChatClient chatClient;

    /**
     * 注入多头研究员专用 ChatClient。
     *
     * <p>该客户端不绑定工具，只围绕已收集的分析师报告进行论证，避免辩论阶段引入未经记录的新事实。</p>
     */
    public BullResearcher(@Qualifier("bullResearcherChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 基于当前分析状态流式生成一轮多头论点。
     *
     * <p>Round 1 走开局陈述模板，Round 2+ 走带强制反驳指令的模板，并把
     * {@link AnalysisState#getDebateTurns() 完整辩论历史} 注入上下文，从根上避免"自说自话"。</p>
     *
     * @param state 当前研究流水线状态，包含三类分析师报告和此前所有辩论发言
     * @param round 辩论轮次，用于提示模型区分多轮论证
     * @return 本轮看多论据的流式 token
     */
    public Flux<String> argue(AnalysisState state, int round) {
        String prompt = round <= OPENING_ROUND
                ? renderOpeningPrompt(state)
                : renderRebuttalPrompt(state, round);
        return chatClient.prompt().user(prompt).stream().content();
    }

    /**
     * 开局陈述模板：第 1 轮还没有对方观点可反驳，要求模型摆出最强的看多基线。
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

                这是辩论的开局陈述，对方还没有发言。请基于上面证据，给出最强的看多基线：
                - 列出 3-5 条最具说服力的看多论据，每条都标注引用的具体证据（财务数据、技术指标或新闻事件）。
                - 同时主动指出本方论证中最大的 1-2 个潜在弱点，留给空头反驳——表现公正性。
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
                4. 完成第 1-3 步之后，才能展开本轮的新论据；新论据同样需要标注具体证据来源。
                5. 不允许跳过反驳直接抛新论据，也不允许复读上一轮自己的论点。
                """.formatted(
                state.getQuery(),
                round,
                truncate(safe(state.getFundamentalsReport()), 2500),
                truncate(safe(state.getMarketReport()), 2500),
                truncate(safe(state.getNewsReport()), 2500),
                renderDebateHistory(state.getDebateTurns(), 6000)
        );
    }

    /**
     * 把辩论流水拼成可读的"轮次 + 立场 + 原文"块。
     *
     * <p>整体长度受 {@code maxLength} 限制，超出时丢弃最早的发言并提示已截断，
     * 优先保留最新交锋——对当前轮次反驳价值最大。</p>
     */
    static String renderDebateHistory(List<DebateTurn> turns, int maxLength) {
        if (turns == null || turns.isEmpty()) {
            return "（暂无）";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = turns.size() - 1; i >= 0; i--) {
            DebateTurn turn = turns.get(i);
            String side = turn.side() == DebateTurn.Side.BULL ? "Bull" : "Bear";
            String entry = "【Round " + turn.round() + " " + side + "】\n" + safe(turn.fullText()) + "\n\n";
            if (builder.length() + entry.length() > maxLength) {
                builder.insert(0, "...[更早的发言已省略]\n\n");
                break;
            }
            builder.insert(0, entry);
        }
        return builder.toString().trim();
    }

    /**
     * 将空值转换为空字符串，避免提示词中出现 {@code null}。
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 截断过长上下文，控制单轮辩论提示词长度。
     *
     * <p>辩论阶段只需要上游报告的核心证据，保留过长原文会挤占模型输出预算。</p>
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...[truncated]";
    }
}
