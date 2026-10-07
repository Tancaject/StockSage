import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.*;
import com.stocksage.agent.intent.IntentRecognitionService;
import com.stocksage.conversation.OrdinaryAnswerReplayService;
import com.stocksage.evolution.AgentPolicyBundle;
import com.stocksage.service.ToolPrefetchService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 受控 HTTP 供应商穿过真实 SDK 和普通生成组件；不使用测试框架，也不调用外部模型。 */
public class OrdinaryCompletionReplay {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AtomicReference<Scenario> CURRENT = new AtomicReference<>();
    private static final List<Map<String, Object>> HTTP = Collections.synchronizedList(new ArrayList<>());
    private record Scenario(String id, String reason, String text, String expected) {}

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]).toAbsolutePath();
        Path output = Path.of(args[1]).toAbsolutePath();
        List<Scenario> scenarios = List.of(
                new Scenario("normal", "stop", "已核对 [E1]。", "COMPLETE"),
                new Scenario("length", "length", "部分回答 [E1]", "TRUNCATED"),
                new Scenario("missing_finish", null, "已有正文 [E1]", "UNKNOWN"),
                new Scenario("sdk_unknown_finish", "UNKNOWN", "已有正文 [E1]", "UNKNOWN"),
                new Scenario("empty", "stop", "", "FAILED"),
                new Scenario("filtered", "content_filter", "部分正文", "FAILED"),
                new Scenario("unexpected_tool", "tool_calls", "部分正文", "FAILED"),
                new Scenario("legacy_tool", "tool_call", "部分正文", "FAILED"),
                new Scenario("http_error", null, "", "FAILED"),
                new Scenario("stream_disconnect", null, "部分正文 [E1]", "NOT_COMPLETE"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/v1/chat/completions", OrdinaryCompletionReplay::respond);
        server.start();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            var api = OpenAiApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("local-controlled-provider").build();
            var model = OpenAiChatModel.builder().openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().model("controlled-model").build())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            ChatClient client = ChatClient.builder(model).build();
            Coordinator coordinator = new Coordinator((IntentRecognitionService) null, client, null, new RoutePlanCatalog());
            set(coordinator, "standardModel", "controlled-model");
            set(coordinator, "modelRoutingMaxOutputTokens", 128);
            OrdinaryAnswerReplayService replay = new OrdinaryAnswerReplayService(coordinator, JSON);
            FundamentalsAgent fundamentals = new FundamentalsAgent(client, null);
            MarketAgent market = new MarketAgent(client);
            NewsAgent news = new NewsAgent(client);
            for (Scenario scenario : scenarios) {
                CURRENT.set(scenario);
                var result = replay.replay(new OrdinaryAnswerReplayService.Request(
                        List.of(new OrdinaryAnswerReplayService.PromptMessage("user", scenario.id())), ModelTier.STANDARD));
                boolean matched = matches(scenario.expected(), result.completion().status().name());
                String taskOutcome = prepared("COMPLETED").outcomeForAnswer(result.answer(), result.completion());
                if (!matched || (result.completion().complete() != (result.status() == OrdinaryAnswerReplayService.Status.COMPLETED))) {
                    failures.add("Final completion differs: " + scenario.id());
                }
                rows.add(row("final", scenario.id(), result, taskOutcome, matched));
                if (scenario.id().equals("normal")) {
                    for (String prior : List.of("DEGRADED", "BLOCKED", "FAILED")) {
                        String retained = prepared(prior).outcomeForAnswer(result.answer(), result.completion());
                        if (!prior.equals(retained)) failures.add("Outcome upgraded: " + prior);
                    }
                    for (String answer : List.of("无引用", "未知引用 [E99]")) {
                        if (!"DEGRADED".equals(prepared("COMPLETED").outcomeForAnswer(answer, result.completion()))) {
                            failures.add("Invalid citation accepted");
                        }
                    }
                    List<Map<String, Object>> observations = result.observations();
                    if (observations.stream().noneMatch(event -> "model-completion".equals(event.get("kind"))
                            && "stop".equalsIgnoreCase(String.valueOf(event.get("finishReason"))))) failures.add("Finish reason lost after usage-only chunk");
                }
                if (scenario.id().equals("http_error") || scenario.id().equals("stream_disconnect")) continue;
                for (String role : List.of("fundamentals", "market", "news")) {
                    ModelCompletion.Output generated;
                    if (role.equals("fundamentals")) {
                        var analysis = fundamentals.analyzeObserved(scenario.id(), "原始证据 [E1]", AgentPolicyBundle.baseline());
                        generated = new ModelCompletion.Output(analysis.content(), analysis.completion());
                    } else generated = role.equals("market")
                            ? market.analyzeObserved(scenario.id(), "原始证据 [E1]")
                            : news.analyzeObserved(scenario.id(), "原始证据 [E1]");
                    var draft = ToolPrefetchService.appendAnalystDraft("原始证据 [E1]", role,
                            generated.content(), "COMPLETED", generated.completion());
                    boolean accepted = matches(scenario.expected(), generated.completion().status().name())
                            && (generated.completion().complete() == (draft.section() != null));
                    if (!accepted) failures.add("Analyst completion differs: " + role + "/" + scenario.id());
                    rows.add(row(role, scenario.id(), generated, draft, accepted));
                }
            }
            CURRENT.set(new Scenario("unknown_finish", "future_reason", "已有正文 [E1]", "NOT_COMPLETE"));
            var unknown = replay.replay(new OrdinaryAnswerReplayService.Request(
                    List.of(new OrdinaryAnswerReplayService.PromptMessage("user", "unknown_finish")), ModelTier.STANDARD));
            boolean unknownRejected = !unknown.completion().complete();
            if (!unknownRejected) failures.add("Unknown provider finish reason accepted");
            rows.add(row("final", "unknown_finish", unknown, "SDK may reject an unknown provider enum before application classification", unknownRejected));

            CURRENT.set(new Scenario("cancelled", "stop", "部分正文 [E1]", "FAILED"));
            List<Map<String, Object>> cancelled = new CopyOnWriteArrayList<>();
            AtomicReference<ModelCompletion> cancellation = new AtomicReference<>();
            coordinator.streamAnswer(List.of(new UserMessage("cancelled")), false, ModelTier.STANDARD, false,
                    cancelled::add, cancellation::set).take(1).blockLast(Duration.ofSeconds(5));
            boolean cancellationRecorded = cancellation.get() != null && !cancellation.get().complete()
                    && cancelled.stream().anyMatch(event -> "CANCELLED".equals(event.get("termination")));
            if (!cancellationRecorded) failures.add("Cancellation completion not recorded");
            rows.add(row("final", "cancelled", cancelled, String.valueOf(cancellation.get()), cancellationRecorded));

            // 观测失败只影响记录，不能抹掉直接交给调用方的完成事实。
            CURRENT.set(scenarios.get(0));
            AtomicReference<ModelCompletion> authoritative = new AtomicReference<>();
            String text = coordinator.streamAnswer(List.of(new UserMessage("observer-failure")), false, ModelTier.STANDARD, false,
                    event -> { throw new IllegalStateException("controlled observer failure"); }, authoritative::set)
                    .collectList().map(tokens -> String.join("", tokens)).block(Duration.ofSeconds(5));
            boolean unaffected = authoritative.get() != null && authoritative.get().complete() && text.contains("[E1]");
            if (!unaffected) failures.add("Observer failure changed completion");
            rows.add(row("final", "observer-failure", authoritative.get(), text, unaffected));
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String path : List.of("agent/ModelCompletion.java", "agent/Coordinator.java", "agent/FundamentalsAgent.java",
                "agent/MarketAgent.java", "agent/NewsAgent.java", "service/ToolPrefetchService.java",
                "conversation/ChatService.java", "conversation/OrdinaryAnswerReplayService.java", "evolution/EvolutionReplayService.java")) {
            Path source = repo.resolve("stocksage-backend/src/main/java/com/stocksage/" + path);
            hashes.put(path, AgentPolicyBundle.sha256(Files.readAllBytes(source)));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", failures.isEmpty() ? "PASS" : "FAIL");
        report.put("scope", "CONTROLLED_HTTP_SPRING_AI_ORDINARY_GENERATION");
        report.put("limitations", List.of("No real provider or semantic quality acceptance", "ChatService persistence and browser UI are not exercised",
                "EvolutionReplayService assembly is compiled; shared draft/outcome rules are exercised directly"));
        report.put("sourceSha256", hashes);
        report.put("checks", rows);
        report.put("httpExchanges", HTTP);
        report.put("failures", failures);
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println(JSON.writeValueAsString(Map.of("status", report.get("status"), "checks", rows.size(), "output", output.toString())));
        if (!failures.isEmpty()) throw new IllegalStateException(String.join("; ", failures));
    }

    private static ToolPrefetchService.PreparedToolContext prepared(String outcome) {
        return new ToolPrefetchService.PreparedToolContext("原始证据 [E1]", "", null, null, outcome, List.of("E1"));
    }
    private static boolean matches(String expected, String actual) {
        return expected.equals("NOT_COMPLETE") ? !actual.equals("COMPLETE") : expected.equals(actual);
    }
    private static Map<String, Object> row(String role, String id, Object result, Object outcome, boolean passed) {
        return Map.of("role", role, "case", id, "result", result, "outcome", outcome, "passed", passed);
    }
    private static void set(Object target, String field, Object value) throws Exception {
        var member = target.getClass().getDeclaredField(field);
        member.setAccessible(true);
        member.set(target, value);
    }
    private static void respond(HttpExchange exchange) throws IOException {
        Scenario scenario = CURRENT.get();
        var request = JSON.readTree(exchange.getRequestBody());
        boolean streaming = request.path("stream").asBoolean();
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("case", scenario.id());
        capture.put("request", request);
        capture.put("stream", streaming);
        HTTP.add(capture);
        try {
            if (scenario.id().equals("http_error")) {
                byte[] body = "{\"error\":{\"message\":\"controlled rejection\",\"type\":\"invalid_request_error\"}}".getBytes(StandardCharsets.UTF_8);
                capture.put("status", 400);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            capture.put("status", 200);
            if (!streaming) {
                var choice = new LinkedHashMap<String, Object>();
                choice.put("index", 0);
                choice.put("message", Map.of("role", "assistant", "content", scenario.text()));
                choice.put("finish_reason", scenario.reason());
                String response = JSON.writeValueAsString(Map.of("id", "controlled", "model", "controlled-model", "created", 1,
                        "object", "chat.completion", "choices", List.of(choice), "usage", Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5)));
                capture.put("response", response);
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            List<String> events = new ArrayList<>();
            events.add(chunk(Map.of("role", "assistant", "content", scenario.text()), null));
            if (!scenario.id().equals("stream_disconnect")) {
                events.add(chunk(Map.of(), scenario.reason()));
                events.add(JSON.writeValueAsString(Map.of("id", "controlled", "model", "controlled-model", "created", 1,
                        "object", "chat.completion.chunk", "choices", List.of(), "usage", Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5))));
                events.add("[DONE]");
            }
            capture.put("responseEvents", List.copyOf(events));
            for (int i = 0; i < events.size(); i++) {
                exchange.getResponseBody().write(("data: " + events.get(i) + "\n\n").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                if (i == 0 && scenario.id().equals("cancelled")) {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        } finally {
            exchange.close();
        }
    }
    private static String chunk(Map<String, Object> delta, String reason) throws IOException {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", reason);
        return JSON.writeValueAsString(Map.of("id", "controlled", "model", "controlled-model", "created", 1,
                "object", "chat.completion.chunk", "choices", List.of(choice)));
    }
}
