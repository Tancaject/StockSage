package com.stocksage.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards direct source references, including same-package types that require no import.
 * This is not a transitive dependency graph or runtime isolation check; reflection and
 * dependencies reached through an allowed collaborator are outside its scope.
 */
class ModuleDependencyBoundaryTest {
    private static final Path SOURCES = Path.of("src/main/java/com/stocksage");
    private static final Pattern NON_CODE = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final String PERSISTENCE = "\\b(?:\\w*Repository|EntityManager|JdbcTemplate|NamedParameterJdbcTemplate)\\b"
            + "|\\b(?:jakarta|javax)\\.persistence\\.|\\borg\\.springframework\\.(?:data\\.jpa|jdbc)\\.";
    private static final String TRANSACTIONS = "\\b(?:Transactional|TransactionTemplate|PlatformTransactionManager|TransactionOperations)\\b"
            + "|\\borg\\.springframework\\.transaction\\.";
    private static final String MODEL_SDK = "\\borg\\.springframework\\.ai\\.(?:chat\\.(?:client|model)|embedding|openai|ollama|anthropic)\\."
            + "|\\b(?:ChatClient|ChatModel|StreamingChatModel|EmbeddingModel)\\b";
    private static final String MODEL_EXECUTION = MODEL_SDK
            + "|\\b(?:Coordinator|FundamentalsAgent|MarketAgent|NewsAgent|ResearchDebateService)\\b";
    private static final String REQUEST_IDENTITY = "\\b(?:RequestIdentity|SecurityContext\\w*|HttpSession\\w*|HttpServletRequest|RequestContextHolder|ServletRequestAttributes)\\b"
            + "|\\borg\\.springframework\\.security\\.|\\b(?:jakarta|javax)\\.servlet\\.";

    @Test void prefetchDelegatesKnowledgePublication() throws IOException {
        forbid("service/ToolPrefetchService.java", PERSISTENCE + "|" + TRANSACTIONS
                + "|\\b(?:KnowledgeIngestionService|backgroundTaskExecutor|searchIngestTtlDays|ResearchTaskService|ResearchTaskQueue|ResearchTaskObservationService|DeepResearchPipeline|ResearchTask|NewsTools)\\b");
    }

    @Test void chatServiceDoesNotOwnRepositoriesOrTransactions() throws IOException {
        forbid("conversation/ChatService.java", PERSISTENCE + "|" + TRANSACTIONS);
        forbid("conversation/OrdinaryAnswerReplayService.java", PERSISTENCE + "|" + TRANSACTIONS
                + "|\\b(?:ToolPrefetchService|ConversationMessageService|TraceService|RagService)\\b");
    }

    @Test void defaultComponentScanFindsTheMovedServicesButNotPerRequestSessions() throws Exception {
        List<String> components = productionComponents("conversation");
        assertThat(components).containsExactlyInAnyOrder(
                "com.stocksage.conversation.OrdinaryAnswerReplayService",
                "com.stocksage.conversation.ChatService",
                "com.stocksage.conversation.ChatPromptAssembler",
                "com.stocksage.conversation.ConversationMessageService",
                "com.stocksage.conversation.ConversationTitleService",
                "com.stocksage.conversation.ImageAttachmentService");
        for (String component : components) {
            assertThat(Modifier.isPublic(Class.forName(component, false, getClass().getClassLoader()).getModifiers()))
                    .as("Spring component remains public: %s", component).isTrue();
        }
    }

    @Test void promptAssemblyDoesNotReadStorageMemoryOrExecuteModelsAndTraces() throws IOException {
        forbid("conversation/ChatPromptAssembler.java", PERSISTENCE + "|" + TRANSACTIONS + "|" + MODEL_EXECUTION
                + "|\\borg\\.springframework\\.data\\.redis\\.|\\b(?:io\\.lettuce|org\\.redisson|redis\\.clients)\\."
                + "|\\b(?:Redis\\w*|StringRedisTemplate|TraceService|TraceEventRelay|TraceEventStore|Tracer)\\b"
                + "|\\bcom\\.stocksage\\.(?:trace|memory)\\.|\\bio\\.opentelemetry\\."
                + "|\\b(?:LongTermMemory|ShortTermMemory|ResearchMemoryService|ResearchMemoryVectorIndex)\\b");
    }

    @Test void streamSessionRemainsInternalAndDoesNotPersistOrRunResearch() throws Exception {
        forbid("conversation/ChatStreamSession.java", PERSISTENCE + "|" + TRANSACTIONS + "|" + MODEL_EXECUTION
                + "|\\b(?:ConversationMessageService|ConversationTitleService|DeepResearchPipeline|DeepEvidenceCollector|DeepEvidenceReplanService"
                + "|ResearchTaskService|ResearchTaskQueue|ResearchTaskWorker|ResearchTaskCheckpointService|ResearchTaskPublicationTransaction"
                + "|InvestmentReportVersionService|OfflineDemoSampleService)\\b");
        Class<?> session = Class.forName("com.stocksage.conversation.ChatStreamSession", false, getClass().getClassLoader());
        assertThat(Modifier.isPublic(session.getModifiers())).as("stream session is package-private").isFalse();
    }

