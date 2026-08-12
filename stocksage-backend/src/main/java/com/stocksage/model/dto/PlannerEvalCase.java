package com.stocksage.model.dto;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
 */
public record PlannerEvalCase(
        @NotBlank String id,
        @NotBlank String query,
        @Min(0) int ragHitCount,
        @NotNull PlanRoute expectedRoute,
        List<PlanAction> requiredActions,
        List<PlanAction> forbiddenActions,
        boolean critical
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
     */
    public PlannerEvalCase {
        requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
        forbiddenActions = forbiddenActions == null ? List.of() : List.copyOf(forbiddenActions);
    }
}
