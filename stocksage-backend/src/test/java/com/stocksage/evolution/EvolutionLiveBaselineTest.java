package com.stocksage.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.RoutePlanCatalog;
import com.stocksage.agent.intent.IntentRecognitionService;
import com.stocksage.config.AgentConfig;
import com.stocksage.config.AiConfig;
import com.stocksage.config.ModelTokenBudgetProperties;
import com.stocksage.config.RuntimeArtifactIdentity;
import com.stocksage.conversation.ChatPromptAssembler;
import com.stocksage.conversation.OrdinaryAnswerReplayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Explicit live-model baseline; ordinary test runs never submit a provider request. */
class EvolutionLiveBaselineTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    @EnabledIfSystemProperty(named = "evolution.live.execution", matches = ".+")
    void runRegisteredBaselineThroughRealComponents() throws Exception {
        Path execution = Path.of(System.getProperty("evolution.live.execution")).toAbsolutePath();
        String destination = System.getProperty("evolution.live.output", "");
        assertFalse(destination.isBlank(), "Set evolution.live.output to a new output directory.");
        Path output = Path.of(destination).toAbsolutePath();
        var declared = json.readValue(Files.readAllBytes(execution), EvolutionReplayService.ExecutionFile.class);
        assertFalse(declared.runs().isEmpty(), "Execution must register at least one run.");
        for (var run : declared.runs()) {
            assertEquals(AgentPolicyBundle.BASELINE_ID, run.bundleId(), "This entry runs the baseline only.");
        }
        Files.createDirectory(output);
        Files.copy(execution, output.resolve("execution.json"));

        var worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "evolution-live-analyst");
            thread.setDaemon(true);
            return thread;
        });
        try (var context = new AnnotationConfigApplicationContext()) {
            var environment = context.getEnvironment();
            environment.getPropertySources().addLast(properties("application", Path.of("src/main/resources/application.properties")));
            Path local = Path.of("src/main/resources/application-local.properties");
            if (Files.isRegularFile(local)) {
                environment.getPropertySources().addBefore("application", properties("application-local", local));
            }
            context.refresh();
            Binder binder = Binder.get(environment);
            var common = binder.bind("spring.ai.openai", OpenAiConnectionProperties.class).get();
            var chat = binder.bind("spring.ai.openai.chat", OpenAiChatProperties.class).get();
            int attempts = environment.getProperty("spring.ai.retry.max-attempts", Integer.class, 1);
            assertEquals(1, attempts, "Live baseline requires the production no-retry setting.");
            var tokenBudget = binder.bind("stocksage.research.token-budget", ModelTokenBudgetProperties.class)
                    .orElse(new ModelTokenBudgetProperties(null, null, null, null, null, null, Map.of()));
            var runtime = new AgentRuntimeConfiguration();
            var ai = configure(context, new AiConfig(runtime));
            var api = ai.openAiApi(common, chat, context.getBeanProvider(RestClient.Builder.class),
                    context.getBeanProvider(WebClient.Builder.class), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER, tokenBudget);
            var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(chat.getOptions())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(attempts).build()).build();
            ChatClient.Builder builder = ChatClient.builder(observeFailures(model, output, common.getApiKey(), chat.getApiKey()));
            ai.chatConcurrencyCustomizer(environment.getProperty("stocksage.chat.max-concurrent-calls", Integer.class, 8))
                    .customize(builder);

            // No DEEP invocation context is attached, so the existing advisor never enters its storage branch.
            var agents = configure(context, new AgentConfig(runtime, null));
            ChatClient fundamentals = agents.fundamentalsAgentChatClient(builder);
            agents.marketAgentChatClient(builder);
            agents.newsAgentChatClient(builder);
            agents.bullResearcherChatClient(builder);
            agents.bearResearcherChatClient(builder);
            agents.researchManagerChatClient(builder);
            agents.researchManagerScoringChatClient(builder);
            agents.researchManagerContinuationChatClient(builder);
            agents.deepEvidenceReplannerChatClient(builder);
            ai.queryRewriteChatClient(builder);
            ai.contextualGistChatClient(builder);
            var methods = new FundamentalsMethodRegistry(AgentPolicyBundle.BASELINE_ID);
            var analyst = new FundamentalsAgent(fundamentals, methods, runtime);
            // The replay calls only streamAnswer; routing and ticker/data services are never invoked.
            var coordinator = configure(context, new Coordinator((IntentRecognitionService) null,
                    ai.chatClient(builder), null, new RoutePlanCatalog()));
            var assembler = configure(context, new ChatPromptAssembler());
            var answer = configure(context, new OrdinaryAnswerReplayService(coordinator, json));
            long timeout = environment.getProperty("stocksage.agent.prefetch.timeout-seconds", Long.class, 120L);
            var artifact = new RuntimeArtifactIdentity();
            var service = new EvolutionReplayService(analyst, methods, runtime, assembler, answer,
                    new TaskExecutorAdapter(worker), json, artifact, "", execution.toString(), "", timeout,
                    assembler.promptMaxTextChars());
            write(output.resolve("runtime.json"), Map.of("startedAt", Instant.now().toString(),
                    "provider", runtime.chatProviderSnapshot(), "clients", runtime.snapshot(),
                    "stageTimeoutSeconds", timeout, "promptMaxTextChars", assembler.promptMaxTextChars(),
                    "retryAttempts", attempts, "runtimeArtifact", artifact.snapshot(), "buildAttestation", "UNATTESTED",
                    "scope", "LIVE_MODEL_FROZEN_COMPONENT_CHAIN"));
            for (var run : declared.runs()) {
                var request = new EvolutionReplayService.Request(run.caseId(), run.bundleId(), run.runId());
                write(output.resolve(run.runId() + ".request.json"), request);
                EvolutionReplayService.Result response;
                try {
                    response = service.replay(request);
                } catch (RuntimeException failure) {
                    write(output.resolve(run.runId() + ".failure.json"), Map.of("errorType", failure.getClass().getSimpleName(),
                            "at", Instant.now().toString(), "providerCompletion", "UNKNOWN", "retry", false));
                    throw new AssertionError("Replay stopped at " + run.runId() + "; inspect saved artifacts. Error type: "
                            + failure.getClass().getSimpleName());
                }
                write(output.resolve(run.runId() + ".response.json"), response);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("request", request);
                row.put("response", response);
                Files.writeString(output.resolve("responses.jsonl"), json.writeValueAsString(row) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                System.out.println("evolution-live run=" + run.runId() + " status=" + response.status());
                assertEquals("COMPLETED", response.status(), "Batch stopped; request, response and available usage are retained at " + output);
            }
        } finally {
            worker.shutdownNow();
        }
    }

    private static PropertiesPropertySource properties(String name, Path path) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new PropertiesPropertySource(name, properties);
    }

    private ChatModel observeFailures(ChatModel delegate, Path output, String... credentials) {
        return new ChatModel() {
            @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
            @Override public ChatResponse call(Prompt prompt) {
                try {
                    return delegate.call(prompt);
                } catch (RuntimeException failure) {
                    record(failure, "CALL");
                    throw failure;
                }
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> delegate.stream(prompt)).doOnError(failure -> record(failure, "STREAM"));
            }
            private void record(Throwable failure, String operation) {
                try {
                    Files.writeString(output.resolve("provider-error.jsonl"), json.writeValueAsString(Map.of(
                                    "at", Instant.now().toString(), "operation", operation,
                                    "causes", providerFailure(failure, credentials))) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception diagnosticFailure) {
                    failure.addSuppressed(new IllegalStateException("Provider failure diagnostic could not be saved."));
                }
            }
        };
    }

    private java.util.List<Map<String, Object>> providerFailure(Throwable failure, String... credentials) {
        var causes = new ArrayList<Map<String, Object>>();
        for (Throwable current = failure; current != null && causes.size() < 10; current = current.getCause()) {
            var cause = new LinkedHashMap<String, Object>();
            cause.put("type", current.getClass().getName());
            String body = "";
            if (current instanceof RestClientResponseException http) {
                cause.put("httpStatus", http.getStatusCode().value());
                body = http.getResponseBodyAsString();
            } else if (current instanceof WebClientResponseException http) {
                cause.put("httpStatus", http.getStatusCode().value());
                body = http.getResponseBodyAsString();
            } else if (current.getMessage() != null) {
                // Spring AI's response handler embeds HTTP status/body in its own exception.
                var status = Pattern.compile("HTTP\\s+(\\d{3})").matcher(current.getMessage());
                if (status.find()) cause.put("httpStatus", Integer.parseInt(status.group(1)));
                int start = current.getMessage().indexOf('{');
                if (start >= 0) body = current.getMessage().substring(start);
            }
            if (!body.isBlank()) {
                try {
                    var parsed = json.readTree(body);
                    String code = parsed.has("error") ? parsed.path("error").path("code").asText("")
                            : parsed.path("code").asText("");
                    for (String credential : credentials) {
                        if (credential != null && !credential.isBlank()) code = code.replace(credential, "REDACTED");
                    }
                    code = code.replaceAll("(?i)(?:Bearer\\s+\\S+|sk-[A-Za-z0-9_-]+)", "REDACTED");
                    if (code.matches("[A-Za-z0-9_.:-]{1,120}")) cause.put("providerCode", code);
                } catch (java.io.IOException ignored) {
                    // Preserve the exception class/status; never persist an unparsed body or message.
                }
            }
            causes.add(cause);
        }
        return causes;
    }

    @Test
    void providerDiagnosticsKeepStatusAndCodeWithoutBodySecrets() throws Exception {
        String key = "fake-private-credential";
        var failure = new RuntimeException("HTTP 401 - {\"error\":{\"code\":\"InvalidApiKey\",\"message\":\"Bearer " + key + "\"}}");
        String saved = json.writeValueAsString(providerFailure(failure, key));
        assertEquals(401, providerFailure(failure, key).get(0).get("httpStatus"));
        assertEquals("InvalidApiKey", providerFailure(failure, key).get(0).get("providerCode"));
        assertFalse(saved.contains(key));
        assertFalse(saved.contains("Bearer"));
        var secretCode = new RuntimeException("HTTP 401 - {\"error\":{\"code\":\"" + key + "\"}}");
        assertFalse(json.writeValueAsString(providerFailure(secretCode, key)).contains(key));
        var prefixedCode = new RuntimeException("HTTP 401 - {\"error\":{\"code\":\"sk-example123\"}}");
        assertFalse(json.writeValueAsString(providerFailure(prefixedCode)).contains("sk-example123"));
    }

    private static <T> T configure(AnnotationConfigApplicationContext context, T instance) {
        context.getAutowireCapableBeanFactory().autowireBean(instance);
        return instance;
    }

    private void write(Path file, Object value) throws Exception {
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }
}
