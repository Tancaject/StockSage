package com.stocksage.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.config.AiConfig;
import com.stocksage.config.ModelTokenBudgetProperties;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Temporary model-only comparison using the existing captured-message replay; no stores or tools. */
public final class ModelComparisonSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception failure) {
            try { System.out.println(JSON.writeValueAsString(Map.of("status", "FAILED", "causes", safeFailure(failure)))); }
            catch (Exception ignored) { System.out.println("{\"status\":\"FAILED\"}"); }
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Map<String, String> arguments = new HashMap<>();
        boolean execute = false;
        for (int i = 0; i < args.length; i++) {
            if ("--run".equals(args[i])) { require(!execute); execute = true; continue; }
            require(Set.of("--before", "--after", "--output").contains(args[i]) && i + 1 < args.length);
            require(arguments.put(args[i], args[++i]) == null);
        }
        require(arguments.keySet().equals(Set.of("--before", "--after", "--output")));
        Path beforePath = Path.of(arguments.get("--before")).toAbsolutePath().normalize();
        Path afterPath = Path.of(arguments.get("--after")).toAbsolutePath().normalize();
        Path output = Path.of(arguments.get("--output")).toAbsolutePath().normalize();
        byte[] beforeBytes = Files.readAllBytes(beforePath), afterBytes = Files.readAllBytes(afterPath);
        JsonNode beforeCapture = JSON.readTree(beforeBytes), afterCapture = JSON.readTree(afterBytes);
        require("before".equals(beforeCapture.path("variant").asText()) && "after".equals(afterCapture.path("variant").asText()));
        Map<String, JsonNode> before = cases(beforeCapture);
        Map<String, JsonNode> after = cases(afterCapture);
        require(before.keySet().equals(after.keySet()) && before.size() * 2 <= 16);
        List<Map<String, Object>> calls = new ArrayList<>();
        int caseIndex = 0;
        for (String id : before.keySet()) {
            JsonNode first = before.get(id), second = after.get(id);
            require(first.get("input").equals(second.get("input")));
            require(first.get("messages").equals(second.get("messages")));
            require("qwen3.7-plus".equals(first.path("options").path("model").asText()));
            require("qwen3.8-max".equals(second.path("options").path("model").asText()));
            var firstOptions = ((com.fasterxml.jackson.databind.node.ObjectNode) first.get("options")).deepCopy();
            var secondOptions = ((com.fasterxml.jackson.databind.node.ObjectNode) second.get("options")).deepCopy();
            firstOptions.remove("model"); secondOptions.remove("model");
            require(firstOptions.equals(secondOptions));
            require(first.path("role").equals(second.path("role")) && first.path("criteria").equals(second.path("criteria"))
                    && first.path("dataKind").equals(second.path("dataKind")));
            for (String arm : caseIndex++ % 2 == 0 ? List.of("before", "after") : List.of("after", "before")) {
                JsonNode sample = ("before".equals(arm) ? before : after).get(id);
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("callId", UUID.randomUUID().toString());
                call.put("caseId", id);
                call.put("arm", arm);
                call.put("role", sample.path("role"));
                call.put("dataKind", sample.path("dataKind"));
                call.put("criteria", sample.path("criteria"));
                call.put("input", sample.get("input"));
                call.put("messages", sample.get("messages"));
                call.put("options", sample.get("options"));
                call.put("promptSha256", sha(JSON.writeValueAsBytes(sample.get("messages"))));
                calls.add(call);
            }
        }
        if (!execute) {
            System.out.println(JSON.writeValueAsString(Map.of("status", "VALIDATED", "cases", before.size(),
                    "plannedCalls", calls.size(), "modelCalls", 0, "beforeSha256", sha(beforeBytes), "afterSha256", sha(afterBytes))));
            return;
        }
        Files.createDirectory(output);
        Files.write(output.resolve("capture-before.json"), beforeBytes, StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("capture-after.json"), afterBytes, StandardOpenOption.CREATE_NEW);
        write(output.resolve("plan.json"), Map.of("schemaVersion", 1, "createdAt", Instant.now().toString(),
                "scope", "PAIRED_MODEL_ONLY_COMPARISON", "beforeSha256", sha(beforeBytes), "afterSha256", sha(afterBytes),
                "calls", calls, "maxCalls", 16, "retryAttempts", 1));

