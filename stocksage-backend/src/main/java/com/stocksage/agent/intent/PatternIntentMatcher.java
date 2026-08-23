package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 高精度中英文表达匹配器。
 *
 * <p>规则只产生有限的意图/路由证据，不生成动作。故意不匹配“分析”“看看”等自由关键词，
 * 避免一个宽泛词越过语义识别直接控制执行计划。</p>
 */
public final class PatternIntentMatcher {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern UNSUPPORTED_TRADE_ACTION = Pattern.compile(
            "(?:下单|撤单|取消订单|买入\\s*\\d+\\s*股|卖出\\s*\\d+\\s*股|"
                    + "\\b(?:place|cancel)\\s+(?:an?\\s+)?order\\b|"
                    + "\\b(?:buy|sell)\\s+\\d+\\s+shares?\\b)", FLAGS);
    private static final Pattern DEEP = Pattern.compile(
            "(?:值不值得(?:长期)?投资|是否值得(?:长期)?投资|是否值得(?:买入|长期持有)|投资价值|长期持有|多空逻辑|"
                    + "\\b(?:worth\\s+investing|investment\\s+thesis|long[- ]term\\s+investment|"
                    + "should\\s+i\\s+(?:buy|invest)|bull\\s+(?:and|/)\\s+bear)\\b)", FLAGS);
    private static final Pattern PORTFOLIO = Pattern.compile(
            "(?:组合诊断|持仓诊断|我的持仓|投资组合|"
                    + "\\b(?:portfolio|holdings?)\\s+(?:diagnosis|review|risk|analysis)\\b)", FLAGS);
    private static final Pattern FUNDAMENTALS = Pattern.compile(
            "(?:\\b10\\s*-[kq]\\b|财报|年报|季报|风险因素|资产负债表|现金流量表|收入结构|"
                    + "\\b(?:annual|quarterly)\\s+(?:report|filing)\\b|"
                    + "\\bsec\\s+filing\\b|\\brisk\\s+factors?\\b)", FLAGS);
    private static final Pattern NEWS = Pattern.compile(
            "(?:新闻|最新消息|新进展|舆情|市场头条|美联储.{0,8}消息|"
                    + "\\b(?:latest|recent)\\b.{0,40}\\b(?:news|developments?|headlines?)\\b|"
                    + "\\bnews\\s+(?:about|for|on)\\b|\\bmarket\\s+headlines?\\b|"
                    + "\\bfomc\\s+developments?\\b)", FLAGS);
    private static final Pattern TECHNICAL = Pattern.compile(
            "(?:K\\s*线|技术指标|均线|布林带|\\b(?:macd|rsi|bollinger|candlestick|"
                    + "technical\\s+analysis|moving\\s+average)\\b)", FLAGS);
    private static final Pattern MARKET = Pattern.compile(
            "(?:股价|实时行情|股票行情|成交量|换手率|"
                    + "\\b(?:stock\\s+price|market\\s+quote|price\\s+chart|trading\\s+volume)\\b|"
                    + "\\b(?:pe|pb|roe)\\b)", FLAGS);
    private static final Pattern COMPARISON = Pattern.compile(
            "(?:比较|对比|哪个更好|\\bcompare\\b.{0,80}\\b(?:and|with|versus|vs\\.?)\\b|"
                    + "\\bversus\\b|\\bvs\\.?\\b)", FLAGS);
    private static final Pattern EXPLANATION = Pattern.compile(
            "(?:什么是|是什么意思|解释一下|如何理解|概念是什么|"
                    + "\\bwhat\\s+is\\b|\\bexplain\\b|\\bhow\\s+does\\b.{0,80}\\bwork\\b|"
                    + "\\bdefinition\\s+of\\b)", FLAGS);

    private static final Pattern REAL_TIME = Pattern.compile(
            "(?:实时|现在|当前股价|今天的?股价|\\b(?:real[- ]time|right\\s+now|current\\s+price)\\b)", FLAGS);
    private static final Pattern RECENT = Pattern.compile(
            "(?:今天|最新|近期|最近|刚刚|\\b(?:today|latest|recent|currently)\\b)", FLAGS);
    private static final Pattern HISTORICAL = Pattern.compile(
            "(?:历史|过去|去年|往年|\\b(?:historical|history|last\\s+year|past\\s+five\\s+years)\\b)", FLAGS);

