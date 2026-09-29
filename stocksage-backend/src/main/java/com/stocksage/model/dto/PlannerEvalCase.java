package com.stocksage.model.dto;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 单条强类型规划器断言。
 *
 * <p>路由和动作直接使用枚举比较，避免展示文案变化导致回归结果漂移。</p>
 *
 * @param id 样例稳定标识，不能为空
 * @param query 送入协调器的用户问题，不能为空
 * @param ragHitCount 模拟已命中的 RAG 文档数，不能为负数
 * @param expectedRoute 期望选择的规划路由，不能为空
 * @param requiredActions 执行计划必须包含的动作；null 规范化为空列表
 * @param forbiddenActions 执行计划不得包含的动作；null 规范化为空列表
 * @param critical 失败时是否计入关键失败数
 * @param recentTurns 当前问题之前、按时间正序排列的最近对话消息；每项包含 user/assistant 角色前缀
 * @param expectedFineIntent 可选的细粒度意图枚举名；为空时不参与单例通过判定
 * @param expectedDecisionSource 可选的路由来源枚举名；为空时不参与单例通过判定
 * @param requireNoFallback 是否要求本例不得使用确定性回退
 * @param expectedResolvedQueryContains 可选的消歧结果片段；提供时要求执行计划的 resolvedQuery 包含它
 * @param expectedClarification 可选的路由元数据澄清标签（含 Coordinator 安全覆盖），不含 ReadRequest 参数澄清
 */
public record PlannerEvalCase(
        @NotBlank String id,
        @NotBlank String query,
        @Min(0) int ragHitCount,
        @NotNull PlanRoute expectedRoute,
        List<PlanAction> requiredActions,
        List<PlanAction> forbiddenActions,
        boolean critical,
        @Size(max = 6) List<@NotBlank @Size(max = 600) String> recentTurns,
        String expectedFineIntent,
        String expectedDecisionSource,
        boolean requireNoFallback,
        String expectedResolvedQueryContains,
        Boolean expectedClarification
) {
    /**
     * 将动作断言复制为不可变列表，防止评测期间被外部修改。
     *
     * @param id 样例标识
     * @param query 用户问题
     * @param ragHitCount RAG 命中数
     * @param expectedRoute 期望路由
     * @param requiredActions 必需动作列表
     * @param forbiddenActions 禁止动作列表
     * @param critical 是否为关键样例
     * @param recentTurns 最近对话消息
     * @param expectedFineIntent 期望细粒度意图
     * @param expectedDecisionSource 期望路由来源
     * @param requireNoFallback 是否禁止回退
     * @param expectedResolvedQueryContains 期望消歧结果包含的片段
     * @param expectedClarification 期望是否请求澄清
     */
    public PlannerEvalCase {
        requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
        forbiddenActions = forbiddenActions == null ? List.of() : List.copyOf(forbiddenActions);
        recentTurns = recentTurns == null ? List.of() : recentTurns.stream()
                .map(value -> value == null ? "" : value.trim())
                .toList();
        expectedFineIntent = normalizeOptionalExpectation(expectedFineIntent);
        expectedDecisionSource = normalizeOptionalExpectation(expectedDecisionSource);
        expectedResolvedQueryContains = normalizeOptionalExpectation(expectedResolvedQueryContains);
    }

    /** 保留十二字段构造方式；旧样例没有澄清标签。 */
    public PlannerEvalCase(
            String id, String query, int ragHitCount, PlanRoute expectedRoute,
            List<PlanAction> requiredActions, List<PlanAction> forbiddenActions, boolean critical,
            List<String> recentTurns, String expectedFineIntent, String expectedDecisionSource,
            boolean requireNoFallback, String expectedResolvedQueryContains
    ) {
        this(id, query, ragHitCount, expectedRoute, requiredActions, forbiddenActions, critical,
                recentTurns, expectedFineIntent, expectedDecisionSource, requireNoFallback,
                expectedResolvedQueryContains, null);
    }

    /** 保留最初 V2 十一字段构造方式，未声明消歧断言的调用保持原语义。 */
    public PlannerEvalCase(
            String id,
            String query,
            int ragHitCount,
            PlanRoute expectedRoute,
            List<PlanAction> requiredActions,
            List<PlanAction> forbiddenActions,
            boolean critical,
            List<String> recentTurns,
            String expectedFineIntent,
            String expectedDecisionSource,
            boolean requireNoFallback
    ) {
        this(id, query, ragHitCount, expectedRoute, requiredActions, forbiddenActions, critical,
                recentTurns, expectedFineIntent, expectedDecisionSource, requireNoFallback, null);
    }

    /** 保留 V1 七字段构造方式，旧回归集合不需要显式补齐 V2 可选断言。 */
    public PlannerEvalCase(
            String id,
            String query,
            int ragHitCount,
            PlanRoute expectedRoute,
            List<PlanAction> requiredActions,
            List<PlanAction> forbiddenActions,
            boolean critical
    ) {
        this(id, query, ragHitCount, expectedRoute, requiredActions, forbiddenActions, critical,
                List.of(), null, null, false, null);
    }

    /** @return 本例是否依赖仅 LIVE_COORDINATOR 能提供的语义或来源信息。 */
    public boolean liveOnly() {
        return !recentTurns.isEmpty()
                || expectedFineIntent != null
                || expectedDecisionSource != null
                || requireNoFallback
                || expectedResolvedQueryContains != null
                || expectedClarification != null;
    }

    private static String normalizeOptionalExpectation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
