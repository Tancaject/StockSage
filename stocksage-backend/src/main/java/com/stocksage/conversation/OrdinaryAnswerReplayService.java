package com.stocksage.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.ModelCompletion;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** 管理评估入口复用当前部署的普通最终回答模型，不执行取证或会话持久化。 */
@Service
@RequiredArgsConstructor
public class OrdinaryAnswerReplayService {
    private final Coordinator coordinator;
    private final ObjectMapper objectMapper;

    @Value("${stocksage.chat.prompt.max-text-chars:24000}")
    private int promptMaxTextChars = 24000;

    @Value("${stocksage.agent.prefetch.timeout-seconds:120}")
    private long timeoutSeconds = 120;

    public Result replay(Request request) {
        List<Message> messages = validateMessages(request);
        String promptSha256 = promptHash(request.messages());
        String replayId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        StringBuffer answer = new StringBuffer();
        List<Map<String, Object>> observations = Collections.synchronizedList(new ArrayList<>());
        Status status = Status.COMPLETED;
        AtomicReference<ModelCompletion> completion = new AtomicReference<>(new ModelCompletion(ModelCompletion.Status.UNKNOWN, ""));
        String errorCode = null;
        try {
            // then() 的 timeout 覆盖整次生成，而非每个 chunk 重置的空闲超时；超时会取消上游订阅。
            Flux.defer(() -> coordinator.streamAnswer(messages, false, request.modelTier(), false,
                            event -> observations.add(Collections.unmodifiableMap(new LinkedHashMap<>(event))), completion::set))
                    .doOnNext(answer::append)
                    .then()
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();
        } catch (RuntimeException error) {
            status = Status.FAILED;
            Throwable failure = Exceptions.unwrap(error);
            errorCode = failure instanceof TimeoutException ? "TIMEOUT"
                    : failure instanceof ModelCompletion.IncompleteOutputException ? completion.get().errorCode()
                    : failure.getClass().getSimpleName();
        }
        List<Map<String, Object>> captured;
        synchronized (observations) {
            captured = List.copyOf(observations);
        }
        return new Result("ordinary_answer_replay_v1", replayId, status, answer.toString(), promptSha256,
                captured, Duration.ofNanos(System.nanoTime() - startedAt).toMillis(), errorCode, timeoutSeconds, completion.get());
    }

    private List<Message> validateMessages(Request request) {
        if (request == null || request.modelTier() == null) {
            throw new IllegalArgumentException("普通回答回放缺少 modelTier，请指定 FAST、STANDARD 或 STRONG。");
        }
        if (request.messages() == null || request.messages().isEmpty() || request.messages().size() > 128) {
            throw new IllegalArgumentException("普通回答回放需要 1 至 128 条文本消息，请检查 messages。");
        }
        List<Message> messages = new ArrayList<>();
        long chars = 0;
        for (PromptMessage message : request.messages()) {
            if (message == null || message.role() == null || message.text() == null) {
                throw new IllegalArgumentException("普通回答回放消息缺少 role 或 text，请提供完整的文本消息。");
            }
            chars += message.text().length();
            Message converted = switch (message.role()) {
                case "system" -> new SystemMessage(message.text());
                case "user" -> new UserMessage(message.text());
                case "assistant" -> new AssistantMessage(message.text());
                default -> throw new IllegalArgumentException("普通回答回放只接受 system、user、assistant 文本角色。");
            };
            messages.add(converted);
        }
        if (chars > promptMaxTextChars) {
            throw new IllegalArgumentException("普通回答回放文本超过当前 " + promptMaxTextChars
                    + " 字符上限，请减少消息内容后重试。");
        }
        PromptMessage current = request.messages().get(request.messages().size() - 1);
        if (!"user".equals(current.role()) || current.text().isBlank()) {
            throw new IllegalArgumentException("普通回答回放末条消息必须是非空 user 消息，请补充当前问题。");
        }
        return List.copyOf(messages);
    }

    private String promptHash(List<PromptMessage> messages) {
        List<Map<String, String>> rendered = messages.stream().map(message -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("role", message.role());
            row.put("text", message.text());
            return row;
        }).toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(rendered).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("普通回答回放无法计算输入指纹，请检查服务配置后重试。", error);
        }
    }

    public record PromptMessage(String role, String text) {}

    public record Request(List<PromptMessage> messages, ModelTier modelTier) {}

    public enum Status { COMPLETED, FAILED }

    public record Result(String schema, String replayId, Status status, String answer, String promptSha256,
                         List<Map<String, Object>> observations, long durationMs, String errorCode,
                         long timeoutSeconds, ModelCompletion completion) {}
}