    /** 返回第一条高精度证据；没有可靠表达时保持为空。 */
    public Optional<IntentSignal> match(IntentRecognitionRequest request) {
        if (request == null || request.currentQuestion().isBlank()) {
            return Optional.empty();
        }
        String query = request.currentQuestion();
        TimeSensitivity time = inferTimeSensitivity(query);
        Map<String, String> entities = IntentBounds.entitiesFromTickers(request.tickerCandidates());
        String resolved = IntentBounds.resolvedQuery(request);

        if (UNSUPPORTED_TRADE_ACTION.matcher(query).find()) {
            return Optional.of(signal(FineIntent.UNKNOWN, IntentGroup.UNKNOWN, PlanRoute.DIRECT,
                    0.99, time, AnalysisDepth.BRIEF, entities, resolved,
                    "检测到项目只读边界之外的交易执行请求。", "PATTERN_UNSUPPORTED_TRADE_ACTION"));
        }
        if (DEEP.matcher(query).find()) {
            return Optional.of(signal(FineIntent.DEEP_RESEARCH, IntentGroup.RESEARCH, PlanRoute.DEEP,
                    0.98, time, AnalysisDepth.DEEP, entities, resolved,
                    "检测到投资判断或长期多维研究表达。", "PATTERN_DEEP_INVESTMENT_JUDGMENT"));
        }
        if (PORTFOLIO.matcher(query).find()) {
            return Optional.of(signal(FineIntent.PORTFOLIO_DIAGNOSIS, IntentGroup.RESEARCH, PlanRoute.DEEP,
                    0.96, time, AnalysisDepth.DEEP, entities, resolved,
                    "检测到组合或持仓诊断表达。", "PATTERN_PORTFOLIO_DIAGNOSIS"));
        }
        if (FUNDAMENTALS.matcher(query).find()) {
            return Optional.of(signal(FineIntent.FUNDAMENTALS, IntentGroup.FUNDAMENTALS,
                    PlanRoute.FUNDAMENTALS, 0.97, time, AnalysisDepth.STANDARD, entities, resolved,
                    "检测到财报、监管文件或财务披露表达。", "PATTERN_FUNDAMENTALS_FILING"));
        }
        if (NEWS.matcher(query).find()) {
            return Optional.of(signal(FineIntent.NEWS_EVENT, IntentGroup.NEWS, PlanRoute.NEWS,
                    0.96, time, AnalysisDepth.STANDARD, entities, resolved,
                    "检测到新闻、事件或宏观进展表达。", "PATTERN_NEWS_EVENT"));
        }
        if (TECHNICAL.matcher(query).find()) {
            return Optional.of(signal(FineIntent.TECHNICAL_ANALYSIS, IntentGroup.MARKET, PlanRoute.MARKET,
                    0.96, time, AnalysisDepth.STANDARD, entities, resolved,
                    "检测到 K 线或技术指标表达。", "PATTERN_TECHNICAL_ANALYSIS"));
        }
        boolean explanation = EXPLANATION.matcher(query).find();
        if (MARKET.matcher(query).find() && !(explanation && request.tickerCandidates().isEmpty())) {
            return Optional.of(signal(FineIntent.MARKET_DATA, IntentGroup.MARKET, PlanRoute.MARKET,
                    0.94, time, AnalysisDepth.STANDARD, entities, resolved,
                    "检测到行情、价格或单点指标表达。", "PATTERN_MARKET_DATA"));
        }
        if (COMPARISON.matcher(query).find()) {
            return Optional.of(signal(FineIntent.COMPARISON, IntentGroup.RESEARCH, PlanRoute.DEEP,
                    0.90, time, AnalysisDepth.DEEP, entities, resolved,
                    "检测到未限定单一数据域的比较研究表达。", "PATTERN_CROSS_DOMAIN_COMPARISON"));
        }
        if (explanation) {
            return Optional.of(signal(FineIntent.KNOWLEDGE_EXPLANATION, IntentGroup.KNOWLEDGE,
                    PlanRoute.DIRECT, 0.94, TimeSensitivity.NONE, AnalysisDepth.BRIEF,
                    entities, resolved, "检测到概念解释表达。", "PATTERN_KNOWLEDGE_EXPLANATION"));
        }
        return Optional.empty();
    }

    private IntentSignal signal(FineIntent intent,
                                IntentGroup group,
                                PlanRoute route,
                                double confidence,
                                TimeSensitivity time,
                                AnalysisDepth depth,
                                Map<String, String> entities,
                                String resolved,
                                String rationale,
                                String reasonCode) {
        return new IntentSignal(
                intent, group, route.name(), route, IntentSignalSource.PATTERN, confidence,
                time, depth, Map.of(IntentSignalSource.PATTERN, confidence), entities,
                resolved, rationale, List.of(reasonCode)
        );
    }

    private TimeSensitivity inferTimeSensitivity(String query) {
        if (REAL_TIME.matcher(query).find()) {
            return TimeSensitivity.REAL_TIME;
        }
        if (RECENT.matcher(query).find()) {
            return TimeSensitivity.RECENT;
        }
        if (HISTORICAL.matcher(query).find()) {
            return TimeSensitivity.HISTORICAL;
        }
        return TimeSensitivity.UNSPECIFIED;
    }
}
