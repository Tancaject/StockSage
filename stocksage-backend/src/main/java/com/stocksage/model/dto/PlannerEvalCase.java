package com.stocksage.model.dto;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Typed planner assertion. Execution behavior is compared with enums rather
 * than user-facing labels.
 */
public record PlannerEvalCase(
        @NotBlank String id,
        @NotBlank String query,
        @Min(0) int ragHitCount,
        String expectedPrimaryIntent,
        List<String> expectedSecondaryIntents,
        @NotNull PlanRoute expectedRoute,
        List<PlanAction> requiredActions,
        List<PlanAction> forbiddenActions,
        boolean critical
) {
    public PlannerEvalCase {
        expectedPrimaryIntent = expectedPrimaryIntent == null ? "" : expectedPrimaryIntent;
        expectedSecondaryIntents = expectedSecondaryIntents == null
                ? List.of()
                : List.copyOf(expectedSecondaryIntents);
        requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
        forbiddenActions = forbiddenActions == null ? List.of() : List.copyOf(forbiddenActions);
    }

    public PlannerEvalCase(String id,
                           String query,
                           int ragHitCount,
                           PlanRoute expectedRoute,
                           List<PlanAction> requiredActions,
                           List<PlanAction> forbiddenActions,
                           boolean critical) {
        this(id, query, ragHitCount, "", List.of(), expectedRoute,
                requiredActions, forbiddenActions, critical);
    }
}
