package com.stocksage.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrdinaryAnswerReplayServiceTest {
    private final Coordinator coordinator = mock(Coordinator.class);
    private final OrdinaryAnswerReplayService service =
            new OrdinaryAnswerReplayService(coordinator, new ObjectMapper());

    @Test
    void replaysExactTextOnceWithoutToolsAndKeepsIndependentObservations() throws Exception {
        var request = new OrdinaryAnswerReplayService.Request(List.of(
                new OrdinaryAnswerReplayService.PromptMessage("system", "规则\n中文🙂"),
                new OrdinaryAnswerReplayService.PromptMessage("assistant", "上文"),
                new OrdinaryAnswerReplayService.PromptMessage("user", "现在呢？")), ModelTier.STRONG);
        List<Message> capturedMessages = new ArrayList<>();
        Map<String, Object> invocation = Map.of("kind", "model-invocation", "scope", "final-answer",
                "modelTier", "STRONG", "modelName", "configured-model");
        Map<String, Object> firstUsage = Map.of("kind", "model-usage", "scope", "final-answer",
                "usageSource", "PROVIDER", "totalTokens", 10);
        Map<String, Object> finalUsage = Map.of("kind", "model-usage", "scope", "final-answer",
                "usageSource", "PROVIDER", "totalTokens", 12);
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STRONG), eq(false), any()))
                .thenAnswer(call -> {
                    capturedMessages.addAll(call.<List<Message>>getArgument(0));
                    Consumer<Map<String, Object>> observer = call.getArgument(4);
                    return Flux.just("答案", "🙂")
                            .doOnSubscribe(ignored -> observer.accept(invocation))
                            .doOnNext(part -> observer.accept(part.equals("答案") ? firstUsage : finalUsage));
                });

        var result = service.replay(request);

        assertThat(result.schema()).isEqualTo("ordinary_answer_replay_v1");
        assertThat(UUID.fromString(result.replayId())).isNotNull();
        assertThat(result.status()).isEqualTo(OrdinaryAnswerReplayService.Status.COMPLETED);
        assertThat(result.answer()).isEqualTo("答案🙂");
        assertThat(result.errorCode()).isNull();
        assertThat(result.observations()).containsExactly(invocation, firstUsage, finalUsage);
        assertThat(result.durationMs()).isNotNegative();
        assertThat(result.timeoutSeconds()).isEqualTo(120);
        assertThat(capturedMessages).extracting(message -> message.getMessageType().getValue())
                .containsExactly("system", "assistant", "user");
        assertThat(capturedMessages).extracting(Message::getText)
                .containsExactly("规则\n中文🙂", "上文", "现在呢？");
        String canonical = "[{\"role\":\"system\",\"text\":\"规则\\n中文🙂\"},"
                + "{\"role\":\"assistant\",\"text\":\"上文\"},{\"role\":\"user\",\"text\":\"现在呢？\"}]";
        assertThat(result.promptSha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))));
        verify(coordinator, times(1)).streamAnswer(anyList(), eq(false), eq(ModelTier.STRONG), eq(false), any());
    }

    @Test
    void keepsPartialAnswerAndOnlyErrorTypeWithoutRetry() {
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any()))
                .thenReturn(Flux.concat(Flux.just("部分回答"),
                        Flux.error(new IllegalStateException("provider secret must not leave service"))));

        var result = service.replay(request("问题"));

        assertThat(result.status()).isEqualTo(OrdinaryAnswerReplayService.Status.FAILED);
        assertThat(result.answer()).isEqualTo("部分回答");
        assertThat(result.errorCode()).isEqualTo("IllegalStateException");
        assertThat(result.observations()).isEmpty();
        verify(coordinator, times(1)).streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any());
    }

    @Test
    void cancelsTheSingleSubscriptionAtWholeReplayDeadline() {
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1L);
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any()))
                .thenReturn(Flux.concat(Flux.just("部分回答"), Flux.<String>never())
                        .doOnSubscribe(ignored -> subscriptions.incrementAndGet())
                        .doOnCancel(cancellations::incrementAndGet));

        var result = service.replay(request("问题"));

        assertThat(result.status()).isEqualTo(OrdinaryAnswerReplayService.Status.FAILED);
        assertThat(result.answer()).isEqualTo("部分回答");
        assertThat(result.errorCode()).isEqualTo("TIMEOUT");
        assertThat(result.timeoutSeconds()).isEqualTo(1);
        assertThat(subscriptions.get()).isEqualTo(1);
        assertThat(cancellations.get()).isEqualTo(1);
        verify(coordinator, times(1)).streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any());
    }

    @Test
    void rejectsInvalidRolesMessagesAndEffectiveCharacterLimitBeforeCallingModel() {
        ReflectionTestUtils.setField(service, "promptMaxTextChars", 20);
        var user = new OrdinaryAnswerReplayService.PromptMessage("user", "问题");
        List<OrdinaryAnswerReplayService.Request> invalid = List.of(
                new OrdinaryAnswerReplayService.Request(List.of(user), null),
                new OrdinaryAnswerReplayService.Request(null, ModelTier.STANDARD),
                new OrdinaryAnswerReplayService.Request(List.of(), ModelTier.STANDARD),
                new OrdinaryAnswerReplayService.Request(Collections.nCopies(129, user), ModelTier.STANDARD),
                new OrdinaryAnswerReplayService.Request(Collections.singletonList(null), ModelTier.STANDARD),
                new OrdinaryAnswerReplayService.Request(List.of(
                        new OrdinaryAnswerReplayService.PromptMessage("tool", "结果"), user), ModelTier.STANDARD),
                new OrdinaryAnswerReplayService.Request(List.of(
                        new OrdinaryAnswerReplayService.PromptMessage("user", null)), ModelTier.STANDARD),
                new OrdinaryAnswerReplayService.Request(List.of(
                        new OrdinaryAnswerReplayService.PromptMessage("assistant", "回答")), ModelTier.STANDARD),
                request("  \n"), request("x".repeat(21)));

        assertThatThrownBy(() -> service.replay(null)).isInstanceOf(IllegalArgumentException.class);
        for (var request : invalid) {
            assertThatThrownBy(() -> service.replay(request)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(coordinator);
    }

    @Test
    void emptyCompletedStreamIsStillFailed() {
        when(coordinator.streamAnswer(anyList(), eq(false), eq(ModelTier.STANDARD), eq(false), any()))
                .thenReturn(Flux.empty());

        var result = service.replay(request("问题"));

        assertThat(result.status()).isEqualTo(OrdinaryAnswerReplayService.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("EMPTY_ANSWER");
    }

    private OrdinaryAnswerReplayService.Request request(String question) {
        return new OrdinaryAnswerReplayService.Request(List.of(
                new OrdinaryAnswerReplayService.PromptMessage("user", question)), ModelTier.STANDARD);
    }
}