        try (var context = new AnnotationConfigApplicationContext()) {
            var environment = context.getEnvironment();
            environment.getPropertySources().addLast(properties("application", Path.of("src/main/resources/application.properties")));
            Path local = Path.of("src/main/resources/application-local.properties");
            if (Files.isRegularFile(local)) environment.getPropertySources().addBefore("application", properties("application-local", local));
            context.register(EvolutionLiveBaselineTest.HttpTransportConfiguration.class);
            context.refresh();
            Binder binder = Binder.get(environment);
            var common = binder.bind("spring.ai.openai", OpenAiConnectionProperties.class).get();
            var chat = binder.bind("spring.ai.openai.chat", OpenAiChatProperties.class).get();
            require(environment.getProperty("spring.ai.retry.max-attempts", Integer.class, 1) == 1);
            var tokenBudget = binder.bind("stocksage.research.token-budget", ModelTokenBudgetProperties.class)
                    .orElse(new ModelTokenBudgetProperties(null, null, null, null, null, null, Map.of()));
            var runtime = new AgentRuntimeConfiguration();
            var ai = new AiConfig(runtime);
            context.getAutowireCapableBeanFactory().autowireBean(ai);
            var api = ai.openAiApi(common, chat, context.getBeanProvider(RestClient.Builder.class),
                    context.getBeanProvider(WebClient.Builder.class), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER, tokenBudget);
            var defaults = chat.getOptions();
            require((defaults.getN() == null || defaults.getN() == 1)
                    && (defaults.getToolNames() == null || defaults.getToolNames().isEmpty())
                    && (defaults.getToolCallbacks() == null || defaults.getToolCallbacks().isEmpty())
                    && (defaults.getExtraBody() == null || defaults.getExtraBody().isEmpty()));
            var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(defaults)
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            var http = context.getBean(ClientHttpRequestFactorySettings.class);
            write(output.resolve("runtime.json"), Map.of("startedAt", Instant.now().toString(),
                    "provider", runtime.chatProviderSnapshot(), "retryAttempts", 1,
                    "scope", "CAPTURED_MESSAGES_ONLY_NO_TOOLS_NO_STORAGE", "buildAttestation", "UNATTESTED_LOCAL_CLASSES",
                    "httpReadTimeout", Objects.toString(http.readTimeout(), "UNSET"),
                    "httpConnectTimeout", Objects.toString(http.connectTimeout(), "UNSET")));
            int completed = 0;
            for (Map<String, Object> call : calls) {
                String callId = call.get("callId").toString();
                write(output.resolve(callId + ".request.json"), call);
                long started = System.nanoTime();
                try {
                    JsonNode options = (JsonNode) call.get("options");
                    var requestOptions = OpenAiChatOptions.builder().model(options.get("model").asText())
                            .temperature(options.get("temperature").doubleValue())
                            .maxTokens(options.get("maxTokens").intValue()).build();
                    requestOptions.setTopP(numberOrNull(options.get("topP")));
                    requestOptions.setFrequencyPenalty(numberOrNull(options.get("frequencyPenalty")));
                    requestOptions.setPresencePenalty(numberOrNull(options.get("presencePenalty")));
                    if (!options.get("stopSequences").isNull()) {
                        List<String> stop = new ArrayList<>(); options.get("stopSequences").forEach(value -> stop.add(value.asText()));
                        requestOptions.setStopSequences(stop);
                    }
                    ChatResponse response = model.call(new Prompt(messages((JsonNode) call.get("messages")), requestOptions));
                    Map<String, Object> record = response(response);
                    record.put("callId", callId);
                    record.put("caseId", call.get("caseId"));
                    record.put("arm", call.get("arm"));
                    record.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                    record.put("finishedAt", Instant.now().toString());
                    write(output.resolve(callId + ".response.json"), record);
                    require(response != null && response.getResult() != null && response.getResult().getOutput() != null
                            && response.getResult().getOutput().getText() != null && !response.getResult().getOutput().getText().isBlank());
                    completed++;
                    System.out.println(JSON.writeValueAsString(Map.of("status", "COMPLETED_CALL", "callId", callId, "completedCalls", completed)));
                } catch (Exception failure) {
                    write(output.resolve(callId + ".failure.json"), Map.of("callId", callId, "causes", safeFailure(failure),
                            "at", Instant.now().toString(), "elapsedMs", (System.nanoTime() - started) / 1_000_000,
                            "providerCompletion", "UNKNOWN", "retry", false));
                    throw failure;
                }
            }
            write(output.resolve("completion.json"), Map.of("status", "COMPLETED", "modelCalls", completed, "finishedAt", Instant.now().toString()));
            System.out.println(JSON.writeValueAsString(Map.of("status", "COMPLETED", "modelCalls", completed)));
        }
    }

    private static Map<String, JsonNode> cases(JsonNode capture) {
        require(capture != null && capture.path("schemaVersion").asInt() == 1 && capture.path("cases").isArray());
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode sample : capture.get("cases")) {
            require(sample.path("caseId").isTextual() && !sample.get("caseId").asText().isBlank());
            require(sample.hasNonNull("input") && sample.path("options").isObject());
            JsonNode options = sample.get("options");
            Set<String> optionFields = new HashSet<>(); options.fieldNames().forEachRemaining(optionFields::add);
            require(optionFields.equals(Set.of("model", "temperature", "maxTokens", "topP", "frequencyPenalty", "presencePenalty", "stopSequences")));
            require(options.path("model").isTextual() && !options.get("model").asText().isBlank());
            require(options.path("temperature").isNumber() && Double.isFinite(options.get("temperature").doubleValue())
                    && options.get("temperature").doubleValue() >= 0 && options.get("temperature").doubleValue() <= 2);
            require(options.path("maxTokens").isIntegralNumber() && options.get("maxTokens").canConvertToInt()
                    && options.get("maxTokens").intValue() > 0);
            for (String name : List.of("topP", "frequencyPenalty", "presencePenalty")) {
                JsonNode value = options.get(name);
                require(value.isNull() || (value.isNumber() && Double.isFinite(value.doubleValue())));
            }
            JsonNode stop = options.get("stopSequences");
            require(stop.isNull() || stop.isArray());
            if (stop.isArray()) stop.forEach(value -> require(value.isTextual()));
            messages(sample.get("messages"));
            require(result.put(sample.get("caseId").asText(), sample) == null);
        }
        require(!result.isEmpty() && result.size() <= 8);
        return result;
    }

    private static List<Message> messages(JsonNode values) {
        require(values != null && values.isArray() && !values.isEmpty());
        List<Message> messages = new ArrayList<>();
        for (JsonNode value : values) {
            require(value.path("text").isTextual() && value.path("role").isTextual());
            messages.add(switch (value.get("role").asText()) {
                case "system" -> new SystemMessage(value.get("text").asText());
                case "user" -> new UserMessage(value.get("text").asText());
                case "assistant" -> new AssistantMessage(value.get("text").asText());
                default -> throw new IllegalArgumentException();
            });
        }
        return messages;
    }

    private static Map<String, Object> response(ChatResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usageSource", "NO_DATA");
        result.put("usageSemantics", "INVOCATION_SNAPSHOT");
        if (response == null) return result;
        var metadata = response.getMetadata();
        result.put("actualModel", metadata.getModel());
        result.put("providerResponseId", metadata.getId());
        if (metadata.getUsage() != null && !(metadata.getUsage() instanceof EmptyUsage)) {
            if (metadata.getUsage().getNativeUsage() instanceof OpenAiApi.Usage usage) {
                result.put("usageSource", "PROVIDER");
                result.put("inputTokens", usage.promptTokens());
                result.put("outputTokens", usage.completionTokens());
                result.put("totalTokens", usage.totalTokens());
            } else result.put("usageSource", "SDK_NORMALIZED");
        }
        if (response.getResult() != null) {
            result.put("finishReason", response.getResult().getMetadata().getFinishReason());
            if (response.getResult().getOutput() != null) result.put("text", response.getResult().getOutput().getText());
        }
        return result;
    }

    private static List<Map<String, Object>> safeFailure(Throwable failure) {
        List<Map<String, Object>> causes = new ArrayList<>();
        for (Throwable current = failure; current != null && causes.size() < 10; current = current.getCause()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", current.getClass().getName());
            if (current instanceof RestClientResponseException http) item.put("httpStatus", http.getStatusCode().value());
            else if (current instanceof WebClientResponseException http) item.put("httpStatus", http.getStatusCode().value());
            causes.add(item);
        }
        return causes;
    }

    private static PropertiesPropertySource properties(String name, Path path) throws Exception {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { values.load(reader); }
        return new PropertiesPropertySource(name, values);
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static Double numberOrNull(JsonNode value) { return value.isNull() ? null : value.doubleValue(); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException(); }
    private static void write(Path file, Object value) throws Exception {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
