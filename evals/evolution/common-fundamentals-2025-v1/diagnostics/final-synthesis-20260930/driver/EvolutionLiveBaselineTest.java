package com.stocksage.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.FundamentalsPrompts;
import com.stocksage.agent.RoutePlanCatalog;
import com.stocksage.agent.intent.IntentRecognitionService;
import com.stocksage.config.AgentConfig;
import com.stocksage.config.AiConfig;
import com.stocksage.config.ModelTokenBudgetProperties;
import com.stocksage.config.ResearchHttpDeadlineConfiguration;
import com.stocksage.config.RuntimeArtifactIdentity;
import com.stocksage.conversation.ChatPromptAssembler;
import com.stocksage.conversation.OrdinaryAnswerReplayService;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.ClientHttpConnectorAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Explicit registered model replay; ordinary test runs never submit a provider request. */
public class EvolutionLiveBaselineTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public static void main(String[] args) throws Exception {
        assertFalse(System.getProperty("stocksage.evolution.eval.build-manifest", "").isBlank(),
                "Packaged execution requires stocksage.evolution.eval.build-manifest.");
        new EvolutionLiveBaselineTest().runRegisteredBaselineThroughRealComponents();
    }

    @Test
    @EnabledIfSystemProperty(named = "evolution.live.execution", matches = ".+")
    void runRegisteredBaselineThroughRealComponents() throws Exception {
        Path execution = Path.of(System.getProperty("evolution.live.execution")).toAbsolutePath();
        String destination = System.getProperty("evolution.live.output", "");
        assertFalse(destination.isBlank(), "Set evolution.live.output to a new output directory.");
        Path output = Path.of(destination).toAbsolutePath();
        String buildManifest = System.getProperty("stocksage.evolution.eval.build-manifest", "");
        String bundleFile = System.getProperty("stocksage.evolution.eval.bundle-file", "");
        String diagnosisFile = System.getProperty("evolution.live.diagnosis-file", "");
        Map<String, String> productionOrigins = buildManifest.isBlank() ? Map.of()
                : requirePackagedOrigins(Path.of(buildManifest));
        boolean preflight = Boolean.getBoolean("evolution.live.preflight");
        var declared = json.readValue(Files.readAllBytes(execution), EvolutionReplayService.ExecutionFile.class);
        assertFalse(declared.runs().isEmpty(), "Execution must register at least one run.");
        DiagnosisFile diagnosis = diagnosisFile.isBlank() ? null : json.copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(Files.readAllBytes(Path.of(diagnosisFile)), DiagnosisFile.class);
        if (diagnosis != null) assertEquals("", bundleFile, "Final diagnosis cannot register a candidate method.");
        if (!bundleFile.isBlank()) {
            assertEquals(1, json.readTree(Files.readAllBytes(Path.of(bundleFile))).path("bundles").size(),
                    "This experiment may register exactly one candidate alongside the baseline.");
        }
        Files.createDirectory(output);
        Files.copy(execution, output.resolve("execution.json"));
        if (diagnosis != null) Files.copy(Path.of(diagnosisFile), output.resolve("diagnosis.json"));
        if (!bundleFile.isBlank()) Files.copy(Path.of(bundleFile), output.resolve("bundles.json"));

        var worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "evolution-live-analyst");
            thread.setDaemon(true);
            return thread;
        });
        try (var context = new AnnotationConfigApplicationContext()) {
            var environment = context.getEnvironment();
            var application = buildManifest.isBlank() ? Path.of("src/main/resources/application.properties").toUri().toURL()
                    : AiConfig.class.getResource("/application.properties");
            if (!buildManifest.isBlank()) {
                assertFalse(application == null || !application.toString().startsWith(productionOrigins.get(AiConfig.class.getName())),
                        "Packaged application.properties must come from the verified JAR.");
            }
            environment.getPropertySources().addLast(properties("application", application));
            Path local = Path.of("src/main/resources/application-local.properties");
            if (Files.isRegularFile(local)) {
                environment.getPropertySources().addBefore("application", properties("application-local", local));
            }
            context.register(HttpTransportConfiguration.class);
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
            var httpSettings = context.getBean(ClientHttpRequestFactorySettings.class);
            var service = new EvolutionReplayService(analyst, methods, runtime, assembler, answer,
                    new TaskExecutorAdapter(worker), json, artifact, buildManifest, execution.toString(), bundleFile, timeout,
                    assembler.promptMaxTextChars());
            if (diagnosis != null) validateDiagnosis(diagnosis, declared, assembler.promptMaxTextChars());
            assertEquals(AgentPolicyBundle.BASELINE_ID, methods.active().bundleId(), "Experimental registration must not activate a method.");
            var recordedRuntime = new LinkedHashMap<String, Object>(Map.of("startedAt", Instant.now().toString(),
                    "provider", runtime.chatProviderSnapshot(), "clients", runtime.snapshot(),
                    "stageTimeoutSeconds", timeout, "promptMaxTextChars", assembler.promptMaxTextChars(),
                    "retryAttempts", attempts, "runtimeArtifact", artifact.snapshot(),
                    "buildAttestation", buildManifest.isBlank() ? "UNATTESTED" : "VERIFIED_ARTIFACT",
                    "httpTransport", Map.of("configuration", "BOOT_AUTO_CONFIGURATION",
                            "readTimeout", java.util.Objects.toString(httpSettings.readTimeout(), "UNSET"),
                            "connectTimeout", java.util.Objects.toString(httpSettings.connectTimeout(), "UNSET")),
                    "scope", preflight ? "NO_MODEL_PREFLIGHT" : "LIVE_MODEL_FROZEN_COMPONENT_CHAIN"));
            recordedRuntime.put("productionCodeSources", productionOrigins);
            if (diagnosis != null) {
                recordedRuntime.put("scope", preflight ? "NO_MODEL_FINAL_DIAGNOSIS_PREFLIGHT" : "LIVE_MODEL_FINAL_ONLY_DIAGNOSIS");
                recordedRuntime.put("experimentId", diagnosis.experimentId());
                recordedRuntime.put("diagnosisSha256", AgentPolicyBundle.sha256(Files.readAllBytes(Path.of(diagnosisFile))));
            }
            recordedRuntime.put("servingBundleId", methods.active().bundleId());
            recordedRuntime.put("driver", Map.of("codeSource", EvolutionLiveBaselineTest.class.getProtectionDomain().getCodeSource().getLocation().toString(),
                    "javaClassPath", System.getProperty("java.class.path"), "loaderPath", System.getProperty("loader.path", "")));
            write(output.resolve("runtime.json"), recordedRuntime);
            if (preflight) {
                System.out.println(json.writeValueAsString(Map.of("status", "PREFLIGHT_PASSED", "modelCalls", 0,
                        "buildAttestation", recordedRuntime.get("buildAttestation"), "output", output.toString())));
                return;
            }
            if (diagnosis != null) {
                for (var run : diagnosis.runs()) {
                    write(output.resolve(run.runId() + ".request.json"), run);
                    OrdinaryAnswerReplayService.Result response;
                    try {
                        response = answer.replay(run.request());
                    } catch (RuntimeException failure) {
                        write(output.resolve(run.runId() + ".failure.json"), Map.of("errorType", failure.getClass().getSimpleName(),
                                "at", Instant.now().toString(), "providerCompletion", "UNKNOWN", "retry", false));
                        throw new AssertionError("Final diagnosis stopped at " + run.runId() + "; inspect saved artifacts. Error type: "
                                + failure.getClass().getSimpleName());
                    }
                    write(output.resolve(run.runId() + ".response.json"), response);
                    Files.writeString(output.resolve("responses.jsonl"), json.writeValueAsString(Map.of("request", run, "response", response)) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    System.out.println("final-diagnosis run=" + run.runId() + " status=" + response.status());
                    assertEquals(run.promptSha256(), response.promptSha256(), "Final replay input differs from registered input.");
                    assertEquals(OrdinaryAnswerReplayService.Status.COMPLETED, response.status(),
                            "Batch stopped; request, response and available usage are retained at " + output);
                }
                return;
            }
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

    private void validateDiagnosis(DiagnosisFile diagnosis, EvolutionReplayService.ExecutionFile declared, int maxChars) throws Exception {
        assertEquals(1, diagnosis.schemaVersion(), "Diagnosis requires schemaVersion=1.");
        assertFalse(diagnosis.experimentId() == null || diagnosis.experimentId().isBlank(), "Diagnosis needs experimentId.");
        assertFalse(diagnosis.runs() == null || diagnosis.runs().isEmpty(), "Diagnosis needs registered runs.");
        var runIds = new java.util.HashSet<String>();
        var registeredCases = declared.cases().stream().map(EvolutionReplayService.FrozenCase::caseId).toList();
        for (var run : diagnosis.runs()) {
            assertFalse(run == null || run.runId() == null || !run.runId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
                            || !runIds.add(run.runId()) || !registeredCases.contains(run.caseId()) || run.repeatId() < 1
                            || run.variant() == null || run.variant().isBlank(),
                    "Diagnosis run needs a unique safe runId, registered caseId, variant and positive repeatId.");
            assertFalse(run.request() == null || run.request().modelTier() == null || run.request().messages() == null
                            || run.request().messages().isEmpty() || run.request().messages().size() > 128,
                    "Diagnosis request needs modelTier and 1 to 128 frozen messages.");
            long chars = 0;
            var ordered = new ArrayList<Map<String, String>>();
            for (var message : run.request().messages()) {
                assertFalse(message == null || message.role() == null || !List.of("system", "user", "assistant").contains(message.role())
                                || message.text() == null, "Diagnosis messages need supported roles and text.");
                chars += message.text().length();
                var row = new LinkedHashMap<String, String>();
                row.put("role", message.role());
                row.put("text", message.text());
                ordered.add(row);
            }
            var last = run.request().messages().get(run.request().messages().size() - 1);
            assertFalse(chars > maxChars || !"user".equals(last.role()) || last.text().isBlank(),
                    "Diagnosis must fit the runtime character budget and end with a nonempty user message.");
            assertEquals(AgentPolicyBundle.sha256(json.writeValueAsString(ordered)), run.promptSha256(),
                    "Frozen diagnosis messages do not match their registered SHA-256.");
        }
    }

    public record DiagnosisFile(int schemaVersion, String experimentId, List<DiagnosisRun> runs) {}
    public record DiagnosisRun(String runId, String caseId, String variant, int repeatId, String promptSha256,
                               OrdinaryAnswerReplayService.Request request) {}

    @Test
    void finalDiagnosisRejectsChangedMessagesAndRepeatedRunsBeforeCallingModels() throws Exception {
        var declared = json.readValue("{\"schemaVersion\":1,\"cases\":[{\"caseId\":\"sample\"}],\"runs\":[]}",
                EvolutionReplayService.ExecutionFile.class);
        var request = new OrdinaryAnswerReplayService.Request(
                List.of(new OrdinaryAnswerReplayService.PromptMessage("user", "frozen input")),
                com.stocksage.agent.ModelTier.STANDARD);
        String hash = AgentPolicyBundle.sha256("[{\"role\":\"user\",\"text\":\"frozen input\"}]");
        var run = new DiagnosisRun("run-1", "sample", "original", 1, hash, request);
        validateDiagnosis(new DiagnosisFile(1, "diagnosis", List.of(run)), declared, 24000);
        assertThrows(AssertionError.class, () -> validateDiagnosis(
                new DiagnosisFile(1, "diagnosis", List.of(run, run)), declared, 24000));
        var changed = new DiagnosisRun("run-2", "sample", "modified", 1, "0".repeat(64), request);
        assertThrows(AssertionError.class, () -> validateDiagnosis(
                new DiagnosisFile(1, "diagnosis", List.of(changed)), declared, 24000));
    }

    private static Map<String, String> requirePackagedOrigins(Path manifest) throws Exception {
        Path expected = manifest.toAbsolutePath().getParent().resolve("stocksage-backend.jar").toRealPath();
        assertFalse(!Files.isRegularFile(expected), "Expected the build's stocksage-backend.jar beside build.json.");
        var origins = new LinkedHashMap<String, String>();
        for (Class<?> type : java.util.List.of(com.stocksage.StockSageApplication.class, FundamentalsAgent.class,
                FundamentalsPrompts.class, EvolutionReplayService.class, FundamentalsMethodRegistry.class,
                AgentPolicyBundle.class, AgentRuntimeConfiguration.class, Coordinator.class, ChatPromptAssembler.class,
                OrdinaryAnswerReplayService.class, AiConfig.class, AgentConfig.class, RuntimeArtifactIdentity.class,
                ResearchHttpDeadlineConfiguration.class)) {
            var location = type.getProtectionDomain().getCodeSource().getLocation();
            Path actual = "file".equals(location.getProtocol()) ? Path.of(location.toURI())
                    : new ApplicationHome(type).getSource().toPath();
            assertEquals(expected, actual.toRealPath(), "Production class must load from the declared JAR: " + type.getName());
            origins.put(type.getName(), location.toString());
        }
        return Map.copyOf(origins);
    }

    @Test
    void packagedEntryRejectsClassDirectoryWithoutCallingModels() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "evolution-packaged-origin-test-");
        try {
            Files.writeString(directory.resolve("stocksage-backend.jar"), "not-a-production-jar");
            assertThrows(AssertionError.class, () -> requirePackagedOrigins(directory.resolve("build.json")));
        } finally {
            Files.deleteIfExists(directory.resolve("stocksage-backend.jar"));
            Files.delete(directory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
            ClientHttpConnectorAutoConfiguration.class, WebClientAutoConfiguration.class})
    @Import(ResearchHttpDeadlineConfiguration.class)
    static class HttpTransportConfiguration {}

    @Test
    void usesBootHttpTransportWithoutBareReactorTenSecondTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                // Bare RestClient construction adds a 10s Reactor timeout absent from Boot's transport.
                Thread.sleep(11_000);
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext(HttpTransportConfiguration.class)) {
            assertEquals(HttpClientAutoConfiguration.class.getName(), context.getBeanFactory()
                    .getBeanDefinition("clientHttpRequestFactoryBuilder").getFactoryBeanName());
            var client = context.getBean(RestClient.Builder.class).build();
            assertEquals("ok", client.get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/slow")
                    .retrieve().body(String.class));
            context.getBean(WebClient.Builder.class);
        } finally {
            server.stop(0);
        }
    }

    private static PropertiesPropertySource properties(String name, Path path) throws Exception {
        return properties(name, path.toUri().toURL());
    }

    private static PropertiesPropertySource properties(String name, java.net.URL source) throws Exception {
        Properties properties = new Properties();
        try (var reader = new java.io.InputStreamReader(source.openStream(), StandardCharsets.UTF_8)) {
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
