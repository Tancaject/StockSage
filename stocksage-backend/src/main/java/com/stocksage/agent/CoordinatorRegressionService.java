package com.stocksage.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinator 路由的小型确定性回归测试入口。
 *
 * <p>这里刻意调用 planDeterministically，使结果不依赖网络或模型服务即可检查。</p>
 */
@Service
@RequiredArgsConstructor
public class CoordinatorRegressionService {

    private final Coordinator coordinator;

    /**
     * 运行默认 Coordinator 路由回归用例。
     *
     * <p>该检查只调用确定性规划方法，不触发模型、网络或外部工具，适合作为开发期快速烟测。</p>
     */
    public Map<String, Object> runDefaultRegression() {
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> cases = regressionCases().stream()
                .map(this::runCase)
                .toList();
        boolean passed = cases.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", passed ? "passed" : "failed");
        result.put("cases", cases);
        result.put("durationMs", System.currentTimeMillis() - startedAt);
        result.put("checkedAt", LocalDateTime.now().toString());
        return result;
    }

    /**
     * 执行单条回归用例并输出结构化结果。
     *
     * <p>判定逻辑只要求计划动作包含期望动作，允许 Coordinator 额外添加最终回答等辅助步骤。</p>
     */
    private Map<String, Object> runCase(RegressionCase regressionCase) {
        ExecutionPlan plan = coordinator.planDeterministically(regressionCase.query(), regressionCase.ragHitCount());
        boolean passed = plan.actionLabels().containsAll(regressionCase.expectedActions());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", regressionCase.name());
        result.put("query", regressionCase.query());
        result.put("route", plan.route());
        result.put("taskType", plan.taskType());
        result.put("modelTier", plan.modelTier());
        result.put("expectedActions", regressionCase.expectedActions());
        result.put("plannedActions", plan.actionLabels());
        result.put("passed", passed);
        return result;
    }

    /**
     * 定义覆盖知识直答、行情查询、财报摄取和深度研究的基础路由样例。
     */
    private List<RegressionCase> regressionCases() {
        return List.of(
                new RegressionCase(
                        "direct_knowledge",
                        "什么是市盈率？",
                        1,
                        List.of("Knowledge Retrieval", "Final Answer")
                ),
                new RegressionCase(
                        "market_query",
                        "NVDA 最近 K 线走势如何？",
                        0,
                        List.of("Market Agent", "getStockKLine", "Final Answer")
                ),
                new RegressionCase(
                        "fundamentals_filing",
                        "苹果的风险因素有哪些？",
                        0,
                        // 注意：FUNDAMENTALS 路由的默认计划早已从 ingestCompanyFilings 演进为
                        // searchCompanyReports + getFinancialReports；旧期望使该用例长期 failed。
                        List.of("Fundamentals Agent", "searchCompanyReports", "getFinancialReports",
                                "Knowledge Retrieval", "Final Answer")
                ),
                new RegressionCase(
                        "deep_analysis",
                        "苹果值不值得长期投资？",
                        0,
                        List.of("Fundamentals Agent", "Market Agent", "News Agent", "Bull Researcher", "Bear Researcher", "Research Manager", "Final Answer")
                )
        );
    }

    /**
     * 单条确定性路由回归用例。
     *
     * @param name 用例名称
     * @param query 用户问题
     * @param ragHitCount 预设 RAG 命中数量
     * @param expectedActions 期望规划中包含的动作名称
     */
    private record RegressionCase(String name, String query, int ragHitCount, List<String> expectedActions) {
    }
}
