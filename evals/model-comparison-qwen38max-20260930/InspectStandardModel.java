import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.RoutePlanCatalog;
import com.stocksage.agent.intent.IntentRecognitionService;
import com.stocksage.config.AgentConfig;
import com.stocksage.config.AiConfig;
import com.stocksage.research.ModelInvocationStore;
import com.stocksage.service.TickerResolutionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Zero-provider inspection of constructed client options and final routing; emits no credentials. */
public final class InspectStandardModel {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("qwen[a-zA-Z0-9.-]+")) {
            throw new IllegalArgumentException("Usage: InspectStandardModel <repo-root> <expected-standard-model>");
        }
        Path root = Path.of(args[0]);
        byte[] application = resource("application.properties");
        String sourceHash = sha(Files.readAllBytes(root.resolve("stocksage-backend/src/main/resources/application.properties")));
        require(sourceHash.equals(sha(application)), "Compiled application.properties differs from source; refresh resources first");
        var prompts = new ArrayList<Prompt>();
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            prompts.add(call.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
        });
        var store = mock(ModelInvocationStore.class);
        var intent = mock(IntentRecognitionService.class);
        var ticker = mock(TickerResolutionService.class);
        var output = new LinkedHashMap<String, Object>();
        try (var context = new AnnotationConfigApplicationContext()) {
            add(context, "application-local.properties", resource("application-local.properties"));
            add(context, "application.properties", application);
            context.refresh();
            var runtime = new AgentRuntimeConfiguration();
            var ai = new AiConfig(runtime);
            var agents = new AgentConfig(runtime, store);
            context.getAutowireCapableBeanFactory().autowireBean(ai);
            context.getAutowireCapableBeanFactory().autowireBean(agents);
            var builder = ChatClient.builder(model);
            var clients = new LinkedHashMap<String, ChatClient>();
            clients.put("chatClient", ai.chatClient(builder));
            clients.put("fundamentals", agents.fundamentalsAgentChatClient(builder));
            clients.put("market", agents.marketAgentChatClient(builder));
            clients.put("news", agents.newsAgentChatClient(builder));
            clients.put("relationExtraction", ai.relationExtractionChatClient(builder));
            var options = new LinkedHashMap<String, Object>();
            for (var entry : clients.entrySet()) {
                int previous = prompts.size();
                entry.getValue().prompt().user("configuration inspection only").call().content();
                require(prompts.size() == previous + 1, "Expected one mock invocation per client");
                var actual = prompts.get(previous).getOptions();
                require(args[1].equals(actual.getModel()), "Client model differs from expected STANDARD model");
                options.put(entry.getKey(), Map.of("model", actual.getModel(), "temperature", actual.getTemperature(),
                        "maxTokens", actual.getMaxTokens()));
            }
            var coordinator = new Coordinator(intent, clients.get("chatClient"), ticker, new RoutePlanCatalog());
            context.getAutowireCapableBeanFactory().autowireBean(coordinator);
            var standard = coordinator.selectFinalAnswerModel(ModelTier.STANDARD, false, false);
            require(args[1].equals(standard.modelName()), "Final route differs from expected STANDARD model");
            var routes = new LinkedHashMap<String, Object>();
            routes.put("STANDARD", coordinator.finalAnswerInvocation(standard));
            routes.put("FAST", coordinator.finalAnswerInvocation(coordinator.selectFinalAnswerModel(ModelTier.FAST, false, false)));
            routes.put("STRONG", coordinator.finalAnswerInvocation(coordinator.selectFinalAnswerModel(ModelTier.STRONG, true, false)));
            routes.put("VISION", coordinator.finalAnswerInvocation(coordinator.selectFinalAnswerModel(ModelTier.STANDARD, false, true)));
            output.put("clientOptions", options);
            output.put("finalRoutes", routes);
        }
        verifyNoInteractions(store, intent, ticker);
        output.put("schemaVersion", 1);
        output.put("scope", "MOCK_MODEL_CONSTRUCTED_OPTIONS_AND_ROUTING_ONLY");
        output.put("status", "PASS");
        output.put("providerCalls", 0);
        output.put("mockCalls", prompts.size());
        output.put("applicationSourceSha256", sourceHash);
        output.put("applicationResourceSha256", sha(application));
        System.out.println(new ObjectMapper().writeValueAsString(output));
    }

    private static byte[] resource(String name) throws Exception {
        try (var stream = InspectStandardModel.class.getClassLoader().getResourceAsStream(name)) {
            return stream == null ? new byte[0] : stream.readAllBytes();
        }
    }

    private static void add(AnnotationConfigApplicationContext context, String name, byte[] bytes) throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource(name, properties));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