    @Test void researchPackageDoesNotReachIntoChatTransportOrRequestIdentity() throws Exception {
        List<Path> research = sourcesIn("research");
        assertThat(research).contains(SOURCES.resolve("research/DeepResearchPipeline.java"),
                SOURCES.resolve("research/ResearchDebateService.java"),
                SOURCES.resolve("research/ResearchTaskPublicationTransaction.java"));
        for (Path source : research) {
            forbid(SOURCES.relativize(source).toString(), "\\b(?:ChatService|ChatStreamSession)\\b|" + REQUEST_IDENTITY
                    + "|" + MODEL_SDK);
        }
        List<String> components = productionComponents("research");
        assertThat(components).containsExactlyInAnyOrder(
                "com.stocksage.research.DeepResearchPipeline",
                "com.stocksage.research.DeepEvidenceCollector",
                "com.stocksage.research.DeepEvidenceReplanService",
                "com.stocksage.research.ResearchTaskService",
                "com.stocksage.research.ResearchSubmissionService",
                "com.stocksage.research.ResearchTaskObservationService",
                "com.stocksage.research.ResearchRunManifestService",
                "com.stocksage.research.ModelInvocationStore",
                "com.stocksage.research.ResearchEvidenceSnapshotService",
                "com.stocksage.research.ResearchTaskLeaseService",
                "com.stocksage.research.ResearchTaskCheckpointService",
                "com.stocksage.research.ResearchTaskPublicationTransaction",
                "com.stocksage.research.ResearchTaskQueue",
                "com.stocksage.research.ResearchTaskWorker",
                "com.stocksage.research.ResearchTaskRecoveryScheduler",
                "com.stocksage.research.InvestmentReportVersionService",
                "com.stocksage.research.ReportMarkdownRenderer",
                "com.stocksage.research.ResearchDebateService");
        assertThat(productionComponents("agent")).contains("com.stocksage.agent.DeepEvidenceReplanner");
        for (String component : components) {
            assertThat(Modifier.isPublic(Class.forName(component, false, getClass().getClassLoader()).getModifiers()))
                    .as("Spring component remains public: %s", component).isTrue();
        }
    }

