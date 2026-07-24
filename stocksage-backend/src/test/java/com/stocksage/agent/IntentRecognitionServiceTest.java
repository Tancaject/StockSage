package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRecognitionServiceTest {

    private final IntentRecognitionService service =
            new IntentRecognitionService(null, new ObjectMapper());

    @Test
    void parsesFencedCompositeIntentAndSanitizesEntities() throws Exception {
        IntentDecision decision = service.parseAndValidate("""
                ```json
                {
                  "primaryIntent": "FUNDAMENTALS",
                  "secondaryIntents": ["NEWS_EVENT", "FUNDAMENTALS", "UNKNOWN_VALUE"],
                  "entities": {"ticker": "NVDA", "bad key": "secret text"},
                  "timeRange": "latest quarter",
                  "needsFreshData": true,
                  "needsRag": true,
                  "needsDeepResearch": false,
                  "suggestedRoute": "FUNDAMENTALS",
                  "rationale": "Needs filing evidence and fresh market reaction.",
                  "confidence": 0.91
                }
                ```
                """);

        assertThat(decision.primaryIntent()).isEqualTo(IntentType.FUNDAMENTALS);
        assertThat(decision.secondaryIntents()).containsExactly(IntentType.NEWS_EVENT);
        assertThat(decision.entities()).containsEntry("ticker", "NVDA")
                .doesNotContainKey("bad key");
        assertThat(decision.suggestedRoute()).isEqualTo(PlanRoute.FUNDAMENTALS);
    }

    @Test
    void rejectsUnknownEnumAndDeepRouteMismatch() {
        assertThatThrownBy(() -> service.parseAndValidate("""
                {"primaryIntent":"HACK","suggestedRoute":"NEWS"}
                """)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.parseAndValidate("""
                {
                  "primaryIntent":"DEEP_RESEARCH",
                  "secondaryIntents":[],
                  "needsDeepResearch":true,
                  "suggestedRoute":"MARKET"
                }
                """)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestBoundsRecentContextAndTickerCandidates() {
        IntentRecognitionRequest request = new IntentRecognitionRequest(
                "follow up",
                List.of("1", "2", "3", "4", "5"),
                -1,
                false,
                List.of("A", "B", "C", "D", "E", "F")
        );

        assertThat(request.recentContext()).containsExactly("1", "2", "3", "4");
        assertThat(request.tickerCandidates()).hasSize(5);
        assertThat(request.ragHitCount()).isZero();
    }

    @Test
    void fallsBackOnEmptyResponseAndNetworkFailure() {
        ChatClient emptyClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(emptyClient.prompt().user(anyString()).call().content()).thenReturn("");
        IntentRecognitionService emptyService = new IntentRecognitionService(emptyClient, new ObjectMapper());

        ChatClient failingClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(failingClient.prompt().user(anyString()).call().content())
                .thenThrow(new IllegalStateException("network unavailable"));
        IntentRecognitionService failingService = new IntentRecognitionService(failingClient, new ObjectMapper());

        IntentRecognitionRequest request =
                new IntentRecognitionRequest("NVDA news", List.of(), 0, false, List.of("NVDA"));
        assertThat(emptyService.recognize(request)).isNull();
        assertThat(failingService.recognize(request)).isNull();
    }

    @Test
    void fallsBackWhenIntentModelTimesOut() {
        ChatClient slowClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(slowClient.prompt().user(anyString()).call().content()).thenAnswer(invocation -> {
            Thread.sleep(300L);
            return "{}";
        });
        IntentRecognitionService slowService = new IntentRecognitionService(
                slowClient,
                new ObjectMapper(),
                new SimpleAsyncTaskExecutor(),
                100L
        );

        IntentDecision decision = slowService.recognize(
                new IntentRecognitionRequest("NVDA news", List.of(), 0, false, List.of("NVDA"))
        );

        assertThat(decision).isNull();
    }
}
