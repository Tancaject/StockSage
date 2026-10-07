import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.conversation.ChatPromptAssembler;
import com.stocksage.evolution.EvolutionReplayService;
import com.stocksage.service.ToolPrefetchService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.ResourcePropertySource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class DraftBudgetReplay {
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path root = repo.resolve("evals/evolution/batch-repair-20261002/budget32768-timeout300");
        var mapper = new ObjectMapper().findAndRegisterModules();
        List<Map<String,Object>> rows = new ArrayList<>();
        try (var beans = new AnnotationConfigApplicationContext()) {
            beans.getEnvironment().getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
            beans.register(ChatPromptAssembler.class);
            beans.refresh();
            var assembler = beans.getBean(ChatPromptAssembler.class);
            check(assembler.promptMaxTextChars() == 24000, "Production prompt budget changed");
            for (String name : List.of("ibm-cash", "ibm-liquidity", "jnj-cash", "tgt-control")) {
                String run = "repair-b32768-" + name;
                Path directory = root.resolve("raw-" + run);
                Path responsePath = directory.resolve(run + "-1-1.response.json");
                byte[] responseBytes = Files.readAllBytes(responsePath);
                var response = mapper.readTree(responseBytes);
                var execution = mapper.readValue(Files.readAllBytes(directory.resolve("execution.json")), EvolutionReplayService.ExecutionFile.class);
                var sample = execution.cases().stream().filter(c -> c.caseId().equals(response.path("caseId").asText())).findFirst().orElseThrow();
                String report = response.path("analysis").path("answer").asText();
                check(response.path("analystStatus").asText().equals("TRUNCATED"), "Expected preserved truncated input");
                var draft = ToolPrefetchService.appendAnalystDraft(sample.preAnalystContext(), "Fundamentals Agent", report, sample.initialTaskOutcome());
                var prepared = new ToolPrefetchService.PreparedToolContext(ToolPrefetchService.finishOrdinaryContext(draft.context(), draft.taskOutcome()),
                        "", null, null, draft.taskOutcome(), sample.citationIds(), sample.context(), draft.section());
                List<Message> messages = new ArrayList<>();
                for (var message : sample.history()) messages.add(message.role().equals("user") ? new UserMessage(message.text()) : new AssistantMessage(message.text()));
                messages.add(new UserMessage(sample.query()));
                var assembly = assembler.assemble(messages, prepared, List.of(), "", "", false, false, sample.asOf());
                check(draft.status().equals("COMPLETED"), "Complete draft not recorded");
                check(draft.taskOutcome().equals(sample.initialTaskOutcome()), "Initial outcome changed");
                check(assembly.usedChars() <= assembly.maxChars(), "Total budget exceeded");
                check(assembly.evidenceContext().equals(sample.context()), "Original evidence changed");
                var span = assembly.analystSpan();
                check(span != null, "Draft attribution is missing");
                String text = assembly.messages().get(span.messageIndex()).getText();
                String included = text.substring(text.offsetByCodePoints(0, span.start()), text.offsetByCodePoints(0, span.end()));
                check(included.equals("## Fundamentals Agent\n" + report + "\n\n"), "Draft text was lost");
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("caseId", sample.caseId()); row.put("runId", response.path("runId").asText());
                row.put("sourceResponsePath", repo.relativize(responsePath).toString()); row.put("sourceResponseSha256", hash(responseBytes));
                row.put("originalAnalystStatus", response.path("analystStatus").asText()); row.put("originalTaskOutcome", response.path("taskOutcome").asText());
                row.put("reassembledAnalystStatus", draft.status()); row.put("reassembledTaskOutcome", draft.taskOutcome());
                row.put("analysisChars", report.length()); row.put("usedChars", assembly.usedChars()); row.put("maxChars", assembly.maxChars());
                row.put("evidencePreserved", true); row.put("fullDraftPreserved", true); row.put("analystSpan", span);
                row.put("messages", assembly.messages().stream().map(m -> Map.of("role", m.getMessageType().getValue(), "text", m.getText())).toList());
                check(hash(Files.readAllBytes(responsePath)).equals(hash(responseBytes)), "Original response changed");
                rows.add(row);
            }
        }
        var result = Map.of("status", "PASS", "scope", "OFFLINE_REAL_DRAFT_COMPONENT_REASSEMBLY", "modelCalls", 0,
                "caseCount", rows.size(), "productionCodeSource", ToolPrefetchService.class.getProtectionDomain().getCodeSource().getLocation().toString(),
                "qualityBoundary", "No regenerated final answers; this verifies complete input assembly only.", "cases", rows);
        Files.writeString(Path.of(args[1]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println(mapper.writeValueAsString(Map.of("status", "PASS", "caseCount", rows.size(), "modelCalls", 0)));
    }
}
