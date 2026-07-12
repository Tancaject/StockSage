package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.service.DeepResearchPipeline;
import com.stocksage.tool.ChatStreamEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchManagerStreamingGuardTest {

    @Test
    void ownershipGuardCancelsManagerStreamBeforeASecondTokenIsEmitted() {
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        Flux<String> tokens = Flux.<String, Integer>generate(
                        () -> 0,
                        (index, sink) -> {
                            sink.next(index == 0 ? "first" : "second");
                            return index + 1;
                        })
                .doOnCancel(() -> upstreamCancelled.set(true));
        ChatClient chatClient = chatClientReturning(tokens);
        ChatStreamEmitter emitter = mock(ChatStreamEmitter.class);
        doAnswer(invocation -> {
            if ("first".equals(invocation.getArgument(5, String.class))) {
                ownershipLost.set(true);
            }
            return null;
        }).when(emitter).emitSection(any(), any(), any(), any(), any(), any());
        ResearchManager manager = new ResearchManager(chatClient, new ObjectMapper());
        Runnable guard = () -> {
            if (ownershipLost.get()) {
                throw new DeepResearchPipeline.OwnershipLostException("lost");
            }
        };

        assertThatThrownBy(() -> manager.synthesizeStreaming(
                AnalysisState.builder().query("q").build(), "trace", 1L, emitter, guard).block())
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(emitter).emitSection(any(), any(), any(), any(), any(), eq("first"));
        verify(emitter, never()).emitSection(any(), any(), any(), any(), any(), eq("second"));
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    void guardRunsAgainBeforeParsingBufferedJson() throws Exception {
        ObjectMapper objectMapper = spy(new ObjectMapper());
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.just("{\"recommendation\":\"BUY\"}")), objectMapper);
        AtomicInteger checks = new AtomicInteger();
        Runnable guard = () -> {
            if (checks.incrementAndGet() > 1) {
                throw new DeepResearchPipeline.OwnershipLostException("lost before parse");
            }
        };

        assertThatThrownBy(() -> manager.synthesizeStreaming(
                AnalysisState.builder().query("q").build(), "trace", 1L,
                mock(ChatStreamEmitter.class), guard).block())
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(objectMapper, never()).readTree(anyString());
    }

    @Test
    void originalStreamingOverloadStillParsesAReportWithoutAGuard() {
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.just(
                        "{\"recommendation\":\"BUY\",\"analystSummary\":\"ok\"}")),
                new ObjectMapper());

        InvestmentReport report = manager.synthesizeStreaming(
                AnalysisState.builder().query("q").build(), "trace", 1L,
                mock(ChatStreamEmitter.class)).block();

        assertThat(report).isNotNull();
        assertThat(report.getRecommendation()).isEqualTo("BUY");
    }

    private ChatClient chatClientReturning(Flux<String> tokens) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).stream().content()).thenReturn(tokens);
        return chatClient;
    }
}
