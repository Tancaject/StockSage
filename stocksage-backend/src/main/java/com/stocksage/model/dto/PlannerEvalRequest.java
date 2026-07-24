package com.stocksage.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PlannerEvalRequest(
        @NotNull PlannerEvalMode mode,
        @NotEmpty List<@Valid PlannerEvalCase> cases
) {
    public PlannerEvalRequest {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
