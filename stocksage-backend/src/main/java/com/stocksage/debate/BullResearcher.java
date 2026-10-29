package com.stocksage.debate;

import static com.stocksage.util.PromptText.untrustedContext;


import com.stocksage.agent.runtime.ModelInvocationAdvisor;
import com.stocksage.debate.DebateModels.Side;
import com.stocksage.research.deep.AnalysisState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;


/**
 * DEEP 多空辩论中的多头研究员。
 *
 * <p>{@link ResearchDebateService} 在每轮把同一 {@link AnalysisState} 交给多空双方；本类只基于
 * 已收集的分析师报告和历史辩论提出正面投资逻辑。它不直接调用工具，避免在证据快照固定后
 * 引入无法进入 Evidence Ledger 的新事实。</p>
 */
@Service
public class BullResearcher {

    /** Round 1 是开局陈述，不强制反驳；Round 2+ 必须先逐条回应对方再展开新论据。 */
    private static final int OPENING_ROUND = 1;

    /** 仅用于多头论证的无工具 ChatClient。 */
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
     * {@link AnalysisState#getDebateTurns() 全部论点索引与近期辩论细节} 注入上下文，避免"自说自话"。</p>
     *
     * @param state 当前研究流水线状态，包含三类分析师报告和此前所有辩论发言
     * @param round 辩论轮次，用于提示模型区分多轮论证
     * @return 本轮看多论据的流式 token
     */
    public Flux<String> argue(AnalysisState state, int round) {
        return argue(state, round, null);
    }

    /**
     * @param repairNote 上一次输出未通过契约校验时的问题说明；首次生成传 null
     */
    public Flux<String> argue(AnalysisState state, int round, String repairNote) {
        var request = chatClient.prompt().user(renderPrompt(state, round, Side.BULL, repairNote));
        if (state.getModelInvocationContext() != null) {
            request.advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, state.getModelInvocationContext()));
        }
        return request.stream().content();
    }

    /**
     * 按发言方渲染本轮提示词。多空双方模板只在角色、关注点、示例结论和对方标签上不同，共用同一结构。
     *
     * <p>Round 1 是开局陈述：还没有对方观点可反驳，要求模型摆出本方最强基线。
     * Round 2+ 是反驳模板：强制模型先逐条回应对方最强观点再展开新论据，杜绝平行独白。</p>
     */
    static String renderPrompt(AnalysisState state, int round, Side side) {
        return renderPrompt(state, round, side, null);
    }

    static String renderPrompt(AnalysisState state, int round, Side side, String repairNote) {
        String prompt = renderBasePrompt(state, round, side);
        if (repairNote == null || repairNote.isBlank()) {
            return prompt;
        }
        // 修正说明放在末尾，前缀与首次请求逐字节相同，供应商缓存仍能命中。
        return prompt + """

                === 上一次输出未通过契约校验 ===
                %s
                请按上面全部要求重新输出完整的两段内容；JSON 必须满足所有硬性要求。
                """.formatted(untrustedContext(repairNote.strip()));
    }

    private static String renderBasePrompt(AnalysisState state, int round, Side side) {
        boolean bull = side == Side.BULL;
        String role = bull
                ? ResearchPrompts.researcherRole("Bull Researcher", "构建看多论据，强调增长、护城河、估值上修和催化剂。")
                : ResearchPrompts.researcherRole("Bear Researcher", "构建看空论据，强调估值压力、竞争、周期、财务和执行风险。");
        if (round <= OPENING_ROUND) {
            return ResearchPrompts.evidenceBlock(state) + """
                    %s
                    辩论轮次：1（开局陈述）

                    请按以下两段顺序输出：
                    第一段用简体中文简要概括本轮立场，不得使用大括号。
                    第二段输出严格 JSON 代码块，除此之外不要再输出文字：
                    ```json
                    {
                      "points": [
                        {
                          "type": "THESIS",
                          "claim": "%s",
                          "horizon": "SHORT_TERM 或 MEDIUM_TERM 或 LONG_TERM 或 UNSPECIFIED",
                          "evidenceRefs": [
                            {"evidenceId": "快照中的真实 evidenceId", "excerpt": "该 evidenceId 内容中的连续原文摘录"}
                          ],
                          "reasoning": "证据如何支持结论",
                          "assumption": "结论成立所需假设",
                          "invalidationCondition": "使结论失效的可观察条件",
                          "respondsToPointIds": []
                        }
                      ]
                    }
                    ```

                    硬性要求：
                    - points 必须有 3-5 条，type 全部为 THESIS；不要自行生成 pointId，后端会分配。
                    - 每条至少绑定一个可用 evidenceId，excerpt 必须逐字来自该 ID 对应的 content，且至少 12 个字符；不得跨证据拼接或补写数字。
                    - assumption 和 invalidationCondition 不得为空；证据不足的观点不要输出。
                    """.formatted(
                    ResearchPrompts.roleSection(role),
                    bull ? "可被证伪的看多结论" : "可被证伪的看空结论或风险");
        }
        String opponentLabel = bull ? "空头" : "多头";
        return ResearchPrompts.evidenceBlock(state) + """
                %s
                辩论轮次：%d

                === 论点索引与近期辩论细节 ===
                %s

                %s
                可回应的%s论点见上方索引。respondsToPointIds 只能填写索引中对方论点的编号（如 %s-1.2），不得使用本方编号。
                如有与本轮回应直接相关、但双方都还没引用过的证据，优先用它支撑或检验论点；不要为了引用而引用无关证据。

                请按以下两段顺序输出：
                第一段用简体中文简要概括本轮回应，不得使用大括号。
                第二段输出严格 JSON 代码块，除此之外不要再输出文字：
                ```json
                {
                  "points": [
                    {
                      "type": "REBUTTAL",
                      "claim": "对指定%s论点的回应结论",
                      "horizon": "SHORT_TERM 或 MEDIUM_TERM 或 LONG_TERM 或 UNSPECIFIED",
                      "evidenceRefs": [
                        {"evidenceId": "快照中的真实 evidenceId", "excerpt": "该 evidenceId 内容中的连续原文摘录"}
                      ],
                      "reasoning": "承认、部分承认或反驳的依据",
                      "assumption": "回应成立所需假设",
                      "invalidationCondition": "使回应失效的条件",
                      "respondsToPointIds": ["索引中真实存在的%s论点编号"]
                    }
                  ]
                }
                ```

                硬性要求：
                - points 必须有 2-3 条，type 全部为 REBUTTAL；每条必须回应至少一个对方论点编号。
                - 不得新增独立主论点，不得回应本方论点，不要自行生成编号或 pointId。
                - 每条至少绑定一个可用 evidenceId，excerpt 必须逐字来自该 ID 对应的 content，且至少 12 个字符。
                - 无法用证据反驳时可以承认对方，但仍需明确说明本方假设如何被削弱。
                """.formatted(
                ResearchPrompts.roleSection(role),
                round,
                untrustedContext(ResearchPrompts.renderDebateHistory(state.getDebateTurns(), 14_000)),
                ResearchPrompts.unusedEvidenceSection(state),
                opponentLabel,
                bull ? "BEAR" : "BULL",
                opponentLabel,
                opponentLabel);
    }

}
