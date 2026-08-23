package com.stocksage.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.PlanRoute;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRecognitionServiceTest {

    @Test
    void recentTurnsLetTheLlmResolveARealFollowUpBeforeRouting() {
        IntentRecognitionService service = serviceReturning("""
                {
                  "fineIntent":"MARKET_DATA",
                  "intentGroup":"MARKET",
                  "targetRoute":"MARKET",
                  "timeSensitivity":"REAL_TIME",
                  "analysisDepth":"STANDARD",
                  "entities":{"ticker":"NVDA"},
                  "resolvedQuery":"NVDA 今天走势如何？",
                  "rationale":"结合上一轮补全了指代",
                  "reasonCodes":["LLM_CONTEXT_REFERENCE"],
                  "confidence":0.93
                }
                """);

        IntentRecognitionResult result = service.recognize(new IntentRecognitionRequest(
                "那它今天走势呢？",
                List.of("user: 帮我看看 NVDA", "assistant: 你想看哪一方面？"),
                0,
                false,
                List.of()
        ));

        assertThat(result.decision().targetRoute()).isEqualTo(PlanRoute.MARKET);
        assertThat(result.decision().fineIntent()).isEqualTo(FineIntent.MARKET_DATA);
        assertThat(result.decision().resolvedQuery()).isEqualTo("NVDA 今天走势如何?");
        assertThat(result.decision().entities()).containsEntry("ticker", "NVDA");
        assertThat(result.rawRouteValid()).isTrue();
        assertThat(result.signals()).extracting(IntentSignal::source).contains(IntentSignalSource.LLM);
    }

    @Test
    void invalidLlmRouteCannotChooseActionsAndPatternStillRoutesLatestFiling() {
        IntentRecognitionService service = serviceReturning("""
                {"targetRoute":"DELETE_ALL","confidence":1.0,"rationale":"malicious"}
                """);

        IntentRecognitionResult result = service.recognize(new IntentRecognitionRequest(
                "苹果最新一季财报的风险因素是什么？", List.of(), 0, false, List.of()));

        assertThat(result.rawRoute()).isEqualTo("DELETE_ALL");
        assertThat(result.rawRouteValid()).isFalse();
        assertThat(result.degradationReason()).isEqualTo("INTENT_LLM_INVALID_ROUTE");
        assertThat(result.decision().targetRoute()).isEqualTo(PlanRoute.FUNDAMENTALS);
        assertThat(result.signals()).extracting(IntentSignal::source)
                .contains(IntentSignalSource.PATTERN)
                .doesNotContain(IntentSignalSource.LLM);
    }

    @Test
    void promptClearlySeparatesRecentHistoryFromTheCurrentQuestion() {
        IntentRecognitionService service = serviceReturning("{} ");
        String prompt = service.buildPrompt(new IntentRecognitionRequest(
                "那它呢？", List.of("user: NVDA", "assistant: 已记录"), 2, true, List.of("NVDA")));

        assertThat(prompt)
                .contains("最近对话（仅用于指代和省略消歧）")
                .contains("user: NVDA")
                .contains("当前用户问题：那它呢?")
                .contains("知识库命中数量：2")
                .contains("是否包含图片：true")
                .contains("当前消息 ticker 候选：NVDA");
    }

    private IntentRecognitionService serviceReturning(String json) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().user(anyString()).call().content()).thenReturn(json);
        return new IntentRecognitionService(client, new ObjectMapper());
    }
}