    @Test void evidenceFactsAndQualificationDoNotDependOnHarnessOrAdapters() throws IOException {
        Pattern imports = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([^;]+);");
        String allowedImport = "(?:java\\.(?:time|util)\\..+|com\\.stocksage\\.evidence\\.EvidenceModels\\..+"
                + "|com\\.stocksage\\.agent\\.intent\\.TimeSensitivity|com\\.fasterxml\\.jackson\\.annotation\\.JsonIgnore)";
        List<Path> facts;
        try (var sources = Files.list(SOURCES.resolve("evidence"))) {
            facts = sources.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(facts).contains(SOURCES.resolve("evidence/EvidenceModels.java"),
                SOURCES.resolve("evidence/EvidenceTiming.java"), SOURCES.resolve("evidence/EvidenceLedger.java"),
                SOURCES.resolve("evidence/EvidenceFreshness.java"));
        for (Path fact : facts) {
            String relative = SOURCES.relativize(fact).toString();
            String source = code(relative);
            assertThat(imports.matcher(source).results().map(match -> match.group(1).strip())
                    .filter(dependency -> !dependency.matches(allowedImport)).toList())
                    .as("%s imports only pure contracts and serialization annotations", relative).isEmpty();
            forbid(relative, PERSISTENCE + "|" + TRANSACTIONS + "|" + MODEL_EXECUTION + "|" + REQUEST_IDENTITY
                    + "|\\b(?:org|io|jakarta|javax)\\.|\\bjava\\.(?:sql|net|io|nio\\.file)\\."
                    + "|\\bcom\\.stocksage\\.(?:repository|service|conversation|research|knowledge|identity|trace|client|config|ibkr|rag|memory|harness|evidence\\.adapter)\\."
                    + "|\\b(?:TraceService|TraceEventRelay|TraceEventStore|ResearchHarness|WebClient|RestClient|HttpClient)\\b");
        }
    }

    @Test void researchAndKnowledgeServicesReceiveIdentityExplicitly() throws IOException {
        List<Path> scoped = new ArrayList<>();
        for (String area : List.of("research", "knowledge", "rag")) {
            scoped.addAll(sourcesIn(area));
        }
        assertThat(scoped).contains(SOURCES.resolve("research/DeepResearchPipeline.java"),
                SOURCES.resolve("research/ResearchTaskService.java"), SOURCES.resolve("research/ResearchDebateService.java"),
                SOURCES.resolve("knowledge/ResearchMemoryService.java"), SOURCES.resolve("knowledge/KnowledgeService.java"),
                SOURCES.resolve("knowledge/KnowledgeIngestionService.java"), SOURCES.resolve("rag/RagService.java"));
        for (Path file : scoped) {
            forbid(SOURCES.relativize(file).toString(), REQUEST_IDENTITY);
        }
    }

    @Test void knowledgeAndRetrievalDoNotExecuteChatOrResearchTasks() throws IOException {
        for (String area : List.of("knowledge", "rag")) {
            for (Path source : sourcesIn(area)) {
                forbid(SOURCES.relativize(source).toString(),
                        "\\b(?:ChatService|ChatStreamSession|ResearchTaskWorker|ResearchTaskQueue|ResearchTaskLeaseService|DeepResearchPipeline)\\b");
            }
        }
        assertThat(productionComponents("knowledge")).containsExactlyInAnyOrder(
                "com.stocksage.knowledge.KnowledgeService",
                "com.stocksage.knowledge.SearchResultIngestionService",
                "com.stocksage.knowledge.KnowledgeIngestionService",
                "com.stocksage.knowledge.EdgarIngestionService",
                "com.stocksage.knowledge.ResearchMemoryService",
                "com.stocksage.knowledge.ResearchMemoryProperties",
                "com.stocksage.knowledge.ResearchMemoryVectorIndex");
    }

    @Test void identityDoesNotDependOnProductExecutionAndKeepsTheSessionPrincipalName() throws IOException {
        for (String name : List.of("AuthService", "RequestIdentity")) {
            forbid("identity/" + name + ".java", MODEL_EXECUTION
                    + "|\\bcom\\.stocksage\\.(?:conversation|research|knowledge|trace)\\."
                    + "|\\b(?:BullResearcher|BearResearcher|ResearchManager|DebateContractParser)\\b"
                    + "|\\bio\\.opentelemetry\\.|\\b(?:TraceService|TraceEventRelay|TraceEventStore|Tracer)\\b");
        }
        assertThat(productionComponents("identity")).containsExactlyInAnyOrder(
                "com.stocksage.identity.AuthService", "com.stocksage.identity.RequestIdentity");
        // The existing HTTP-session principal is deliberately kept at its serialized class name.
        assertThat(code("security/AuthenticatedUser.java")).contains("package com.stocksage.security;");
        assertThat(code("identity/RequestIdentity.java"))
                .contains("import com.stocksage.security.AuthenticatedUser;");
    }

    @Test void providerMappingDoesNotOwnExecutionPersistenceOrRequestIdentity() throws IOException {
        forbid("evidence/adapter/EvidenceEnvelopeMapper.java", PERSISTENCE + "|" + TRANSACTIONS + "|" + MODEL_EXECUTION
                + "|" + REQUEST_IDENTITY
                + "|\\b(?:DeepResearchPipeline|DeepEvidenceCollector|ResearchTaskService|ChatService|TraceService|ResearchHarness)\\b"
                + "|\\b(?:WebClient|RestClient|HttpClient|DataServiceClient|IbkrReadOnlyService)\\b");
        assertThat(productionComponents("evidence"))
                .containsExactly("com.stocksage.evidence.adapter.EvidenceEnvelopeMapper");
    }

    private static List<Path> sourcesIn(String area) throws IOException {
        try (var sources = Files.walk(SOURCES.resolve(area))) {
            return sources.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
        }
    }

    private static List<String> productionComponents(String area) throws IOException {
        List<String> productionClasses = sourcesIn(area).stream()
                .map(source -> "com.stocksage." + SOURCES.relativize(source).toString()
                        .replace('\\', '.').replace('/', '.').replaceFirst("\\.java$", ""))
                .toList();
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        // Only production-source component discovery is checked; tests also contain nested configurations.
        // Production nested components still participate in each exact component-set assertion.
        scanner.addExcludeFilter((metadataReader, metadataReaderFactory) -> {
            String className = metadataReader.getClassMetadata().getClassName();
            int nestedClass = className.indexOf('$');
            String topLevelClass = nestedClass < 0 ? className : className.substring(0, nestedClass);
            return !productionClasses.contains(topLevelClass);
        });
        return scanner.findCandidateComponents("com.stocksage." + area).stream()
                .map(definition -> definition.getBeanClassName()).toList();
    }

    private static void forbid(String relative, String forbidden) throws IOException {
        assertThat(Pattern.compile(forbidden).matcher(code(relative)).results().map(MatchResult::group).toList())
                .as("%s must not reference the forbidden boundary directly", relative).isEmpty();
    }

    private static String code(String relative) throws IOException {
        Path file = SOURCES.resolve(relative);
        assertThat(Files.isRegularFile(file)).as("boundary source exists: %s", file).isTrue();
        return NON_CODE.matcher(Files.readString(file)).replaceAll(" ");
    }
}
