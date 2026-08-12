package com.stocksage.config;

import com.stocksage.tool.CompatibilityTools;
import com.stocksage.tool.FundamentalsTools;
import com.stocksage.tool.MarketTools;
import com.stocksage.tool.NewsTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智能体专用 ChatClient 配置。
 *
 * <p>这里为不同研究角色创建相互独立的 ChatClient Bean，每个 Bean 都从基础 Builder 克隆，
 * 再注入自己的系统提示词和工具集合。这样可以把“基本面、市场、新闻、辩论、总结”的职责边界
 * 固定在配置层，避免某个角色误用不属于自己的工具或输出风格。</p>
 */
@Configuration
public class AgentConfig {

    /** 低成本规划任务使用的快速模型。 */
    @Value("${stocksage.chat.model-routing.fast-model:qwen3.6-flash}")
    private String fastModel;

    /** 基本面、行情和新闻分析师默认使用的标准模型。 */
    @Value("${stocksage.chat.model-routing.standard-model:${STOCKSAGE_CHAT_MODEL:qwen3.6-plus}}")
    private String standardModel;

    /** 多空研究员和研究经理使用的高能力模型。 */
    @Value("${stocksage.chat.model-routing.strong-model:qwen3.6-max-preview}")
    private String strongModel;

    /** Agent 生成文本时的统一采样温度。 */
    @Value("${stocksage.chat.model-routing.temperature:${STOCKSAGE_CHAT_TEMPERATURE:0.7}}")
    private double modelRoutingTemperature;

    /** 单次 Agent 调用允许生成的最大 token 数。 */
    @Value("${stocksage.chat.model-routing.max-output-tokens:${STOCKSAGE_CHAT_MAX_OUTPUT_TOKENS:4096}}")
    private int modelRoutingMaxOutputTokens;

    /**
     * 创建基本面分析师客户端。
     *
     * <p>该角色绑定财报、公告和结构化财务数据工具，输出会作为后续研究经理汇总时的重要证据来源。
     * 系统提示词中特别区分 A 股、港股和美股的数据来源，避免分析师把不同市场的数据接口混用。</p>
     *
     * @param builder Spring AI 提供的基础客户端构建器
     * @param fundamentalsTools 财报与结构化财务数据工具
     * @param compatibilityTools 搜索、公告等兼容工具
     * @return 仅开放基本面相关工具的 ChatClient
     */
    @Bean("fundamentalsAgentChatClient")
    public ChatClient fundamentalsAgentChatClient(ChatClient.Builder builder,
                                                  FundamentalsTools fundamentalsTools,
                                                  CompatibilityTools compatibilityTools) {
        return builder.clone()
                .defaultOptions(chatOptions(standardModel))
                .defaultSystem("""
                        你是 StockSage Fundamentals Agent，专注 A 股、港股、美股财报、结构化财务数据、风险因素和经营质量。
                        美股 SEC 10-K/10-Q 可使用 ingestCompanyFilings 与 getStructuredFinancials；getFinancialReports 对美股也应走 SEC EDGAR XBRL。A 股走 baostock，港股走 AKShare，并用 searchCompanyReports 搜索公司公告/年报原文。
                        输出应包含关键事实、财务趋势、风险因素和可引用来源。
                        """)
                .defaultTools(fundamentalsTools, compatibilityTools)
                .build();
    }

    /**
     * 创建市场分析师客户端。
     *
     * <p>该角色绑定行情和 IBKR 只读工具，负责实时价格、K 线、技术指标、估值指标和账户快照。
     * 提示词要求必须通过工具读取运行时数据，防止模型凭记忆编造报价、持仓或技术指标。</p>
     *
     * @param builder Spring AI 提供的基础客户端构建器
     * @param marketTools 行情、指标和 IBKR 只读工具
     * @param compatibilityTools 股票搜索等兼容工具
     * @return 仅开放市场分析相关工具的 ChatClient
     */
    @Bean("marketAgentChatClient")
    public ChatClient marketAgentChatClient(ChatClient.Builder builder,
                                            MarketTools marketTools,
                                            CompatibilityTools compatibilityTools) {
        return builder.clone()
                .defaultOptions(chatOptions(standardModel))
                .defaultSystem("""
                        你是 StockSage Market Agent，专注实时行情、K 线、技术指标、估值指标、横向对比和 IBKR 只读账户数据。
                        必须使用市场工具获取运行时数据；不要编造报价、持仓或技术指标。
                        输出应短而结构化，突出数据观察、趋势判断和数据缺口。
                        """)
                .defaultTools(marketTools, compatibilityTools)
                .build();
    }

    /**
     * 创建新闻分析师客户端。
     *
     * <p>该角色绑定新闻与网页搜索工具，负责处理具有时效性的宏观政策、公司事件和市场情绪。
     * 输出侧重信息时间、来源和不确定性，供研究经理判断新闻证据是否仍然有效。</p>
     *
     * @param builder Spring AI 提供的基础客户端构建器
     * @param newsTools 新闻与网页搜索工具
     * @param compatibilityTools 股票搜索等兼容工具
     * @return 仅开放新闻研究相关工具的 ChatClient
     */
    @Bean("newsAgentChatClient")
    public ChatClient newsAgentChatClient(ChatClient.Builder builder,
                                          NewsTools newsTools,
                                          CompatibilityTools compatibilityTools) {
        return builder.clone()
                .defaultOptions(chatOptions(standardModel))
                .defaultSystem("""
                        你是 StockSage News Agent，专注最新新闻、宏观政策、事件影响和市场情绪。
                        对时效性问题必须使用 searchNews 或 webSearch；回答要标注信息时间和不确定性。
                        """)
                .defaultTools(newsTools, compatibilityTools)
                .build();
    }

