package com.stocksage.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IntentAwarePlanner {

    private final Coordinator coordinator;
    private final IntentRecognitionService recognitionService;
    private final IntentPlanAssembler assembler;
    private final MeterRegistry meterRegistry;
    private final IntentMode mode;

    public IntentAwarePlanner(Coordinator coordinator,
                              IntentRecognitionService recognitionService,
                              IntentPlanAssembler assembler,
                              MeterRegistry meterRegistry,
                              @Value("${stocksage.agent.intent.mode:LEGACY}") String mode) {
        this.coordinator = coordinator;
        this.recognitionService = recognitionService;
        this.assembler = assembler;
        this.meterRegistry = meterRegistry;
        this.mode = parseMode(mode);
    }

    public ExecutionPlan plan(IntentRecognitionRequest request) {
        if (mode == IntentMode.LEGACY) {
            return coordinator.plan(request.currentQuestion(), request.ragHitCount());
        }
        long startedAt = System.currentTimeMillis();
        IntentDecision decision = recognitionService.recognize(request);
        if (mode == IntentMode.ACTIVE) {
            if (decision != null) {
                return assembler.assemble(decision, request.ragHitCount(),
                        System.currentTimeMillis() - startedAt);
            }
            return coordinator.planDeterministically(request.currentQuestion(), request.ragHitCount());
        }

        ExecutionPlan legacy = coordinator.plan(request.currentQuestion(), request.ragHitCount());
        String outcome = decision == null
                ? "invalid"
                : (decision.suggestedRoute() == legacy.route() ? "agree" : "disagree");
        Counter.builder("stocksage.intent.shadow.decisions")
                .tags("outcome", outcome, "legacy_route", legacy.route().name().toLowerCase())
                .register(meterRegistry)
                .increment();
        RoutingDecisionMetadata legacyMetadata = legacy.routingDecision();
        List<String> signals = new ArrayList<>(legacyMetadata == null
                ? List.of()
                : legacyMetadata.matchedSignals());
        signals.add("intent-shadow-" + outcome);
        RoutingDecisionMetadata shadowMetadata = new RoutingDecisionMetadata(
                legacyMetadata == null
                        ? RoutingDecisionSource.LEGACY_LLM
                        : legacyMetadata.decisionSource(),
                decision == null ? legacy.route().name() : decision.primaryIntent().name(),
                decision == null
                        ? List.of()
                        : decision.secondaryIntents().stream().map(Enum::name).toList(),
                legacy.route(),
                signals,
                request.ragHitCount(),
                legacyMetadata == null ? "" : legacyMetadata.fallbackReason(),
                legacyMetadata == null ? 0L : legacyMetadata.durationMs()
        );
        return new ExecutionPlan(
                legacy.route(), legacy.taskType(), legacy.thought(), legacy.actions(),
                legacy.observation(), legacy.modelTier(), shadowMetadata
        );
    }

    public IntentMode mode() {
        return mode;
    }

    private IntentMode parseMode(String value) {
        try {
            return IntentMode.valueOf(value == null ? "LEGACY" : value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return IntentMode.LEGACY;
        }
    }
}
