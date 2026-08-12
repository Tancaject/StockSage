package com.stocksage.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 规划器批量回归评测接口的请求体。
 *
 * @param mode 要评测的规划实现，不能为空
 * @param cases 评测样例列表，不能为空且至少一项；构造后复制为不可变列表
 */
public record PlannerEvalRequest(
        @NotNull PlannerEvalMode mode,
        @NotEmpty List<@Valid PlannerEvalCase> cases
) {
    /**
     * 规范化评测样例，避免调用方后续修改原列表影响本次评测。
     *
     * @param mode 要评测的规划实现
     * @param cases 评测样例；null 会先转为空列表，再由 Bean Validation 判定为非法请求
     */
    public PlannerEvalRequest {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