    /**
     * 创建多头研究员客户端。
     *
     * <p>多头研究员不直接调用工具，只基于分析师已给出的证据组织正方论点，
     * 重点挖掘增长、护城河、估值修复和潜在催化剂。</p>
     *
     * @param builder Spring AI 提供的基础客户端构建器
     * @return 不绑定工具的多头研究员 ChatClient
     */
    @Bean("bullResearcherChatClient")
    public ChatClient bullResearcherChatClient(ChatClient.Builder builder) {
        return researcherClient(builder, "Bull Researcher", "构建看多论据，强调增长、护城河、估值上修和催化剂。");
    }

    /**
     * 创建空头研究员客户端。
     *
     * <p>空头研究员同样不直接调用工具，只针对已有证据提出反方论点，
     * 重点检查估值压力、竞争、周期、财务质量和执行风险。</p>
     *
     * @param builder Spring AI 提供的基础客户端构建器
     * @return 不绑定工具的空头研究员 ChatClient
     */
    @Bean("bearResearcherChatClient")
    public ChatClient bearResearcherChatClient(ChatClient.Builder builder) {
        return researcherClient(builder, "Bear Researcher", "构建看空论据，强调估值压力、竞争、周期、财务和执行风险。");
    }

    /**
     * 创建研究经理客户端。
     *
     * <p>研究经理负责整合分析师报告和多空辩论，输出最终结构化结论。
     * 这里的提示词强调公司一致性校验和证据追溯，避免把不同公司或不同 ticker 的信息拼接成同一份报告。</p>
     *
     * @param builder Spring AI 提供的基础客户端构建器
     * @return 负责最终研究综合的 ChatClient
     */
    @Bean("researchManagerChatClient")
    public ChatClient researchManagerChatClient(ChatClient.Builder builder) {
        return builder.clone()
                .defaultOptions(chatOptions(strongModel))
                .defaultSystem("""
                        你是 StockSage Research Manager，负责综合 Analyst 报告和 Bull/Bear 辩论。
                        输出必须平衡证据，给出 recommendation、rationale、riskFactors、evidenceItems、unknowns、citations。
                        在综合前必须核对所有 Analyst 报告是否围绕同一家公司；若出现 ticker、公司名、行业或主营业务不一致，忽略不一致内容并把它列为数据质量风险，不得合并成同一家公司结论。
                        每个关键结论都要能追溯到财务、行情、新闻、公告或知识库证据；证据不足时写入 unknowns，不要补编数据。
                        始终提示：仅供参考，不构成投资建议。
                        """)
                .build();
    }

    /**
     * 创建辩论轮次规划客户端。
     *
     * <p>轮次规划只需根据证据复杂度输出一个整数 JSON，不需要前沿模型，因此固定走 FAST 档。
     * 配合 {@code ResearchDebateService} 让它与第 1 轮辩论并发执行，使其不再占用深度研究的关键路径。</p>
     *
     * @param builder Spring AI 提供的基础客户端构建器
     * @return 只输出轮次决策的 ChatClient
     */
    @Bean("debatePlannerChatClient")
    public ChatClient debatePlannerChatClient(ChatClient.Builder builder) {
        return builder.clone()
                .defaultOptions(chatOptions(fastModel))
                .defaultSystem("""
                        你是 StockSage 的辩论轮次规划器，只根据证据复杂度输出严格 JSON 决定多空辩论轮数。
                        不解释、不回答投资问题本身。
                        """)
                .build();
    }

    /**
     * 构造多空研究员共用的客户端模板。
     *
     * @param builder 基础 ChatClient 构建器
     * @param role 研究员角色名称，会写入系统提示词
     * @param focus 当前研究员的论证重点
     * @return 已配置系统提示词的研究员 ChatClient
     */
    private ChatClient researcherClient(ChatClient.Builder builder, String role, String focus) {
        return builder.clone()
                .defaultOptions(chatOptions(strongModel))
                .defaultSystem("""
                        你是 StockSage 的 %s。
                        任务：基于 Analyst 报告进行投资辩论，不调用工具，不编造数据。
                        关注点：%s
                        如果 Analyst 报告中的公司名称、ticker、行业或主营业务互相冲突，必须指出冲突并拒绝使用无关公司事实。
                        输出要列出最强论据、反方可能反驳、需要验证的关键假设。
                        """.formatted(role, focus))
                .build();
    }

    /**
     * 统一构造 Agent 使用的后端白名单模型选项。
     *
     * <p>chat 走百炼 OpenAI 兼容接口，模型名通过标准 OpenAI 协议传递。</p>
     *
     * @param modelName 本次角色应使用的模型名；空值时回退到标准模型
     * @return 包含模型、温度和输出上限的调用选项
     */
    private OpenAiChatOptions chatOptions(String modelName) {
        String resolvedModel = modelName == null || modelName.isBlank() ? standardModel : modelName.trim();
        return OpenAiChatOptions.builder()
                .model(resolvedModel)
                .temperature(modelRoutingTemperature)
                .maxTokens(modelRoutingMaxOutputTokens)
                .build();
    }
}
