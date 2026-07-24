package com.stocksage.service;

import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.IntentDecision;
import com.stocksage.agent.IntentPlanAssembler;
import com.stocksage.agent.IntentRecognitionRequest;
import com.stocksage.agent.IntentRecognitionService;
import com.stocksage.agent.PlanAction;
import com.stocksage.model.dto.PlannerEvalCase;
import com.stocksage.model.dto.PlannerEvalMode;
import com.stocksage.model.dto.PlannerEvalRequest;
import com.stocksage.model.dto.PlannerEvalResponse;
import com.stocksage.model.dto.PlannerEvalResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Deterministic, typed planner evaluation used by regression endpoints and
 * offline golden-set runners.
 */
@Service
public class PlannerEvalService {

    public static final String SCHEMA_VERSION = "planner_eval_v1";
    private final Coordinator coordinator;
    private final IntentRecognitionService intentRecognitionService;
    private final IntentPlanAssembler intentPlanAssembler;

    public PlannerEvalService(Coordinator coordinator,
                              IntentRecognitionService intentRecognitionService,
                              IntentPlanAssembler intentPlanAssembler) {
        this.coordinator = coordinator;
        this.intentRecognitionService = intentRecognitionService;
        this.intentPlanAssembler = intentPlanAssembler;
    }

    public PlannerEvalResponse evaluate(PlannerEvalRequest request) {
        long startedAt = System.currentTimeMillis();
        List<PlannerEvalResult> results = request.cases().stream()
                .map(evalCase -> evaluateCase(request.mode(), evalCase))
                .toList();

        int total = results.size();
        int passed = (int) results.stream().filter(PlannerEvalResult::passed).count();
        int criticalFailures = (int) results.stream()
                .filter(result -> result.critical() && !result.passed())
                .count();
        int executable = (int) results.stream().filter(PlannerEvalResult::executable).count();
        int routeMatches = (int) results.stream()
                .filter(result -> result.executable() && result.expectedRoute() == result.actualRoute())
                .count();
        long requiredTotal = request.cases().stream().mapToLong(item -> item.requiredActions().size()).sum();
        long requiredMissing = results.stream().mapToLong(item -> item.missingRequiredActions().size()).sum();
        long forbiddenTotal = request.cases().stream().mapToLong(item -> item.forbiddenActions().size()).sum();
        long forbiddenMatched = results.stream().mapToLong(item -> item.matchedForbiddenActions().size()).sum();

        String status;
        status = passed == total ? "passed" : "failed";
        return new PlannerEvalResponse(
                SCHEMA_VERSION,
                request.mode(),
                status,
                total,
                passed,
                criticalFailures,
                ratio(routeMatches, total),
                requiredTotal == 0 ? 1.0 : ratio(requiredTotal - requiredMissing, requiredTotal),
                forbiddenTotal == 0 ? 0.0 : ratio(forbiddenMatched, forbiddenTotal),
                ratio(executable, total),
                System.currentTimeMillis() - startedAt,
                LocalDateTime.now().toString(),
                results
        );
    }

    private PlannerEvalResult evaluateCase(PlannerEvalMode mode, PlannerEvalCase evalCase) {
        long startedAt = System.currentTimeMillis();
        try {
            ExecutionPlan plan;
            if (mode == PlannerEvalMode.INTENT_SHADOW) {
                IntentDecision decision = intentRecognitionService.recognize(new IntentRecognitionRequest(
                        evalCase.query(), List.of(), evalCase.ragHitCount(), false, List.of()
                ));
                if (decision == null) {
                    return new PlannerEvalResult(
                            evalCase.id(), evalCase.expectedRoute(), null, List.of(),
                            evalCase.requiredActions(), List.of(), evalCase.critical(),
                            false, false, System.currentTimeMillis() - startedAt,
                            "INTENT_OUTPUT_INVALID"
                    );
                }
                plan = intentPlanAssembler.assemble(
                        decision, evalCase.ragHitCount(), System.currentTimeMillis() - startedAt
                );
            } else {
                plan = mode == PlannerEvalMode.DETERMINISTIC
                        ? coordinator.planDeterministically(evalCase.query(), evalCase.ragHitCount())
                        : coordinator.plan(evalCase.query(), evalCase.ragHitCount());
            }
            List<PlanAction> planned = plan.actions() == null ? List.of() : List.copyOf(plan.actions());
            List<PlanAction> missing = evalCase.requiredActions().stream()
                    .filter(action -> !planned.contains(action))
                    .toList();
            List<PlanAction> forbidden = evalCase.forbiddenActions().stream()
                    .filter(planned::contains)
                    .toList();
            boolean passed = plan.route() == evalCase.expectedRoute()
                    && missing.isEmpty()
                    && forbidden.isEmpty();
            return new PlannerEvalResult(
                    evalCase.id(), evalCase.expectedRoute(), plan.route(), planned,
                    missing, forbidden, evalCase.critical(), passed, true,
                    System.currentTimeMillis() - startedAt, ""
            );
        } catch (Exception ignored) {
            return new PlannerEvalResult(
                    evalCase.id(), evalCase.expectedRoute(), null, List.of(),
                    evalCase.requiredActions(), List.of(), evalCase.critical(),
                    false, false, System.currentTimeMillis() - startedAt,
                    "PLANNER_EXECUTION_FAILED"
            );
        }
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }
}
