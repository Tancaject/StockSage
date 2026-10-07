package com.stocksage.agent;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.Locale;
import java.util.Map;

/** 流结束和有正文都不能证明模型正常完成；只认可供应商明确的正常结束原因。 */
public record ModelCompletion(Status status, String finishReason) {
    public enum Status { COMPLETE, TRUNCATED, UNKNOWN, FAILED }

    public static ModelCompletion assess(String finishReason, String content) {
        String reason = finishReason == null ? "" : finishReason.trim();
        Status status = switch (reason.toLowerCase(Locale.ROOT)) {
            case "length", "max_tokens" -> Status.TRUNCATED;
            case "stop" -> content == null || content.isBlank() ? Status.FAILED : Status.COMPLETE;
            case "content_filter", "tool_calls", "tool_call", "function_call" -> Status.FAILED;
            default -> content == null || content.isBlank() ? Status.FAILED : Status.UNKNOWN;
        };
        return new ModelCompletion(status, reason);
    }

    public static String finishReason(ChatResponse response) {
        return response == null || response.getResult() == null ? ""
                : response.getResult().getMetadata().getFinishReason();
    }

    public static Output output(ChatResponse response) {
        String content = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();
        return new Output(content, assess(finishReason(response), content));
    }

    public static ModelCompletion failure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IncompleteOutputException incomplete) return incomplete.completion();
        }
        return new ModelCompletion(Status.FAILED, "");
    }

    public boolean complete() { return status == Status.COMPLETE; }

    public String errorCode() {
        return switch (status) {
            case COMPLETE -> "";
            case TRUNCATED -> "OUTPUT_TRUNCATED";
            case UNKNOWN -> "COMPLETION_UNKNOWN";
            case FAILED -> switch (finishReason.toLowerCase(Locale.ROOT)) {
                case "content_filter" -> "OUTPUT_FILTERED";
                case "tool_calls", "tool_call", "function_call" -> "UNEXPECTED_TOOL_CALL";
                case "stop" -> "EMPTY_ANSWER";
                default -> "OUTPUT_FAILED";
            };
        };
    }

    public String message() {
        return switch (status) {
            case COMPLETE -> "";
            case TRUNCATED -> "模型回答达到输出上限，正文未完成。请缩小问题范围后重新生成。";
            case UNKNOWN -> "模型返回了正文，但没有提供可确认的正常结束原因，无法确认回答完整。请重新生成。";
            case FAILED -> switch (finishReason.toLowerCase(Locale.ROOT)) {
                case "content_filter" -> "模型服务的内容筛选中止了回答。请调整问题后重新生成。";
                case "tool_calls", "tool_call", "function_call" -> "普通回答模型返回了工具调用，未完成正文。请重新生成；若持续出现，请检查模型配置。";
                case "stop" -> "模型正常结束但没有返回正文。请重新生成。";
                default -> "模型未能生成完整回答。请重新生成；若持续失败，请检查模型服务。";
            };
        };
    }

    public Map<String, Object> attributes(String scope) {
        return Map.of("kind", "model-completion", "scope", scope,
                "completionStatus", status.name(), "finishReason", finishReason);
    }

    public record Output(String content, ModelCompletion completion) {}

    public static final class IncompleteOutputException extends IllegalStateException {
        private final ModelCompletion completion;
        public IncompleteOutputException(ModelCompletion completion) {
            super(completion.message());
            this.completion = completion;
        }
        public ModelCompletion completion() { return completion; }
    }
}
