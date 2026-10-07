import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.DeepEvidenceReplanner;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.NewsAgent;
import com.stocksage.config.AgentConfig;
import com.stocksage.config.AiConfig;
import com.stocksage.conversation.ConversationTitleService;
import com.stocksage.conversation.ChatPromptAssembler;
import com.stocksage.evolution.AgentPolicyBundle;
import com.stocksage.evolution.FundamentalsMethodRegistry;
import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.ShortTermMemory;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.UserProfileDTO;
import com.stocksage.rag.QueryRewriter;
import com.stocksage.research.ModelInvocationStore;
import com.stocksage.service.UserService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Temporary offline prompt capture; the supplied model is a mock and cannot contact a provider. */
public final class CapturePromptSmoke {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> cases = new ArrayList<>();
    private final List<Prompt> captured = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: CapturePromptSmoke <repo-root> <before|after> <output-json>");
        new CapturePromptSmoke().run(Path.of(args[0]), args[1], Path.of(args[2]));
    }

    private void run(Path root, String variant, Path output) throws Exception {
        if (!Set.of("before", "after").contains(variant)) throw new IllegalArgumentException("variant must be before or after");
        if (Files.exists(output)) throw new IllegalArgumentException("Capture output already exists; preserve the prior artifact");
        var model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            captured.add(call.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
        });
        var stores = mock(ModelInvocationStore.class);
        var users = mock(UserService.class);
        var redis = mock(StringRedisTemplate.class);
        var runtime = new AgentRuntimeConfiguration();
        try (var context = new AnnotationConfigApplicationContext()) {
            // Inject only @Value fields on manually created instances; do not register API or store beans.
            loadProperties(context, root.resolve("stocksage-backend/src/main/resources/application-local.properties"));
            loadProperties(context, root.resolve("stocksage-backend/src/main/resources/application.properties"));
            context.refresh();
            var ai = new AiConfig(runtime);
            var agents = new AgentConfig(runtime, stores);
            context.getAutowireCapableBeanFactory().autowireBean(ai);
            context.getAutowireCapableBeanFactory().autowireBean(agents);
            var builder = ChatClient.builder(model);
            var memoryClient = ai.memoryChatClient(builder);

            var rewrite = new QueryRewriter(ai.queryRewriteChatClient(builder));
            context.getAutowireCapableBeanFactory().autowireBean(rewrite);
            ReflectionTestUtils.setField(rewrite, "enabled", true);
            String rewriteInput = "比较 MSFT 2025 与 2024 财年的经营现金流，不讨论股价。";
            capture("rewrite-comparison-exclusion", "query-rewrite", Map.of("query", rewriteInput),
                    List.of("保留 MSFT、2025/2024 财年、经营现金流与两年比较关系", "保留不讨论股价的排除条件", "只输出一行检索词，不新增公司、日期或财务事实"),
                    () -> rewrite.rewrite(rewriteInput));

            var shortMemory = new ShortTermMemory(redis, memoryClient);
            context.getAutowireCapableBeanFactory().autowireBean(shortMemory);
            ReflectionTestUtils.setField(shortMemory, "llmSummaryEnabled", true);
            List<String> history = List.of("user: 帮我看 AAPL 的现金流。", "assistant: 净利润是 100，增长可能来自需求回暖。",
                    "user: 原报告是 90，不是 100；需求原因还没查。");
            capture("summary-correction-uncertainty", "memory-summary", Map.of("history", history),
                    List.of("保留用户提出的 90 对 100 的更正", "需求回暖仍是助手未核实的推测，不写成确定原因", "保留 AAPL 现金流的研究目标及原因待查"),
                    () -> ReflectionTestUtils.invokeMethod(shortMemory, "buildSummary", history));

            var longMemory = new LongTermMemory(users, memoryClient, mapper);
            String user = "我比较保守，想了解 MSFT，暂时没有买。";
            String assistant = "可以考虑小仓位买入 MSFT，保守组合也可研究债券。";
            capture("profile-assistant-suggestion", "memory-profile", Map.of("userMessage", user, "assistantResponse", assistant),
                    List.of("只输出原契约四字段 JSON", "holdings 与 revokedHoldings 为空数组；riskPreference 为 conservative", "profileSummary 不把助手建议写成用户已持有 MSFT 或债券"),
                    () -> ReflectionTestUtils.invokeMethod(longMemory, "mergeLlmProfile", new UserProfileDTO(), new ArrayList<String>(), user, assistant));

            var titles = new ConversationTitleService(memoryClient);
            context.getAutowireCapableBeanFactory().autowireBean(titles);
            ReflectionTestUtils.setField(titles, "enabled", true);
            String titleInput = "帮我看看 AAPL 最近一季财报的现金流，重点看资本开支。";
            capture("title-financial-topic", "conversation-title", Map.of("query", titleInput),
                    List.of("简短中文主题标题，保留 AAPL 及现金流或资本开支主题", "不回答财务问题、不编造财报结果", "不输出解释、Markdown 或额外字段"),
                    () -> titles.generateTitle(titleInput));

            var news = new NewsAgent(agents.newsAgentChatClient(builder));
            String newsQuery = "微软今天有什么与数据中心相关的消息，能确认对业务有多大影响吗？";
            String newsContext = "标的：MSFT，市场：US；研究日期：2026-09-30。\n[N1] 发布日期：2026-09-30；来源类型：搜索结果摘要；标题：微软推进数据中心项目；摘要：当地报道一项微软数据中心项目获得规划许可。事件发生日期未提供，原文未抓取，投资金额及收入影响未披露。\n没有本轮行情或价格变化数据。";
            capture("news-snippet-missing-event-time", "news", Map.of("query", newsQuery, "context", newsContext),
                    List.of("区分发布时间与未知事件发生时间，不断言事件今天发生", "保留只有摘要、原文未核验限制", "不编投资额、收入影响或价格因果；可说明潜在影响仍需验证"),
                    () -> news.analyze(newsQuery, newsContext));

            var replanner = new DeepEvidenceReplanner(agents.deepEvidenceReplannerChatClient(builder));
            var state = AnalysisState.builder().query("请计算 MSFT FY2025 的经营现金流与资本开支差额。")
                    .fundamentalsReport("已取得 FY2025 经营现金流，但本轮财报证据未包含资本开支，暂不能计算差额。")
                    .marketReport("本题无需行情。")
                    .newsReport("已取得近期新闻摘要，均未提供 FY2025 财报资本开支；没有与用户问题相关的新闻事件缺口。")
                    .build();
            capture("replan-financial-gap-stop", "evidence-replan", Map.of("query", state.getQuery(), "fundamentals", state.getFundamentalsReport(),
                            "market", state.getMarketReport(), "news", state.getNewsReport()),
                    List.of("严格四字段 JSON：decision/action/query/reasonCode", "decision 为 STOP，action 和 query 为 JSON null", "reasonCode 为 NO_SAFE_ACTION，不用新闻搜索补财报数字"),
                    () -> replanner.propose(state));

            var fundamentals = new FundamentalsAgent(agents.fundamentalsAgentChatClient(builder), new FundamentalsMethodRegistry("baseline-v1"));
            Path execution = root.resolve("rag-eval/evolution/common-fundamentals-2025-v1/ai-experiment-001/execution-development.json");
            Set<String> selected = Set.of("wmt-fy2025-cashflow", "msft-fy2025-liquidity");
            int found = 0;
            for (JsonNode row : mapper.readTree(Files.readString(execution, StandardCharsets.UTF_8)).path("cases")) {
                String caseId = row.path("caseId").asText();
                if (!selected.contains(caseId)) continue;
                String query = row.path("resolvedQuery").asText();
                String evidence = row.path("preAnalystContext").asText();
                if (query.isBlank() || evidence.isBlank()) throw new IllegalStateException("Missing frozen development input");
                if (!row.path("caseSha256").asText().matches("[a-f0-9]{64}")) throw new IllegalStateException("Missing source case hash");
                List<String> criteria = caseId.startsWith("wmt")
                        ? List.of("按用户要求列出三个财年经营现金流、资本开支及自由现金流，期间、单位与公式一致", "不根据现金流表编造具体资本开支投向、管理层意图或未来融资保证", "需要新增计算时给出对应原值及公式，额外数字也应正确")
                        : List.of("按用户要求列出两年现金及短期投资、流动资产、流动负债与流动比率", "保留 FY2025 一年内到期长期债务 2,999 百万美元，不说短期有息债务清零", "未实现收入不等同于同额现金还款义务，但履约仍可能有现金成本；额外合计不能遗漏科目");
                capture("fundamentals-" + caseId, "fundamentals", Map.of("sourceExecution", root.relativize(execution).toString(),
                                "sourceCaseId", caseId, "sourceCaseSha256", row.path("caseSha256").asText(), "query", query, "context", evidence),
                        criteria, () -> fundamentals.analyze(query, evidence));
                found++;
            }
            if (found != 2 || cases.size() != 8) throw new IllegalStateException("Expected eight captured cases");
            verifyNoInteractions(stores, users, redis);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("variant", variant);
        result.put("captureScope", "REAL_CALLER_PROMPTS_WITH_MOCK_CHAT_MODEL");
        result.put("providerCalls", 0);
        result.put("storageCalls", 0);
        result.put("ordinaryFixedRules", ChatPromptAssembler.ordinaryFixedRules());
        result.put("baselineMethodIdentity", AgentPolicyBundle.baseline().identity());
        result.put("captureNotes", List.of("Model options are actual client request options after @Value injection; secrets are never serialized", "Optional rewrite/summary/title flags enabled for capture only", "Profile capture invokes mergeLlmProfile, excluding deterministic extraction and persistence", "Fundamentals cases reuse frozen DEVELOPMENT inputs only; no validation or holdout use"));
        result.put("cases", cases);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(mapper.writeValueAsString(Map.of("status", "CAPTURED", "cases", cases.size(), "providerCalls", 0, "output", output.toString())));
    }

    private void capture(String id, String role, Map<String, ?> input, List<String> criteria, Runnable invocation) {
        int start = captured.size();
        invocation.run();
        if (captured.size() != start + 1) throw new IllegalStateException("Expected one model-bound prompt for " + id);
        Prompt prompt = captured.get(start);
        List<Map<String, String>> messages = prompt.getInstructions().stream().map(message -> Map.of(
                "role", message.getMessageType().getValue(), "text", message.getText())).toList();
        var options = prompt.getOptions();
        Map<String, Object> safeOptions = new LinkedHashMap<>();
        safeOptions.put("model", options.getModel());
        safeOptions.put("temperature", options.getTemperature());
        safeOptions.put("maxTokens", options.getMaxTokens());
        safeOptions.put("topP", options.getTopP());
        safeOptions.put("frequencyPenalty", options.getFrequencyPenalty());
        safeOptions.put("presencePenalty", options.getPresencePenalty());
        safeOptions.put("stopSequences", options.getStopSequences());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", id);
        row.put("role", role);
        row.put("dataKind", role.equals("fundamentals") ? "FROZEN_PUBLIC_REPORT_DEVELOPMENT" : "SYNTHETIC");
        row.put("input", input);
        row.put("messages", messages);
        row.put("options", safeOptions);
        row.put("criteria", criteria);
        cases.add(row);
    }

    private static void loadProperties(AnnotationConfigApplicationContext context, Path path) throws Exception {
        if (!Files.isRegularFile(path)) return;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
        context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource(path.getFileName().toString(), properties));
    }
}
