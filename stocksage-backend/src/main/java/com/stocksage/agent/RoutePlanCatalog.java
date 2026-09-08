package com.stocksage.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将已验证的五种路由转换为后端拥有的固定执行计划片段。
 *
 * <p>意图识别只负责产出 {@link PlanRoute}；工具、Agent、动作顺序、任务名称和模型层级
 * 都只能从本目录取得。这样既允许识别器直接选择五种路由之一，也不会让模型生成可执行动作。</p>
 */
@Component
public final class RoutePlanCatalog {

    /**
     * 解析唯一的服务器端计划定义。
     *
     * @param route 已验证路由
     * @param resolvedQuery 已结合近期上下文消歧的问题
     * @param ragHitCount 知识库命中数
     * @return 不可变计划定义
     */
    public RoutePlanSpec resolve(PlanRoute route, String resolvedQuery, int ragHitCount) {
        return resolve(route, resolvedQuery, ragHitCount, false);
    }

    /** 澄清计划仍属于 DIRECT，但只允许返回澄清文本，不声明或执行检索动作。 */
    public RoutePlanSpec resolve(PlanRoute route,
                                 String resolvedQuery,
                                 int ragHitCount,
                                 boolean needsClarification) {
        PlanRoute safeRoute = route == null ? PlanRoute.DIRECT : route;
        if (needsClarification) {
            return new RoutePlanSpec(
                    "意图澄清",
                    List.of(PlanAction.FINAL_ANSWER),
                    ModelTier.FAST
            );
        }
        return new RoutePlanSpec(
                taskType(safeRoute),
                actions(safeRoute),
                modelTier(safeRoute, resolvedQuery, ragHitCount)
        );
    }

    private List<PlanAction> actions(PlanRoute route) {
        List<PlanAction> routeActions = switch (route) {
            case MARKET -> List.of(
                    PlanAction.GET_STOCK_KLINE,
                    PlanAction.GET_FINANCIAL_METRICS,
                    PlanAction.GET_TECHNICAL_INDICATORS,
                    PlanAction.MARKET_AGENT
            );
            case FUNDAMENTALS -> List.of(
                    PlanAction.KNOWLEDGE_RETRIEVAL,
                    PlanAction.GET_FINANCIAL_REPORTS,
                    PlanAction.SEARCH_COMPANY_REPORTS,
                    PlanAction.FUNDAMENTALS_AGENT
            );
            case DEEP -> List.of(
                    PlanAction.FUNDAMENTALS_AGENT,
                    PlanAction.MARKET_AGENT,
                    PlanAction.NEWS_AGENT,
                    PlanAction.BULL_RESEARCHER,
                    PlanAction.BEAR_RESEARCHER,
                    PlanAction.RESEARCH_MANAGER
            );
            case NEWS -> List.of(
                    PlanAction.SEARCH_NEWS,
                    PlanAction.WEB_SEARCH,
                    PlanAction.NEWS_AGENT
            );
            case DIRECT -> List.of(PlanAction.KNOWLEDGE_RETRIEVAL);
        };
        // 动作顺序是 ToolPrefetch 与 Trace 的行为契约，复制后只在尾部追加最终回答。
        List<PlanAction> result = new ArrayList<>(routeActions);
        result.add(PlanAction.FINAL_ANSWER);
        return List.copyOf(result);
    }

    private String taskType(PlanRoute route) {
        return switch (route) {
            case MARKET -> "单点市场查询";
            case FUNDAMENTALS -> "单点财报查询";
            case DEEP -> "深度投资分析";
            case NEWS -> "新闻与事件分析";
            case DIRECT -> "知识类问答";
        };
    }

    private ModelTier modelTier(PlanRoute route, String resolvedQuery, int ragHitCount) {
        return switch (route) {
            case DEEP -> ModelTier.STRONG;
            case MARKET, FUNDAMENTALS, NEWS -> ModelTier.STANDARD;
            case DIRECT -> looksComplexDirectQuery(resolvedQuery, ragHitCount)
                    ? ModelTier.STANDARD
                    : ModelTier.FAST;
        };
    }

    private boolean looksComplexDirectQuery(String query, int ragHitCount) {
        String normalized = query == null ? "" : query.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        return normalized.length() > 120
                || ragHitCount >= 3
                || containsAny(lower,
                "compare", "detailed", "analysis", "risk", "valuation",
                "对比", "比较", "详细", "深入", "分析", "风险", "估值", "财报", "年报", "季报");
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 服务器拥有的不可变路由计划定义。 */
    public record RoutePlanSpec(String taskType, List<PlanAction> actions, ModelTier modelTier) {
        public RoutePlanSpec {
            taskType = taskType == null ? "" : taskType;
            actions = actions == null ? List.of() : List.copyOf(actions);
            modelTier = modelTier == null ? ModelTier.STANDARD : modelTier;
        }
    }
}
