package com.stocksage.conversation;

import com.stocksage.evolution.FundamentalsRuntimeIdentity;
import com.stocksage.exception.ResearchCapacityExceededException;

import com.stocksage.agent.TickerResolutionService;
import com.stocksage.agent.ordinary.ToolPrefetchService;
import com.stocksage.knowledge.KnowledgeIngestionService;
import com.stocksage.knowledge.ResearchMemoryQuery;
import com.stocksage.knowledge.ResearchMemoryService;
import com.stocksage.research.task.ResearchTaskObservationService;
import com.stocksage.research.task.ResearchTaskService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.RoutingDecisionMetadata;
import com.stocksage.agent.RoutingDecisionObserver;
import com.stocksage.agent.intent.IntentRecognitionResult;
import com.stocksage.agent.runtime.AgentStep;
import com.stocksage.agent.runtime.ModelCompletion;
import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.ShortTermMemory;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.rag.RagService;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.ToolCallContext;
import com.stocksage.tool.ToolCallEventBus;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话编排服务。
 *
 * <p>这里是一轮用户请求的主运行流程，调用会话服务提交消息，组织链路生命周期、RAG 检索、
 * Coordinator 路由、确定性取证和上下文组装；SSE 生命周期交给 ChatStreamSession。
 * Spring AI 模型只接收整理后的上下文；记忆更新和知识摄取等副作用都留在服务层处理。</p>
 *
 * <p>典型请求路径：
 * 控制器 -> streamChat -> 意图识别 -> 可选 RAG -> Coordinator 规划 -> 后端取证/无工具智能体 ->
 * 最终回答流 -> 持久化 + 关闭链路。</p>
 *
 * <p>边界：完整消息历史以 MySQL 为真源，Redis 短期记忆和长期画像均可降级；
 * DEEP 后台任务拥有独立 trace 生命周期，浏览器取消 SSE 不会取消已经受理的研究任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 普通聊天会话的来源标识，用于与工作台会话隔离列表。 */
    private static final String CONVERSATION_ORIGIN_CHAT = "chat";
    /** 工作台创建会话的来源标识。 */
    private static final String CONVERSATION_ORIGIN_WORKBENCH = "workbench";
    /** 从用户问题提取美股 ticker 候选，供研究记忆按标的过滤。 */
    private static final Pattern INTENT_TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}\\b");
    /** 看似 ticker、实际是投研缩写的过滤表。 */
    private static final Set<String> INTENT_NON_TICKERS = Set.of(
            "AI", "PE", "PB", "ROE", "RSI", "MACD", "SEC", "ETF", "USD", "EPS", "EV", "FCF"
    );
    private record MemoryEntry(String role, String content) {
    }

    /** 提取最多五个去重美股 ticker 候选。 */
    private List<String> extractTickerCandidates(String query) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        Matcher matcher = INTENT_TICKER_PATTERN.matcher(query == null ? "" : query);
        while (matcher.find() && candidates.size() < 5) {
            String candidate = matcher.group();
            if (!INTENT_NON_TICKERS.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    /** 当前消息优先，再从最近历史由近到远补充 ticker，供指代消歧和本地 fallback 使用。 */
    private List<String> contextualTickerCandidates(String currentQuestion, List<String> recentTurns) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>(extractTickerCandidates(currentQuestion));
        if (recentTurns != null) {
            for (int i = recentTurns.size() - 1; i >= 0 && candidates.size() < 5; i--) {
                for (String candidate : extractTickerCandidates(recentTurns.get(i))) {
                    candidates.add(candidate);
                    if (candidates.size() == 5) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }

    /** 首轮完成后异步生成侧边栏短标题。 */
    private final ConversationTitleService conversationTitleService;
    /** 记录实际提示序列的 JSON 指纹，并供传输层编码使用。 */
    private final ObjectMapper objectMapper;
    /** 记录路由、检索、模型完成和终态。 */
    private final TraceService traceService;
    /** 在路由前检索知识库文档。 */
    private final RagService ragService;
    /** 接收工具切面产生的实时开始/完成事件。 */
    private final ToolCallEventBus toolCallEventBus;
    /** 将后台任务 trace 事件转发给当前 SSE 订阅。 */
    private final TraceEventRelay traceEventRelay;
    /** 向后台任务 trace 发布进度事件。 */
    private final ChatStreamEmitter chatStreamEmitter;
    /** 规划 route/action/model 并流式生成最终答案。 */
    private final Coordinator coordinator;
    /** Redis 提示词窗口；不可用时可由数据库历史继续回答。 */
    private final ShortTermMemory shortTermMemory;
    /** 数据库用户画像及后台提取逻辑。 */
    private final LongTermMemory longTermMemory;
    /** FUNDAMENTALS 回答未完成时写入自进化失败池。 */
    private final com.stocksage.evolution.EvolutionFailurePool failurePool;
    /** 标题和画像更新使用独立的有界后台线程池。 */
    private final AsyncTaskExecutor backgroundTaskExecutor;
    /** 校验并解码当前轮多模态图片。 */
    private final ImageAttachmentService imageAttachmentService;
    /** 执行确定性工具/Agent 预取或提交 DEEP 后台任务。 */
    private final ToolPrefetchService toolPrefetchService;
    /** 会话 SQL 读写与轮次事务；记忆和模型副作用在其返回后执行。 */
    private final ConversationMessageService conversationMessageService;
    /** 汇总路由决策指标。 */
    private final RoutingDecisionObserver routingDecisionObserver;
    /** 检索当前用户可能相关的跨会话历史研究。 */
    private final ResearchMemoryService researchMemoryService;
    /** 复用服务器规范化后的单一标的，避免 RAG 自行维护 ticker 白名单。 */
    private final TickerResolutionService tickerResolutionService;
    private final ChatPromptAssembler chatPromptAssembler;
    private final ResearchTaskService researchTaskService;
    private final ResearchTaskObservationService researchTaskObservationService;

    /** 长模型阶段向前端发送空心跳的间隔秒数。 */
    @Value("${stocksage.chat.stream.heartbeat-seconds:20}")
    private long streamHeartbeatSeconds;

    /**
     * 通过 SSE 将单轮对话流式输出为 JSON 数据块。
     *
     * <p>返回的 Flux 会刻意把工具进度事件和回答令牌交错输出，
     * 让前端在最终回答仍在生成时也能展示观测结果。</p>
     *
     * @param request 用户、会话、文本和可选图片/重生成标记
     * @return JSON 字符串组成的冷 Flux；订阅后才执行预取和模型生成
     */
    public Flux<String> streamChat(ChatRequest request) {
        if (request.getSubmissionId() != null) {
            var submitted = researchTaskService.findSubmission(researchTaskService.buildRunSubmissionKey(
                    request.getUserId(), request.getSubmissionId()));
            if (submitted.isPresent()) {
                return researchTaskObservationService.observeChat(submitted.get(), request.getUserId());
            }
        }
        PreparedTurn turn = prepareTurn(request);

        ChatStreamSession stream = new ChatStreamSession(turn.traceId(), turn.conversationId(), turn.startTime(),
                streamHeartbeatSeconds, objectMapper, traceService, traceEventRelay,
                chatStreamEmitter, toolCallEventBus);
        StringBuilder fullResponse = new StringBuilder();
        Flux<String> conversationStarted = Flux.just(stream.toJson(ChatChunk.builder()
                .type("meta")
                .content("")
                .traceId(turn.traceId())
                .conversationId(turn.conversationId())
                .build()));
        Flux<String> executionPlanStarted = Flux.just(stream.toJson(ChatChunk.builder()
                .type("thought")
                .content(turn.executionPlan().thought() + "\n" + turn.executionPlan().observation())
                .traceId(turn.traceId())
                .conversationId(turn.conversationId())
                .build()));
        Flux<String> routeDecisionStarted = turn.routingDecision() == null
                ? Flux.empty()
                : Flux.just(stream.toJson(ChatChunk.builder()
                        .type("route_decision")
                        .content(formatRouteDecision(turn.routingDecision()))
                        .traceId(turn.traceId())
                        .conversationId(turn.conversationId())
                        .metadata(turn.routeAttributes())
                        .build()));
        Flux<String> retrievalObservation = turn.retrievedDocs().isEmpty()
                ? Flux.empty()
                : Flux.just(stream.toJson(ChatChunk.builder()
                        .type("observation")
                        .content("知识库召回 " + turn.retrievedDocs().size() + " 个候选片段，模型会结合其相关性判断是否采用。")
                        .traceId(turn.traceId())
                        .conversationId(turn.conversationId())
                        .build()));

        Flux<String> answerStream = Flux.defer(() -> {
            // 注册按链路隔离的事件通道。@Tool 方法开始或完成时，工具调用切面会向该通道写入事件。
            ToolCallContext.register(turn.traceId(), turn.conversationId(), request.getMessage());
            ToolCallContext.set(turn.traceId(), turn.conversationId(), turn.resolvedQuery());

            // 5. 在发送最终回答提示词前执行确定性预取。
            // 两类最终回答客户端都不持有工具；DEEP 只切换到证据整理提示和更强模型层级。
            boolean preparedContextOnly = toolPrefetchService.isDeepResearchPlan(turn.executionPlan().actions());
            Coordinator.SelectedModel selectedModel = coordinator.selectFinalAnswerModel(turn.executionPlan().modelTier(), preparedContextOnly, turn.hasImages());
            String pinnedUserMemory = !turn.needsIntentClarification() && turn.executionPlan().route() == PlanRoute.FUNDAMENTALS
                    ? longTermMemory.buildPromptContext(request.getUserId()) : null;
            FundamentalsRuntimeIdentity.Request methodRequest = turn.executionPlan().route() == PlanRoute.FUNDAMENTALS && !turn.needsIntentClarification()
                    ? new FundamentalsRuntimeIdentity.Request(request.getMessage(), coordinator.finalAnswerInvocation(selectedModel),
                        chatPromptAssembler.promptMaxTextChars(), !turn.hasImages() && !preparedContextOnly
                            && selectedModel.tier() == com.stocksage.agent.ModelTier.STANDARD) : null;
            // 澄清是硬执行闸门：不做 RAG/记忆/工具/Agent 预取，更不能提交 DEEP 后台任务。
            // 参数与多标的问题直接回固定澄清；只是意图没识别出来时，仍不取数，但交给回答模型依据本轮可见的对话回答或澄清。
            boolean clarificationBlocked = !turn.parameterClarification().isBlank()
                    || turn.routingDecision().reasonCodes().contains(Coordinator.MULTI_TARGET_UNSUPPORTED)
                    || turn.routingDecision().reasonCodes().contains(Coordinator.MULTI_TARGET_PARAMETERS_DIFFER)
                    || turn.routingDecision().reasonCodes().contains(Coordinator.TARGET_REWRITE_MISMATCH);
            if (turn.needsIntentClarification() && !clarificationBlocked) {
                recordIntentClarificationDeferred(turn.traceId());
            }
            ToolPrefetchService.PreparedToolContext preparedToolContext = turn.needsIntentClarification()
                    ? new ToolPrefetchService.PreparedToolContext(
                            "", !turn.parameterClarification().isBlank() ? turn.parameterClarification()
                                    : clarificationBlocked ? buildIntentClarificationQuestion(turn.routingDecision()) : "",
                            null, turn.traceId(), clarificationBlocked ? "BLOCKED" : null)
                    : toolPrefetchService.prefetch(
                            turn.executionPlan(),
                            turn.resolvedQuery(),
                            turn.traceId(),
                            turn.conversationId(),
                            request.getUserId(),
                            selectedModel,
                            request.getSubmissionId(), methodRequest, turn.retrievedTickers(),
                            request.getEvaluationMode()
                    );
            stream.relayPrepared(preparedToolContext.eventTraceId(),
                    preparedToolContext.submittedTaskId() != null);

            if (preparedToolContext.hasDirectAnswer()) {
                return streamPreparedDirectAnswer(
                        preparedToolContext.directAnswer(),
                        request,
                        turn.conversation(),
                        turn.newConversation(),
                        turn.traceId(),
                        turn.conversationId(),
                        turn.sourceMessageId(),
                        turn.sourceObservedAt(),
                        turn.startTime(),
                        fullResponse,
                        stream,
                        preparedToolContext.taskOutcome()
                );
            }
            return streamModelAnswer(turn, request, preparedToolContext, selectedModel,
                    preparedContextOnly, pinnedUserMemory, fullResponse, stream);
        }).onErrorResume(error -> {
            stream.preparationFailed();
            if (stream.tryRecordTerminal()) {
                long durationMs = System.currentTimeMillis() - turn.startTime();
                traceService.endTrace(turn.traceId(), "error", 0, durationMs);
                log.error("Chat preparation failed, conversationId={}, traceId={}", turn.conversationId(), turn.traceId(), error);
            }
            stream.finishForeground();
            return Flux.just(stream.toJson(ChatChunk.builder()
                    .type("error")
                    .content(chatFailureMessage(error))
                    .traceId(turn.traceId())
                    .conversationId(turn.conversationId())
                    .build()));
        }).transform(ChatService::clearToolContextAfterSubscribe)
                // 取消可能发生在回答流订阅前；后台任务的上下文由 finishRelay 在事件流结束时注销。
                .doFinally(signalType -> {
                    if (!stream.hasBackgroundTask()) ToolCallContext.unregister(turn.traceId());
                })
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(conversationStarted, routeDecisionStarted, executionPlanStarted, retrievalObservation,
                stream.mergeWith(answerStream));
    }

    /**
     * 同步准备一轮对话：持久化用户轮次、识别意图与标的、检索知识库和研究记忆，并生成执行计划和对应 Trace。
     *
     * <p>只做不调用 @Tool 的准备工作；工具上下文在订阅回答流后才注册。</p>
     */
    private PreparedTurn prepareTurn(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        List<Media> imageMedia = imageAttachmentService.normalizeImageAttachments(request.getImages());
        boolean hasImages = !imageMedia.isEmpty();
        request.setMessage(imageAttachmentService.normalizeUserMessage(request.getMessage(), hasImages));
        boolean newConversation = request.getConversationId() == null;

        // 1. 创建或加载会话，并先持久化新的用户轮次，
        // 确保重试、取消和链路查询都指向真实记录。
        Conversation conversation = getOrCreateConversation(request);
        Long conversationId = conversation.getId();
        String traceId = traceService.startTrace(request.getUserId(), conversationId, request.getMessage());

        ConversationMessageService.UserTurn userTurn = beginUserTurn(request, conversationId);
        List<String> routingTurns = userTurn.routingTurns();
        Message sourceMessage = userTurn.sourceMessage();
        Long sourceMessageId = sourceMessage.getId();
        LocalDateTime sourceObservedAt = sourceMessage.getCreatedAt();

        // 2. 先结合最近三轮识别意图并补全指代。该阶段只选择 targetRoute 和 resolvedQuery，
        // 不允许模型选择任何工具、动作或具体模型。
        List<String> intentTickerCandidates = contextualTickerCandidates(request.getMessage(), routingTurns);
        IntentRecognitionResult intentRecognition = coordinator.recognizeIntent(
                request.getMessage(), routingTurns, 0, hasImages, intentTickerCandidates);
        String resolvedQuery = intentRecognition.decision().resolvedQuery().isBlank()
                ? request.getMessage()
                : intentRecognition.decision().resolvedQuery();
        String parameterClarification = coordinator.inspectRequest(intentRecognition, request.getMessage())
                .clarification();
        boolean needsIntentClarification = intentRecognition.decision().needsClarification()
                || !parameterClarification.isBlank();
        String canonicalTicker = tickerResolutionService.resolveExplicitTicker(resolvedQuery);
        List<String> tickerCandidates = canonicalTicker.isBlank()
                ? extractTickerCandidates(resolvedQuery)
                : List.of(canonicalTicker);
        if (tickerCandidates.isEmpty()) {
            tickerCandidates = intentTickerCandidates;
        }
        if (canonicalTicker.isBlank() && !tickerCandidates.isEmpty()) {
            canonicalTicker = tickerResolutionService.resolveExplicitTicker(tickerCandidates.get(0));
        }

        // 3. 工具上下文改在订阅后的 answerStream 内注册：请求线程的同步阶段不调用 @Tool，
        // 这样请求线程不会残留 ThreadLocal，流建立前抛异常也不会遗留活跃上下文。

        // 4. 使用已经消歧的独立问题检索知识库；检索失败不阻断对话体验。
        long retrievalStart = System.currentTimeMillis();
        boolean needsKnowledgeRetrieval = !needsIntentClarification
                && (intentRecognition.decision().targetRoute() == PlanRoute.DIRECT
                || intentRecognition.decision().targetRoute() == PlanRoute.FUNDAMENTALS);
        List<String> fanOutTargets = needsIntentClarification
                ? List.of() : coordinator.fanOutTargets(intentRecognition, request.getMessage());
        List<Document> retrievedDocs = !needsKnowledgeRetrieval
                ? List.of()
                : safeRetrieve(resolvedQuery, canonicalTicker, fanOutTargets, traceId, retrievalStart);
        Set<String> retrievedTickers = retrievedDocs.stream()
                .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("ticker", "")).toUpperCase(Locale.ROOT))
                .filter(ticker -> !ticker.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        long retrievalDuration = System.currentTimeMillis() - retrievalStart;
        if (!retrievedDocs.isEmpty()) {
            String sources = buildRagTraceSources(retrievedDocs);
            traceService.addStep(traceId, AgentStep.builder()
                    .index(0)
                    .thought("Retrieved " + retrievedDocs.size() + " relevant document(s) from knowledge base.")
                    .action("Knowledge Retrieval")
                    .actionInput(request.getMessage())
                    .observation(sources)
                    .attributes(Map.of("kind", "knowledge-retrieval", "requestedTargets", fanOutTargets,
                            "retrieved", retrievedDocs.stream().map(doc -> Map.of(
                                    "ticker", String.valueOf(doc.getMetadata().getOrDefault("ticker", "")),
                                    "filingType", String.valueOf(doc.getMetadata().getOrDefault("filing_type", "")))).toList()))
                    .durationMs(retrievalDuration)
                    .tokenCount(0)
                    .build());
        }
        // 调用用户隔离的研究记忆检索；结果明确服从当前 RAG/工具证据，不命中或失败即空上下文。
        ResearchMemoryService.RetrievalResult researchMemory = needsIntentClarification
                || intentRecognition.decision().targetRoute() == PlanRoute.DEEP
                ? ResearchMemoryService.RetrievalResult.empty()
                : researchMemoryService.retrieve(new ResearchMemoryQuery(
                        request.getUserId(),
                        tickerCandidates.isEmpty() ? "" : tickerCandidates.get(0),
                        resolvedQuery,
                        traceId,
                        intentRecognition.decision().analysisDepth(),
                        intentRecognition.decision().timeSensitivity()
                ));

        // 5. targetRoute 已由意图融合直接给出；Coordinator 只查询服务器端计划目录。
        ExecutionPlan executionPlan = coordinator.planRecognized(
                intentRecognition, request.getMessage(), retrievedDocs.size());
        RoutingDecisionMetadata routingDecision = executionPlan.routingDecision();
        Map<String, Object> routeAttributes = new java.util.LinkedHashMap<>(buildRouteAttributes(executionPlan));
        if (!intentRecognition.modelObservation().isEmpty()) {
            routeAttributes.put("modelObservation", intentRecognition.modelObservation());
        }
        routingDecisionObserver.record(routingDecision);
        traceService.addStep(traceId, AgentStep.builder()
                .thought(executionPlan.thought())
                .action("Coordinate Request")
                .actionInput(request.getMessage())
                .observation(executionPlan.observation())
                .durationMs(routingDecision == null ? 0L : routingDecision.durationMs())
                .tokenCount(0)
                .attributes(routeAttributes)
                .build());
        if (executionPlan.route() == PlanRoute.MARKET || executionPlan.route() == PlanRoute.FUNDAMENTALS
                || executionPlan.route() == PlanRoute.NEWS) {
            // 执行、Trace 与前端读同一份最终计划。
            traceService.addStep(traceId, AgentStep.builder()
                    .action("Plan")
                    .observation("计划来源：" + executionPlan.ordinaryPlan().source()
                            + (executionPlan.ordinaryPlan().multiTarget()
                            ? "；对比标的：" + String.join("、", executionPlan.ordinaryPlan().targets()) : ""))
                    .attributes(executionPlan.ordinaryPlan().attributes())
                    .build());
        }
        return new PreparedTurn(startTime, imageMedia, hasImages, newConversation, conversation, conversationId,
                traceId, sourceMessageId, sourceObservedAt, resolvedQuery, parameterClarification, needsIntentClarification,
                retrievedDocs, retrievedTickers, researchMemory, executionPlan, routingDecision, routeAttributes);
    }

    /** 拼装提示词并流式输出最终回答；完成、失败或取消时各自落库消息、关闭 Trace 并触发后台任务。 */
    private Flux<String> streamModelAnswer(
            PreparedTurn turn,
            ChatRequest request,
            ToolPrefetchService.PreparedToolContext preparedToolContext,
            Coordinator.SelectedModel selectedModel,
            boolean preparedContextOnly,
            String pinnedUserMemory,
            StringBuilder fullResponse,
            ChatStreamSession stream
    ) {
        // 走到这里仍标记澄清的，只能是意图未识别、已放行给回答模型的分支。
        List<org.springframework.ai.chat.messages.Message> promptMessages =
                buildPromptMessages(request.getUserId(), turn.conversationId(), preparedToolContext,
                        turn.retrievedDocs(), turn.researchMemory().promptContext(),
                        pinnedUserMemory == null ? longTermMemory.buildPromptContext(request.getUserId()) : pinnedUserMemory,
                        turn.imageMedia(), preparedContextOnly, turn.traceId(),
                        turn.needsIntentClarification() ? INTENT_UNCERTAIN_INSTRUCTION : null);
        Flux<String> modelStarted = Flux.just(stream.toJson(ChatChunk.builder()
                .type("model")
                .content(selectedModel.modelName())
                .modelTier(selectedModel.tier().name())
                .modelName(selectedModel.modelName())
                .traceId(turn.traceId())
                .conversationId(turn.conversationId())
                .build()));
        // 调用 Coordinator 选择的无工具最终 ChatClient 流。
        AtomicReference<ModelCompletion> answerCompletion = new AtomicReference<>(
                new ModelCompletion(ModelCompletion.Status.UNKNOWN, ""));
        Flux<String> responseTokens = coordinator.streamAnswer(promptMessages, preparedContextOnly,
                selectedModel.tier(), turn.hasImages(), attributes -> recordAnswerObservation(turn.traceId(), attributes), answerCompletion::set);

        return Flux.concat(modelStarted, responseTokens
            .doFirst(() -> ToolCallContext.set(turn.traceId(), turn.conversationId(), turn.resolvedQuery()))
            .map(token -> {
                fullResponse.append(token);
                return stream.toJson(ChatChunk.builder()
                        .type("answer")
                        .content(token)
                        .traceId(turn.traceId())
                        .conversationId(turn.conversationId())
                        .build());
            })
            .doOnComplete(() -> {
                long durationMs = System.currentTimeMillis() - turn.startTime();
                if (stream.tryRecordTerminal()) {
                    String assistantText = fullResponse.toString();
                    Message saved = persistAssistantMessage(turn.conversationId(), request.getUserId(), assistantText, turn.traceId(), selectedModel);
                    shortTermMemory.addMessage(turn.conversationId(), "assistant",
                            ShortTermMemory.assistantMemoryContent(assistantText, saved.getCreatedAt()));
                    submitBackground("memory-compress", turn.conversationId(), () -> shortTermMemory.compressIfNeeded(turn.conversationId()));
                    maybeGenerateConversationTitle(turn.conversationId(), request.getUserId(), request.getMessage(),
                            turn.newConversation(), turn.conversation().getOrigin());
                    submitBackground("profile-extraction", turn.conversationId(), () -> longTermMemory.extractAndUpdate(
                            request.getUserId(), assistantText, request.getMessage(),
                            turn.sourceMessageId(), turn.sourceObservedAt()));
                    int estimatedTokens = estimateTokens(promptMessages, assistantText);
                    traceService.addStep(turn.traceId(), AgentStep.builder()
                            .thought("Generated streamed assistant answer.")
                            .action(null)
                            .actionInput(null)
                            .observation(null)
                            .durationMs(durationMs)
                            .tokenCount(0)
                            .attributes(Map.of("kind", "answer-completion", "usageScope", "final-answer",
                                    "legacyTotalTokensSource", "CHARACTER_ESTIMATE", "estimatedTotalTokens", estimatedTokens))
                            .build());
                    String taskOutcome = preparedToolContext.outcomeForAnswer(assistantText, answerCompletion.get());
                    traceService.endTrace(turn.traceId(), "success", estimatedTokens, durationMs, taskOutcome);
                    if (turn.executionPlan().route() == PlanRoute.FUNDAMENTALS) {
                        submitBackground("evolution-failure-capture", turn.conversationId(),
                                () -> failurePool.recordOrdinaryOutcome(turn.traceId(), taskOutcome));
                    }
                    log.info("Chat completed, conversationId={}, traceId={}, responseLength={}",
                            turn.conversationId(), turn.traceId(), fullResponse.length());
                }
            })
            .onErrorResume(e -> {
                long durationMs = System.currentTimeMillis() - turn.startTime();
                if (stream.tryRecordTerminal()) {
                    String partial = fullResponse.toString();
                    traceService.addStep(turn.traceId(), AgentStep.builder()
                            .thought("Streaming chat failed.")
                            .action(null)
                            .actionInput(null)
                            .observation(e.getMessage())
                            .durationMs(durationMs)
                            .tokenCount(0)
                            .build());
                    traceService.endTrace(turn.traceId(), "error", 0, durationMs,
                            preparedToolContext.outcomeForAnswer(partial, answerCompletion.get()));
                    log.error("Streaming chat failed, conversationId={}, traceId={}", turn.conversationId(), turn.traceId(), e);
                }
                return Flux.just(stream.toJson(ChatChunk.builder()
                        .type("error")
                        .content(chatFailureMessage(e))
                        .traceId(turn.traceId())
                        .conversationId(turn.conversationId())
                        .build()));
            })
            .doFinally(signalType -> {
                if (signalType == SignalType.CANCEL && stream.tryRecordTerminal()) {
                    long durationMs = System.currentTimeMillis() - turn.startTime();
                    if (!fullResponse.isEmpty()) {
                        persistAssistantMessage(turn.conversationId(), request.getUserId(), fullResponse.toString(), turn.traceId(), selectedModel);
                    }
                    traceService.addStep(turn.traceId(), AgentStep.builder()
                            .thought("Streaming chat was cancelled by the client.")
                            .action(null)
                            .actionInput(null)
                            .observation(null)
                            .durationMs(durationMs)
                            .tokenCount(0)
                            .build());
                    traceService.endTrace(turn.traceId(), "cancelled", 0, durationMs);
                    log.info("Chat cancelled, conversationId={}, traceId={}, responseLength={}",
                            turn.conversationId(), turn.traceId(), fullResponse.length());
                }
                stream.finishForeground();
            })
            .transform(ChatService::clearToolContextAfterSubscribe));
    }

    /** 一轮对话在同步准备阶段确定的会话、检索、记忆与执行计划。 */
    private record PreparedTurn(
            long startTime,
            List<Media> imageMedia,
            boolean hasImages,
            boolean newConversation,
            Conversation conversation,
            Long conversationId,
            String traceId,
            Long sourceMessageId,
            LocalDateTime sourceObservedAt,
            String resolvedQuery,
            String parameterClarification,
            boolean needsIntentClarification,
            List<Document> retrievedDocs,
            Set<String> retrievedTickers,
            ResearchMemoryService.RetrievalResult researchMemory,
            ExecutionPlan executionPlan,
            RoutingDecisionMetadata routingDecision,
            Map<String, Object> routeAttributes
    ) {
    }

    /** 订阅期间同步设置的工具上下文在订阅返回后于同一线程清理，避免池化线程残留到下一次请求。 */
    private static <T> Flux<T> clearToolContextAfterSubscribe(Flux<T> source) {
        return Flux.from(subscriber -> {
            try {
                source.subscribe(subscriber);
            } finally {
                ToolCallContext.clear();
            }
        });
    }

    private static String chatFailureMessage(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelCompletion.IncompleteOutputException incomplete) return incomplete.getMessage();
        }
        ResearchCapacityExceededException capacity = ResearchCapacityExceededException.find(error);
        return capacity == null ? "服务暂时出错，请稍后重试。" : capacity.getMessage();
    }

    private Flux<String> streamPreparedDirectAnswer(
            String directAnswer,
            ChatRequest request,
            Conversation conversation,
            boolean newConversation,
            String traceId,
            Long conversationId,
            Long sourceMessageId,
            LocalDateTime sourceObservedAt,
            long startTime,
            StringBuilder fullResponse,
            ChatStreamSession stream,
            String taskOutcome
    ) {
        String assistantText = directAnswer == null ? "" : directAnswer.trim();
        fullResponse.append(assistantText);
        return Flux.just(stream.toJson(ChatChunk.builder()
                        .type("answer")
                        .content(assistantText)
                        .traceId(traceId)
                        .conversationId(conversationId)
                        .build()))
                .doOnComplete(() -> {
                    if (!assistantText.isBlank()) {
                        Message saved = persistAssistantMessage(conversationId, request.getUserId(), assistantText, traceId, null);
                        shortTermMemory.addMessage(conversationId, "assistant",
                                ShortTermMemory.assistantMemoryContent(assistantText, saved.getCreatedAt()));
                        submitBackground("memory-compress", conversationId, () -> shortTermMemory.compressIfNeeded(conversationId));
                        maybeGenerateConversationTitle(
                                conversationId,
                                request.getUserId(),
                                request.getMessage(),
                                newConversation,
                                conversation.getOrigin()
                        );
                    }
                    long durationMs = System.currentTimeMillis() - startTime;
                    if (stream.hasBackgroundTask()) {
                        traceService.addStep(traceId, AgentStep.builder()
                                .thought("Accepted DEEP research task for background execution.")
                                .durationMs(durationMs)
                                .tokenCount(0)
                                .build());
                        log.info("Background research accepted, conversationId={}, traceId={}",
                                conversationId, traceId);
                        return;
                    }
                    if (stream.tryRecordTerminal()) {
                        submitBackground("profile-extraction", conversationId, () -> longTermMemory.extractAndUpdate(
                                request.getUserId(), assistantText, request.getMessage(),
                                sourceMessageId, sourceObservedAt));
                        traceService.endTrace(
                                traceId,
                                "FAILED".equals(taskOutcome) ? "error" : "success",
                                0,
                                durationMs,
                                taskOutcome
                        );
                        log.info("Direct chat answer completed, conversationId={}, traceId={}, responseLength={}",
                                conversationId, traceId, fullResponse.length());
                    }
                })
                .doFinally(signalType -> {
                    if (stream.hasBackgroundTask()) {
                        return;
                    }
                    if (signalType == SignalType.CANCEL && stream.tryRecordTerminal()) {
                        traceService.endTrace(
                                traceId,
                                "cancelled",
                                0,
                                System.currentTimeMillis() - startTime
                        );
                    }
                    stream.finishForeground();
                });
    }

    /**
     * 列出用户普通聊天会话，排除工作台来源。
     *
     * @param userId 当前用户 ID
     * @return 按更新时间倒序的会话
     */
    public List<Conversation> listConversations(String userId) {
        return conversationMessageService.listConversations(userId);
    }

    /**
     * 校验归属后列出完整消息历史。
     *
     * @param userId 当前用户 ID
     * @param conversationId 会话 ID
     * @return 按创建时间正序的消息
     */
    public List<Message> listMessages(String userId, Long conversationId) {
        return conversationMessageService.listMessages(userId, conversationId);
    }

    /**
     * 后台研究完成后复用同一条会话消息落库契约，并同步短期记忆。
     *
     * @param conversationId 目标会话
     * @param userId 任务所属用户
     * @param text 最终报告 Markdown
     * @param traceId 后台任务 trace ID
     */
    public void persistAssistantReport(Long conversationId, String userId, String text, String traceId) {
        conversationMessageService.persistAssistantReport(conversationId, userId, text, traceId);
        rebuildShortTermMemory(conversationId, conversationMessageService.listMessages(userId, conversationId));
        submitBackground("memory-compress", conversationId, () -> shortTermMemory.compressIfNeeded(conversationId));
    }

    /**
     * 删除归属当前用户的会话、全部消息和 Redis 短期记忆。
     *
     * @param userId 当前用户 ID
     * @param conversationId 会话 ID
     */
    public void deleteConversation(String userId, Long conversationId) {
        conversationMessageService.deleteConversation(userId, conversationId);
        shortTermMemory.clear(conversationId);
        log.info("Conversation deleted, userId={}, conversationId={}", userId, conversationId);
    }

    /**
     * 读取本轮会话和画像，调用纯上下文组装器，并记录实际发送内容的预算与归因。
     */
    private List<org.springframework.ai.chat.messages.Message> buildPromptMessages(
            String userId,
            Long conversationId,
            ToolPrefetchService.PreparedToolContext preparedToolContext,
            List<Document> retrievedDocs,
            String researchMemoryContext,
            String userMemoryContext,
            List<Media> imageMedia,
            boolean preparedContextOnly,
            String traceId,
            String turnInstruction
    ) {
        List<Message> history = conversationMessageService.listMessages(userId, conversationId);
        List<org.springframework.ai.chat.messages.Message> shortTermMessages =
                buildShortTermPromptMessages(conversationId, history, imageMedia);
        ChatPromptAssembler.Assembly assembly = chatPromptAssembler.assemble(
                shortTermMessages, preparedToolContext, retrievedDocs, researchMemoryContext,
                userMemoryContext, imageMedia != null && !imageMedia.isEmpty(),
                preparedContextOnly, LocalDate.now(), turnInstruction);
        if (traceId != null) {
            recordAnswerObservation(traceId,
                    Map.of("kind", "prompt-budget", "usedChars", assembly.usedChars(),
                            "maxChars", assembly.maxChars(), "historyChars", assembly.historyChars(),
                            "dynamicContextChars", assembly.context().length(),
                            "toolContextChars", assembly.toolContextChars(),
                            "toolRemainingChars", assembly.toolRemainingChars(),
                            "toolBudgetRatio", assembly.toolBudgetRatio()));
        }
        recordAnswerContext(traceId, assembly.messages(), assembly.context(), assembly.evidenceContext(),
                assembly.hasImages(), assembly.evidenceCaptureComplete(), assembly.analystSpan());
        return assembly.messages();
    }

    private void recordAnswerContext(String traceId,
                                     List<org.springframework.ai.chat.messages.Message> messages,
                                     String context, String evidenceContext,
                                     boolean hasImages, boolean evidenceCaptureComplete,
                                     ChatPromptAssembler.AnalystSpan analystSpan) {
        try {
            List<Map<String, String>> renderedMessages = messages.stream().map(message -> {
                Map<String, String> rendered = new LinkedHashMap<>();
                rendered.put("role", message.getMessageType().getValue());
                rendered.put("text", message.getText() == null ? "" : message.getText());
                return rendered;
            }).toList();
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("kind", "answer-context");
            attributes.put("schemaVersion", 1);
            attributes.put("scope", "ordinary-final-answer");
            attributes.put("promptFingerprintScope", "TEXT_ONLY");
            attributes.put("messages", renderedMessages);
            attributes.put("promptSha256", KnowledgeIngestionService.sha256(objectMapper.writeValueAsString(renderedMessages)));
            attributes.put("context", context);
            attributes.put("contextSha256", KnowledgeIngestionService.sha256(context));
            attributes.put("evidenceContext", evidenceContext);
            attributes.put("evidenceSha256", KnowledgeIngestionService.sha256(evidenceContext));
            attributes.put("hasImages", hasImages);
            attributes.put("evidenceCaptureComplete", evidenceCaptureComplete);
            if (analystSpan != null) {
                String text = messages.get(analystSpan.messageIndex()).getText();
                String draft = text.substring(text.offsetByCodePoints(0, analystSpan.start()),
                        text.offsetByCodePoints(0, analystSpan.end()));
                attributes.put("analystSpan", Map.of("schemaVersion", 1, "messageIndex", analystSpan.messageIndex(),
                        "start", analystSpan.start(), "end", analystSpan.end(), "offsetUnit", "UNICODE_CODE_POINT",
                        "removedTextSha256", KnowledgeIngestionService.sha256(draft)));
            }
            recordAnswerObservation(traceId, attributes);
        } catch (Exception error) {
            log.warn("Answer context observation unavailable, traceId={}, errorType={}",
                    traceId, error.getClass().getSimpleName());
        }
    }

    private void recordAnswerObservation(String traceId, Map<String, Object> attributes) {
        try {
            String action = "prompt-budget".equals(attributes.get("kind")) ? "Prompt Budget" : "Answer Attribution";
            traceService.addStep(traceId, AgentStep.builder().action(action)
                    .attributes(attributes).build());
        } catch (Exception error) {
            // Observation failure must not turn a completed model call into a retry and duplicate its cost.
            log.warn("Answer attribution unavailable, traceId={}, errorType={}",
                    traceId, error.getClass().getSimpleName());
        }
    }

    private List<org.springframework.ai.chat.messages.Message> buildShortTermPromptMessages(
            Long conversationId,
            List<Message> history,
            List<Media> imageMedia
    ) {
        List<String> memoryEntries = shortTermMemory.getContext(conversationId);
        String expectedCurrentEntry = history == null || history.isEmpty()
                ? ""
                : toMemoryMessage(history.get(history.size() - 1));
        boolean currentEntryMissing = expectedCurrentEntry.isBlank()
                || memoryEntries.isEmpty()
                || !expectedCurrentEntry.equals(memoryEntries.get(memoryEntries.size() - 1));
        if (currentEntryMissing && history != null && !history.isEmpty()) {
            rebuildShortTermMemory(conversationId, history);
            memoryEntries = shortTermMemory.getContext(conversationId);
            if (memoryEntries.isEmpty()
                    || !expectedCurrentEntry.equals(memoryEntries.get(memoryEntries.size() - 1))) {
                memoryEntries = shortTermMemory.restoreContext(conversationId, history);
            }
        }

        List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        for (int i = 0; i < memoryEntries.size(); i++) {
            MemoryEntry entry = parseMemoryEntry(memoryEntries.get(i));
            boolean currentUserMessage = i == memoryEntries.size() - 1 && "user".equals(entry.role());
            if (currentUserMessage && imageMedia != null && !imageMedia.isEmpty()) {
                messages.add(UserMessage.builder()
                        .text(entry.content())
                        .media(imageMedia)
                        .build());
            } else {
                messages.add(toAiMessage(entry.role(), entry.content()));
            }
        }
        return messages;
    }

    private MemoryEntry parseMemoryEntry(String value) {
        if (value == null || value.isBlank()) {
            return new MemoryEntry("user", "");
        }
        int separator = value.indexOf(": ");
        if (separator <= 0) {
            return new MemoryEntry("user", value.trim());
        }
        String role = value.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        String content = value.substring(separator + 2).trim();
        if (!Set.of("user", "assistant", "system").contains(role)) {
            role = "user";
        }
        return new MemoryEntry(role, content);
    }

    /**
     * 安全执行 RAG 检索，失败时记录追踪步骤并返回空列表。
     */
    private List<Document> safeRetrieve(String query,
                                        String canonicalTicker,
                                        List<String> fanOutTargets,
                                        String traceId,
                                        long retrievalStart) {
        try {
            if (fanOutTargets.size() >= 2) {
                return ragService.retrieveForTickers(query, fanOutTargets);
            }
            return canonicalTicker == null || canonicalTicker.isBlank()
                    ? ragService.retrieve(query)
                    : ragService.retrieveForTicker(query, canonicalTicker);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - retrievalStart;
            log.warn("RAG retrieval failed, continuing without knowledge context. traceId={}, message={}",
                    traceId, e.getMessage());
            traceService.addStep(traceId, AgentStep.builder()
                    .thought("Knowledge retrieval failed; continuing without RAG context.")
                    .action("Knowledge Retrieval")
                    .actionInput(query)
                    .observation(e.getMessage())
                    .durationMs(durationMs)
                    .tokenCount(0)
                    .build());
            return List.of();
        }
    }

    /**
     * 构造写入追踪面板的 RAG 命中来源摘要。
     */
    private String buildRagTraceSources(List<Document> retrievedDocs) {
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document doc = retrievedDocs.get(i);
            sources.append(chatPromptAssembler.formatCitationSource(i + 1, doc)).append("\n");
            String snippet = doc.getText().length() > 150
                    ? doc.getText().substring(0, 150) + "..."
                    : doc.getText();
            sources.append("  ").append(snippet).append("\n");
        }
        return sources.toString().trim();
    }

    private String formatRouteDecision(RoutingDecisionMetadata decision) {
        StringBuilder text = new StringBuilder()
                .append("意图理解：").append(decision.intentSummary())
                .append("\n细粒度意图：").append(decision.fineIntent())
                .append(" / ").append(decision.intentGroup())
                .append("\n选择路由：").append(decision.route().name())
                .append("\n决策来源：").append(decision.decisionSource().name())
                .append("\n置信度：").append(String.format(Locale.ROOT, "%.2f", decision.confidence()))
                .append("\n时效/深度：").append(decision.timeSensitivity())
                .append(" / ").append(decision.analysisDepth())
                .append("\n依据：").append(decision.rationale())
                .append("\nRAG 命中：").append(decision.ragHitCount());
        if (!decision.sourceScores().isEmpty()) {
            text.append("\n信号分数：").append(decision.sourceScores());
        }
        if (decision.needsClarification()) {
            text.append("\n需要澄清：是");
        }
        if (!decision.fallbackReason().isBlank()) {
            text.append("\n降级原因：").append(decision.fallbackReason());
        }
        return text.toString();
    }

    /** 在唯一计划动作上派生角色展示字段，避免再维护一份角色路由表。 */
    private Map<String, Object> buildRouteAttributes(ExecutionPlan plan) {
        if (plan == null || plan.routingDecision() == null) {
            return Map.of();
        }
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(plan.routingDecision().toAttributes());
        attributes.put("plannedPrimaryAgent", plan.primaryAgent());
        attributes.put("plannedSupportingAgents", plan.supportingAgents());
        attributes.put("readRequest", plan.readRequest().attributes());
        return Map.copyOf(attributes);
    }

    /**
     * 意图未识别时给回答模型的本轮说明：本轮没有检索也没有取数，只能依据当前可见的对话记录、摘要和画像。
     * 这些记忆都受预算限制，找不到不等于用户没说过；需要最新数据时不能拿历史数字顶替。
     */
    private static final String INTENT_UNCERTAIN_INSTRUCTION = """
            【本轮说明】本轮问题的意图没能可靠识别，这次没有获取任何行情、财务或新闻数据，也没有知识库资料。
            - 本轮可见的对话记录、会话摘要或用户画像能回答这个问题时，直接回答；用"你之前提到"这类说法点明出处即可。
            - 需要最新数据才能回答时，说明这次没有查询最新数据，请用户说明标的和要看的内容；对话里出现过的历史数字不能当作当前数据。
            - 找不到相关内容时，说明"在目前能看到的对话记录里没有找到"并请用户补充；不要断言用户从未说过，也不要编造。
            - 向用户说明时不要提及后端、检索、会话摘要、意图识别、本轮说明这些内部机制。
            """;

    /** 意图未识别、交给回答模型处理时在 Trace 留一步，便于和固定澄清区分。 */
    private void recordIntentClarificationDeferred(String traceId) {
        try {
            traceService.addStep(traceId, AgentStep.builder().action("Intent Clarification")
                    .observation("意图未能确定：不检索、不取数，交给回答模型依据本轮可见的对话记录回答或澄清。")
                    .attributes(Map.of("kind", "intent-clarification", "mode", "ANSWER_FROM_VISIBLE_CONTEXT"))
                    .build());
        } catch (Exception error) {
            log.warn("Intent clarification trace unavailable, traceId={}, errorType={}",
                    traceId, error.getClass().getSimpleName());
        }
    }

    /** 参数缺失或多标的结构问题时直接返回一个可回答的澄清问题，不再调用工具或第二个模型。 */
    private String buildIntentClarificationQuestion(RoutingDecisionMetadata decision) {
        if (decision != null && decision.reasonCodes().contains(Coordinator.MULTI_TARGET_PARAMETERS_DIFFER)) {
            return "这次对比的几个标的要求了不同的期间、粒度或年报/季报口径。请为所有标的指定同一种取数要求后再对比。";
        }
        if (decision != null && decision.reasonCodes().contains(Coordinator.TARGET_REWRITE_MISMATCH)) {
            return "识别结果中的股票与本轮明确指定的标的不一致，已停止检索和取证。请重新发送股票代码及查询要求。";
        }
        if (decision != null && decision.reasonCodes().contains(Coordinator.MULTI_TARGET_UNSUPPORTED)) {
            return "当前一次只支持分析一个股票或公司。请先选择一个标的，再告诉我你要看行情、财报、新闻还是综合研究。";
        }
        String subject = "";
        if (decision != null) {
            subject = decision.entities().getOrDefault(
                    "ticker", decision.entities().getOrDefault("company", ""));
        }
        if (!subject.isBlank()) {
            return "关于 " + subject + "，你希望我看行情、财报、最新新闻，还是做综合投资研究？";
        }
        return "请告诉我具体股票或公司，以及你希望看行情、财报、最新新闻，还是做综合研究？";
    }

    /**
     * 以中英混合内容每两个字符约一个 token，粗略估算本轮提示词和回答用量。
     *
     * @param promptMessages 发送给模型的消息列表
     * @param response 模型回答，可为空
     * @return 仅用于用量记录的近似 token 数
     */
    private int estimateTokens(List<org.springframework.ai.chat.messages.Message> promptMessages, String response) {
        int promptChars = promptMessages.stream()
                .mapToInt(m -> m.getText() != null ? m.getText().length() : 0)
                .sum();
        int responseChars = response != null ? response.length() : 0;
        return (promptChars + responseChars) / 2;
    }

    private org.springframework.ai.chat.messages.Message toAiMessage(String role, String content) {
        String normalizedRole = role == null ? "" : role.toLowerCase(Locale.ROOT);
        return switch (normalizedRole) {
            case "assistant" -> new AssistantMessage(content);
            case "system" -> UserMessage.builder()
                    .text("[UNTRUSTED_CONTEXT: CONVERSATION_SUMMARY]\n"
                            + content.replace("[UNTRUSTED_CONTEXT:", "[context marker removed:"))
                    .metadata(Map.of("conversationSummary", true))
                    .build();
            default -> new UserMessage(content);
        };
    }

    /**
     * 获取已有会话或创建新会话。
     */
    private Conversation getOrCreateConversation(ChatRequest request) {
        if (request.getConversationId() != null) {
            return conversationMessageService.getConversationForUser(request.getConversationId(), request.getUserId());
        }
        String origin = normalizeConversationOrigin(request.getOrigin());
        return conversationMessageService.createConversation(request.getUserId(), origin,
                initialConversationTitle(request, origin, request.getMessage()));
    }

    private String normalizeConversationOrigin(String origin) {
        String normalized = origin == null ? "" : origin.trim().toLowerCase(Locale.ROOT);
        if (CONVERSATION_ORIGIN_WORKBENCH.equals(normalized)) {
            return CONVERSATION_ORIGIN_WORKBENCH;
        }
        return CONVERSATION_ORIGIN_CHAT;
    }

    private String initialConversationTitle(ChatRequest request, String origin, String userMessage) {
        if (CONVERSATION_ORIGIN_WORKBENCH.equals(origin)) {
            String title = sanitizeConversationTitle(request.getTitle());
            if (!title.isBlank()) {
                return title;
            }
        }
        return conversationTitleService.fallbackTitle(userMessage);
    }

    private String sanitizeConversationTitle(String title) {
        String sanitized = title == null ? "" : title.trim().replaceAll("[\\r\\n\\t]+", " ");
        return sanitized.length() > 128 ? sanitized.substring(0, 128) : sanitized;
    }

    private void maybeGenerateConversationTitle(Long conversationId, String userId, String userMessage,
                                                boolean newConversation, String origin) {
        if (!newConversation || !CONVERSATION_ORIGIN_CHAT.equals(origin)) {
            return;
        }
        submitBackground("conversation-title", conversationId, () -> {
            String title = conversationTitleService.generateTitle(userMessage);
            conversationMessageService.updateTitleIfOwned(conversationId, userId, title);
        });
    }

    private void submitBackground(String task, Long conversationId, Runnable action) {
        try {
            backgroundTaskExecutor.execute(() -> {
                try {
                    action.run();
                } catch (Exception error) {
                    log.warn("Background task failed, task={}, conversationId={}, reason=EXECUTION_FAILED",
                            task, conversationId, error);
                }
            });
        } catch (RejectedExecutionException rejected) {
            log.warn("Background task not submitted, task={}, conversationId={}, reason=CAPACITY_REJECTED",
                    task, conversationId);
        }
    }

    /** SQL 轮次提交后再更新派生记忆，Redis 不参与会话写事务。 */
    private ConversationMessageService.UserTurn beginUserTurn(ChatRequest request, Long conversationId) {
        var turn = conversationMessageService.appendUserTurn(request.getUserId(), conversationId,
                request.getMessage(), request.getConversationId() != null && Boolean.TRUE.equals(request.getReplaceLastTurn()));
        if (turn.replaced() || shortTermMemory.getContext(conversationId).isEmpty()) {
            rebuildShortTermMemory(conversationId,
                    conversationMessageService.listMessages(request.getUserId(), conversationId));
        } else {
            shortTermMemory.addMessage(conversationId, "user", request.getMessage());
        }
        return turn;
    }

    /**
     * 根据数据库消息重建 Redis 短期记忆。
     */
    private void rebuildShortTermMemory(Long conversationId, List<Message> messages) {
        List<String> memoryMessages = shortTermMemory.restoreContext(conversationId, messages);
        shortTermMemory.replaceWithMessages(conversationId, memoryMessages);
    }

    /**
     * 把数据库消息转换成短期记忆行。
     */
    private String toMemoryMessage(Message message) {
        return ShortTermMemory.toMemoryMessage(message);
    }

    private Message persistAssistantMessage(Long conversationId, String userId, String content,
                                         String traceId, Coordinator.SelectedModel selectedModel) {
        return conversationMessageService.appendAssistantMessage(conversationId, userId, content, traceId,
                selectedModel == null ? null : selectedModel.tier().name(),
                selectedModel == null ? null : selectedModel.modelName());
    }

}
