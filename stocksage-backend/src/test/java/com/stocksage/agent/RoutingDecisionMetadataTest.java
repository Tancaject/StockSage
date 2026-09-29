package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.intent.FineIntent;
import com.stocksage.agent.intent.IntentSignalSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingDecisionMetadataTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void signalDiagnosticJsonAndAttributesExposeOnlyEnumsAndConfidence() {
        var snapshot = new RoutingDecisionMetadata.SignalSnapshot(
                IntentSignalSource.EMBEDDING, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 0.87);
        List<RoutingDecisionMetadata.SignalSnapshot> signals = new ArrayList<>(List.of(snapshot));
        var metadata = new RoutingDecisionMetadata(RoutingDecisionSource.INTENT_FUSION, "NEWS",
                PlanRoute.NEWS, "intent", "rationale", 0.9, List.of(), 0, "", 1,
                "NEWS_EVENT", "NEWS", "RECENT", "STANDARD", Map.of(), Map.of(), false, List.of(), signals);
        signals.clear();

        assertThat(metadata.signalDiagnostics()).containsExactly(snapshot);
        Map<String, Object> json = mapper.convertValue(snapshot,
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
        assertThat(json).containsOnlyKeys("source", "targetRoute", "fineIntent", "confidence")
                .containsEntry("source", "EMBEDDING")
                .containsEntry("targetRoute", "NEWS")
                .containsEntry("fineIntent", "NEWS_EVENT")
                .containsEntry("confidence", 0.87);
        assertThat(metadata.toAttributes().get("signalDiagnostics")).isEqualTo(List.of(json));
    }

    @Test
    void oldJsonAndTenFieldConstructorDoNotInventSignalObservations() throws Exception {
        var metadata = mapper.readValue("""
                {"decisionSource":"ROUTING_LLM","rawRoute":"NEWS","route":"NEWS",
                 "confidence":0.9,"needsClarification":false,"reasonCodes":["FUSION_SINGLE_SOURCE"]}
                """, RoutingDecisionMetadata.class);
        var legacy = new RoutingDecisionMetadata(RoutingDecisionSource.DETERMINISTIC_FALLBACK, "DIRECT",
                PlanRoute.DIRECT, "", "", 0.5, List.of(), 0, "EXPLICIT_DETERMINISTIC", 0);

        assertThat(metadata.signalDiagnostics()).isEmpty();
        assertThat(legacy.signalDiagnostics()).isEmpty();
        assertThat(metadata.toAttributes().get("signalDiagnostics")).isEqualTo(List.of());
    }
}
