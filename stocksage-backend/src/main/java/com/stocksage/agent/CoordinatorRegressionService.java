package com.stocksage.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.stocksage.model.dto.PlannerEvalCase;
import com.stocksage.model.dto.PlannerEvalMode;
import com.stocksage.model.dto.PlannerEvalRequest;
import com.stocksage.model.dto.PlannerEvalResponse;
import com.stocksage.model.dto.PlannerEvalResult;
import com.stocksage.service.PlannerEvalService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinator 路由的小型确定性回归测试入口。
 *
 * <p>上游回归/管理入口调用本服务，它再委托 {@link PlannerEvalService} 以确定性模式评估固定样例。
 * 因此结果不依赖网络或模型服务；它验证“计划含必要动作”，不证明真实工具执行或端到端完成率。</p>
 */
@Service
@RequiredArgsConstructor
public class CoordinatorRegressionService {

    /** 复用统一 Planner Eval 契约和判定逻辑。 */
    private final PlannerEvalService plannerEvalService;

    /**
     * 运行默认 Coordinator 路由回归用例。
     *
     * <p>该检查只调用确定性规划方法，不触发模型、网络或外部工具，适合作为开发期快速烟测。</p>
     */
    public Map<String, Object> runDefaultRegression() {
        List<PlannerEvalCase> regressionCases = regressionCases();
        // 调用统一 Eval 服务，而不是在此复制 Coordinator 动作匹配规则。
        PlannerEvalResponse response = plannerEvalService.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.DETERMINISTIC, regressionCases)
        );
        List<Map<String, Object>> cases = java.util.stream.IntStream.range(0, regressionCases.size())
                .mapToObj(index -> toLegacyCase(regressionCases.get(index), response.results().get(index)))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", response.status());
        result.put("cases", cases);
        result.put("durationMs", response.durationMs());
        result.put("checkedAt", response.checkedAt());
        return result;
    }

    /**
     * 执行单条回归用例并输出结构化结果。
     *
     * <p>判定逻辑只要求计划动作包含期望动作，允许 Coordinator 额外添加最终回答等辅助步骤。</p>
     *
     * @param evalCase 固定评估用例
     * @param evalResult Planner Eval 的结构化结果
     * @return 兼容旧接口字段名的结果 Map
     */
    private Map<String, Object> toLegacyCase(PlannerEvalCase evalCase, PlannerEvalResult evalResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", evalCase.id());
        result.put("query", evalCase.query());
        result.put("route", evalResult.actualRoute());
        result.put("expectedActions", evalCase.requiredActions().stream().map(PlanAction::label).toList());
        result.put("plannedActions", evalResult.plannedActions().stream().map(PlanAction::label).toList());
        result.put("passed", evalResult.passed());
        return result;
    }

    /**
     * 定义覆盖知识直答、行情查询、财报查询和深度研究的基础路由样例。
     *
     * @return 顺序稳定的默认回归用例
     */
    private List<PlannerEvalCase> regressionCases() {
        return List.of(
                new PlannerEvalCase(
                        "direct_knowledge",
                        "什么是市盈率？",
                        1,
                        PlanRoute.DIRECT,
                        List.of(PlanAction.KNOWLEDGE_RETRIEVAL, PlanAction.FINAL_ANSWER),
                        List.of(PlanAction.BULL_RESEARCHER, PlanAction.BEAR_RESEARCHER),
                        true
                ),
                new PlannerEvalCase(
                        "market_query",
                        "NVDA 最近 K 线走势如何？",
                        0,
                        PlanRoute.MARKET,
                        List.of(PlanAction.MARKET_AGENT, PlanAction.GET_STOCK_KLINE, PlanAction.FINAL_ANSWER),
                        List.of(PlanAction.BULL_RESEARCHER, PlanAction.BEAR_RESEARCHER),
                        true
                ),
                new PlannerEvalCase(
                        "fundamentals_filing",
                        "苹果的风险因素有哪些？",
                        0,
                        PlanRoute.FUNDAMENTALS,
                        List.of(PlanAction.FUNDAMENTALS_AGENT, PlanAction.SEARCH_COMPANY_REPORTS,
                                PlanAction.GET_FINANCIAL_REPORTS, PlanAction.KNOWLEDGE_RETRIEVAL,
                                PlanAction.FINAL_ANSWER),
                        List.of(PlanAction.BULL_RESEARCHER, PlanAction.BEAR_RESEARCHER),
                        true
                ),
                new PlannerEvalCase(
                        "deep_analysis",
                        "苹果值不值得长期投资？",
                        0,
                        PlanRoute.DEEP,
                        List.of(PlanAction.FUNDAMENTALS_AGENT, PlanAction.MARKET_AGENT, PlanAction.NEWS_AGENT,
                                PlanAction.BULL_RESEARCHER, PlanAction.BEAR_RESEARCHER,
                                PlanAction.RESEARCH_MANAGER, PlanAction.FINAL_ANSWER),
                        List.of(),
                        true
                )
        );
    }
}
