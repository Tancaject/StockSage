package com.stocksage.agent;

import com.stocksage.research.ResearchDebateService;

import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.DebateModels.Side;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * DEEP 多空辩论中的空头研究员。
 *
 * <p>{@link ResearchDebateService} 在每轮把同一 {@link AnalysisState} 交给多空双方；本类只基于
 * 上游已收集的基本面、行情、新闻和历史辩论，从风险、估值压力和数据质量角度提出反方观点。
 * 它不绑定工具，不能在辩论阶段引入账本外的新事实。</p>
 */
@Service
public class BearResearcher {

    /** 仅用于空头论证的无工具 ChatClient。 */
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
        var request = chatClient.prompt().user(BullResearcher.renderPrompt(state, round, Side.BEAR));
        if (state.getModelInvocationContext() != null) {
            request.advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, state.getModelInvocationContext()));
        }
        return request.stream().content();
    }

}
