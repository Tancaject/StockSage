package com.stocksage.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoordinatorUsageTest {

    @Test
    void observesInvocationAndProviderSnapshotsWithoutChangingTextOrInventingMissingUsage() {
        for (boolean withUsage : List.of(true, false)) {
            ChatModel model = mock(ChatModel.class);
            var chunks = new ArrayList<>(List.of(
                    new ChatResponse(List.of(new Generation(new AssistantMessage("你好")))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage(" ")))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("世界"))))));
            if (withUsage) {
                chunks.add(new ChatResponse(List.of(), ChatResponseMetadata.builder()
                        .model("provider-model-revision")
                        .usage(new DefaultUsage(12, 1, 13)).build()));
                chunks.add(new ChatResponse(List.of(), ChatResponseMetadata.builder()
                        .model("provider-model-revision")
                        .usage(new DefaultUsage(12, 3, 15)).build()));
            }
            when(model.stream(any(Prompt.class))).thenReturn(Flux.fromIterable(chunks));
            Coordinator coordinator = new Coordinator(null, ChatClient.create(model), null, new RoutePlanCatalog());
            ReflectionTestUtils.setField(coordinator, "strongModel", "configured-model");
            ReflectionTestUtils.setField(coordinator, "modelRoutingTemperature", 0.4);
            ReflectionTestUtils.setField(coordinator, "modelRoutingMaxOutputTokens", 2048);
            List<Map<String, Object>> events = new ArrayList<>();

            var answer = coordinator.streamAnswer(List.of(new UserMessage("问候")), true,
                    ModelTier.FAST, false, events::add).collectList().block();

            assertThat(answer).containsExactly("你好", " ", "世界");
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).containsAllEntriesOf(Map.of(
                    "kind", "model-invocation", "schemaVersion", 1, "scope", "final-answer",
                    "modelName", "configured-model", "modelTier", "STRONG",
                    "temperature", 0.4, "maxOutputTokens", 2048));
            assertThat(events.get(1)).containsEntry("kind", "model-usage")
                    .containsEntry("usageSemantics", "INVOCATION_SNAPSHOT")
                    .containsEntry("usageSource", withUsage ? "PROVIDER" : "NO_DATA");
            if (withUsage) {
                assertThat(events.get(1)).containsAllEntriesOf(Map.of(
                        "inputTokens", 12, "outputTokens", 3, "totalTokens", 15,
                        "providerModelName", "provider-model-revision"));
            } else {
                assertThat(events.get(1)).doesNotContainKeys("inputTokens", "outputTokens", "totalTokens");
            }
            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(model).stream(prompt.capture());
            OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
            assertThat(options.getStreamUsage()).isTrue();
            assertThat(options.getModel()).isEqualTo("configured-model");
            assertThat(options.getTemperature()).isEqualTo(0.4);
            assertThat(options.getMaxTokens()).isEqualTo(2048);

            assertThat(coordinator.streamAnswer(List.of(new UserMessage("问候")), true,
                    ModelTier.FAST, false, event -> { throw new IllegalStateException("observer failed"); })
                    .collectList().block()).isEqualTo(answer);
            assertThat(coordinator.streamAnswer(List.of(new UserMessage("问候")), true,
                    ModelTier.FAST, false).collectList().block()).isEqualTo(answer);
        }
    }
}
