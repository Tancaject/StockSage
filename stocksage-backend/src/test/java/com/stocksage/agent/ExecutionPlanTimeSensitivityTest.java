package com.stocksage.agent;

import com.stocksage.agent.intent.TimeSensitivity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPlanTimeSensitivityTest {

    @Test
    void onlyExplicitKnownRoutingValuesBecomeTimeRequirements() {
        var legacy = new ExecutionPlan(PlanRoute.DEEP, "", "", List.of(), "", ModelTier.STRONG);
        assertThat(legacy.timeSensitivity()).isEqualTo(TimeSensitivity.UNSPECIFIED);
        for (TimeSensitivity sensitivity : TimeSensitivity.values()) {
            assertThat(plan(sensitivity.name()).timeSensitivity()).isEqualTo(sensitivity);
        }
        for (String value : List.of("", "latest", "FUTURE_ENUM")) {
            assertThat(plan(value).timeSensitivity()).isEqualTo(TimeSensitivity.UNSPECIFIED);
        }
    }

    private ExecutionPlan plan(String value) {
        var routing = new RoutingDecisionMetadata(null, "DEEP", PlanRoute.DEEP, "", "", 1.0,
                List.of(), 0, "", 0, "", "", value, "", Map.of(), Map.of(), false, List.of());
        return new ExecutionPlan(PlanRoute.DEEP, "", "", List.of(), "", ModelTier.STRONG, routing);
    }
}
