package com.stocksage.agent;

import com.stocksage.model.dto.ModelInvocationContext;
import com.stocksage.research.ModelInvocationStore;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.tool.ToolCallContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Records invocation facts and enforces the run deadline without changing prompt or options. */
public final class ModelInvocationAdvisor implements CallAdvisor, StreamAdvisor {
    public static final String CONTEXT_KEY = "stocksage.invocation";
    private final String role;
    private final ModelInvocationStore store;

    public ModelInvocationAdvisor(String role, ModelInvocationStore store) {
        this.role = role;
        this.store = store;
    }

    @Override public String getName() { return "research-invocation-" + role; }
    @Override public int getOrder() { return Integer.MAX_VALUE - 1; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!(request.context().get(CONTEXT_KEY) instanceof ModelInvocationContext context)) {
            return chain.nextCall(request);
        }
        context.remainingMillis();
        Observation observation = new Observation(store.begin(context, role, fingerprint(request)));
        try {
            context.remainingMillis();
            ChatClientResponse response = ToolCallContext.withRunDeadline(
                    new ToolCallContext.RunDeadline(context.runId(), context.deadlineEpochMs()),
                    () -> chain.nextCall(request));
            observation.accept(response);
            context.remainingMillis();
            observation.finish("SUCCEEDED");
            return response;
        } catch (RuntimeException failure) {
            observation.finish("FAILED");
            context.remainingMillis();
            throw failure;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!(request.context().get(CONTEXT_KEY) instanceof ModelInvocationContext context)) {
            return chain.nextStream(request);
        }
        return Flux.defer(() -> {
            context.remainingMillis();
            Observation observation = new Observation(store.begin(context, role, fingerprint(request)));
            return Flux.defer(() -> {
                        long remaining = context.remainingMillis();
                        AtomicBoolean deadlineReached = new AtomicBoolean();
                        // One absolute timer per invocation; emitted tokens cannot renew the run deadline.
                        return chain.nextStream(request).takeUntilOther(Mono.delay(Duration.ofMillis(remaining))
                                        .doOnNext(ignored -> deadlineReached.set(true)))
                                // The timer's normal signal cancels upstream before exposing the budget failure.
                                .concatWith(Mono.defer(() -> deadlineReached.get()
                                        ? Mono.error(new ResearchBudgetExceededException(context.runId(),
                                                ResearchBudgetExceededException.Reason.DEADLINE))
                                        : Mono.empty()));
                    })
                    .doOnNext(ignored -> context.remainingMillis())
                    .doOnNext(observation::accept)
                    .doOnComplete(() -> observation.finish("SUCCEEDED"))
                    .doOnError(error -> observation.finish("FAILED"))
                    .doOnCancel(() -> observation.finish("CANCELLED"));
        });
    }

    private Map<String, Object> fingerprint(ChatClientRequest request) {
        StringBuilder text = new StringBuilder();
        long textBytes = 0;
        for (var message : request.prompt().getInstructions()) {
            String content = message.getText() == null ? "" : message.getText();
            textBytes = Math.addExact(textBytes, content.getBytes(StandardCharsets.UTF_8).length);
            text.append(message.getMessageType()).append(':').append(content.length()).append(':').append(content);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("promptHashScope", "ORDERED_TEXT_MESSAGES");
        values.put("inputTextUtf8Bytes", textBytes);
        values.put("inputMessageCount", request.prompt().getInstructions().size());
        values.put("inputTokenCountStatus", "NOT_COUNTED");
        // SDK defaults and provider message framing are resolved after this advisor.
        values.put("optionsScope", "CHAT_CLIENT_REQUEST_OPTIONS");
        values.put("totalOutputTokenCeilingStatus", "UNVERIFIED");
        try {
            values.put("promptSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        var options = request.prompt().getOptions();
        if (options != null) {
            if (options.getModel() != null) values.put("requestedModel", options.getModel());
            if (options.getTemperature() != null) values.put("temperature", options.getTemperature());
            if (options.getMaxTokens() != null) values.put("maxTokens", options.getMaxTokens());
            if (options instanceof OpenAiChatOptions openAi) {
                values.put("toolsPresent", (openAi.getToolNames() != null && !openAi.getToolNames().isEmpty())
                        || (openAi.getToolCallbacks() != null && !openAi.getToolCallbacks().isEmpty()));
                if (openAi.getMaxCompletionTokens() != null) values.put("maxCompletionTokens", openAi.getMaxCompletionTokens());
                if (openAi.getN() != null) values.put("n", openAi.getN());
                if (openAi.getReasoningEffort() != null) values.put("reasoningEffort", openAi.getReasoningEffort());
                var extra = openAi.getExtraBody();
                values.put("extraBodyPresent", extra != null && !extra.isEmpty());
                if (extra != null) {
                    // Do not persist arbitrary extraBody data: it may contain private provider settings.
                    if (extra.get("enable_thinking") instanceof Boolean enabled) values.put("enableThinking", enabled);
                    if (extra.get("thinking_budget") instanceof Integer budget) values.put("thinkingBudget", budget);
                }
            }
        }
        return Map.copyOf(values);
    }

    private final class Observation {
        private final String id;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile Map<String, Object> response = Map.of(
                "usageSource", "NO_DATA", "usageSemantics", "INVOCATION_SNAPSHOT");

        private Observation(String id) { this.id = id; }

        private void accept(ChatClientResponse value) {
            if (value == null || value.chatResponse() == null) return;
            var metadata = value.chatResponse().getMetadata();
            Map<String, Object> latest = new LinkedHashMap<>(response);
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                latest.put("actualModel", metadata.getModel());
            }
            var usage = metadata.getUsage();
            if (usage != null && !(usage instanceof EmptyUsage)) {
                latest.remove("inputTokens");
                latest.remove("outputTokens");
                latest.remove("totalTokens");
                // DefaultUsage turns missing fields into zero or a calculated total. Only native
                // fields establish provider usage and may release a durable token reservation.
                if (usage.getNativeUsage() instanceof org.springframework.ai.openai.api.OpenAiApi.Usage nativeUsage) {
                    latest.put("usageSource", "PROVIDER");
                    if (nativeUsage.promptTokens() != null) latest.put("inputTokens", nativeUsage.promptTokens());
                    if (nativeUsage.completionTokens() != null) latest.put("outputTokens", nativeUsage.completionTokens());
                    if (nativeUsage.totalTokens() != null) latest.put("totalTokens", nativeUsage.totalTokens());
                } else {
                    latest.put("usageSource", "SDK_NORMALIZED");
                }
            }
            response = Map.copyOf(latest);
        }

        private void finish(String status) {
            if (terminal.compareAndSet(false, true)) store.finish(id, status, response);
        }
    }
}
