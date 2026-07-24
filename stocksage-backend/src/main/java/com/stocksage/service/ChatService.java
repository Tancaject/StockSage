package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.IntentAwarePlanner;
import com.stocksage.agent.IntentRecognitionRequest;
import com.stocksage.agent.RoutingDecisionMetadata;
import com.stocksage.agent.RoutingDecisionObserver;
import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.ShortTermMemory;
import com.stocksage.model.dto.ChatChunk;
import com.stocksage.model.dto.ChatRequest;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.MessageRepository;
import com.stocksage.rag.RagService;
import com.stocksage.tool.ToolCallContext;
import com.stocksage.tool.ToolCallEventBus;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceEventStore;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话编排服务。
 *
 * <p>这里是一轮用户请求的主运行流程，负责持久化会话写入、链路生命周期、RAG 检索、
 * Coordinator 路由、确定性工具和智能体预取、提示词组装，以及 SSE 数据块输出。
 * Spring AI 模型只接收整理后的上下文；记忆更新和知识摄取等副作用都留在服务层处理。</p>
 *
 * <p>典型请求路径：
 * 控制器 -> streamChat -> RAG 检索 -> Coordinator 规划 -> 可选预取/智能体 ->
 * 最终回答流 -> 持久化 + 关闭链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String CONVERSATION_ORIGIN_CHAT = "chat";
    private static final String CONVERSATION_ORIGIN_WORKBENCH = "workbench";
    private static final Pattern INTENT_TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}\\b");
    private static final Set<String> INTENT_NON_TICKERS = Set.of(
            "AI", "PE", "PB", "ROE", "RSI", "MACD", "SEC", "ETF", "USD", "EPS", "EV", "FCF"
    );

    private record MemoryEntry(String role, String content) {
    }

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

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationTitleService conversationTitleService;
    private final ObjectMapper objectMapper;
    private final TraceService traceService;
    private final RagService ragService;
    private final ToolCallEventBus toolCallEventBus;
    private final TraceEventRelay traceEventRelay;
    private final ChatStreamEmitter chatStreamEmitter;
    private final Coordinator coordinator;
    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    // 分析师/工具预取和后台记忆更新共用的工作线程池（AsyncConfig#agentTaskExecutor，6 线程 daemon）。
    private final AsyncTaskExecutor agentTaskExecutor;
    private final ImageAttachmentService imageAttachmentService;
    private final ToolPrefetchService toolPrefetchService;
    private final ConversationMessageService conversationMessageService;
    private final RoutingDecisionObserver routingDecisionObserver;
    private final IntentAwarePlanner intentAwarePlanner;
    private final ResearchMemoryService researchMemoryService;

    @Value("${stocksage.chat.stream.heartbeat-seconds:20}")
    private long streamHeartbeatSeconds;


    /**
     * 通过 SSE 将单轮对话流式输出为 JSON 数据块。
     *
     * <p>返回的 Flux 会刻意把工具进度事件和回答令牌交错输出，
     * 让前端在最终回答仍在生成时也能展示观测结果。</p>
     */
    public Flux<String> streamChat(ChatRequest request) {
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

        replaceLastTurnIfRequested(request, conversation);
        saveMessage(conversationId, "user", request.getMessage());
        shortTermMemory.addMessage(conversationId, "user", request.getMessage());
        touchConversation(conversation);

        // 2. 注册按链路隔离的事件通道。@Tool 方法开始或完成时，
        // 工具调用切面会向该通道写入事件。
        ToolCallContext.register(traceId, conversationId, request.getMessage());

        // 3. 路由前先检索知识库上下文。命中数量可为 Coordinator 提供信号，
        // 但检索失败不应阻断对话体验。
        long retrievalStart = System.currentTimeMillis();
        List<Document> retrievedDocs = safeRetrieve(request.getMessage(), traceId, retrievalStart);
        long retrievalDuration = System.currentTimeMillis() - retrievalStart;
        if (!retrievedDocs.isEmpty()) {
            String sources = buildRagTraceSources(retrievedDocs);
            traceService.addStep(traceId, AgentStep.builder()
                    .index(0)
                    .thought("Retrieved " + retrievedDocs.size() + " relevant document(s) from knowledge base.")
                    .action("Knowledge Retrieval")
                    .actionInput(request.getMessage())
                    .observation(sources)
                    .durationMs(retrievalDuration)
                    .tokenCount(0)
                    .build());
        }
        List<String> tickerCandidates = extractTickerCandidates(request.getMessage());
        ResearchMemoryService.RetrievalResult researchMemory = researchMemoryService.retrieve(
                request.getUserId(),
                tickerCandidates.isEmpty() ? "" : tickerCandidates.get(0),
                request.getMessage(),
                traceId
        );

        // 4. 让 Coordinator 选择路由。它可以使用 LLM，
        // 但在路由失败时会回退到确定性规则。
        List<String> intentContextSnapshot = shortTermMemory.getContext(conversationId);
        List<String> recentIntentContext = intentContextSnapshot.stream()
                .skip(Math.max(0, intentContextSnapshot.size() - 4L))
                .toList();
        ExecutionPlan executionPlan = intentAwarePlanner.plan(new IntentRecognitionRequest(
                request.getMessage(),
                recentIntentContext,
                retrievedDocs.size(),
                hasImages,
                tickerCandidates
        ));
        RoutingDecisionMetadata routingDecision = executionPlan.routingDecision();
        routingDecisionObserver.record(routingDecision);
        traceService.addStep(traceId, AgentStep.builder()
                .thought(executionPlan.thought())
                .action("Coordinate Request")
                .actionInput(request.getMessage())
                .observation(executionPlan.observation())
                .durationMs(routingDecision == null ? 0L : routingDecision.durationMs())
                .tokenCount(0)
                .attributes(routingDecision == null ? Map.of() : routingDecision.toAttributes())
                .build());

        StringBuilder fullResponse = new StringBuilder();
        Flux<String> conversationStarted = Flux.just(toJson(ChatChunk.builder()
                .type("meta")
                .content("")
                .traceId(traceId)
                .conversationId(conversationId)
                .build()));
        Flux<String> executionPlanStarted = Flux.just(toJson(ChatChunk.builder()
                .type("thought")
                .content(executionPlan.thought() + "\n" + executionPlan.observation())
                .traceId(traceId)
                .conversationId(conversationId)
                .build()));
        Flux<String> routeDecisionStarted = routingDecision == null
                ? Flux.empty()
                : Flux.just(toJson(ChatChunk.builder()
                        .type("route_decision")
                        .content("Route " + routingDecision.route().name()
                                + " selected by " + routingDecision.decisionSource().name() + ".")
                        .traceId(traceId)
                        .conversationId(conversationId)
                        .metadata(routingDecision.toAttributes())
                        .build()));
        Flux<String> retrievalObservation = retrievedDocs.isEmpty()
                ? Flux.empty()
                : Flux.just(toJson(ChatChunk.builder()
                        .type("observation")
                        .content("知识库召回 " + retrievedDocs.size() + " 个候选片段，模型会结合其相关性判断是否采用。")
                        .traceId(traceId)
                        .conversationId(conversationId)
                        .build()));

        AtomicBoolean terminalRecorded = new AtomicBoolean(false);
        AtomicBoolean backgroundTaskSubmitted = new AtomicBoolean(false);
        AtomicBoolean relayFailed = new AtomicBoolean(false);
        AtomicReference<String> eventTraceId = new AtomicReference<>(traceId);
        Sinks.One<String> relayTraceReady = Sinks.one();

        // 心跳关闭信号：DEEP 路由的 Bull/Bear 辩论与最终答案首字延迟都是纯模型阶段，
        // 期间不产生任何 SSE 字节；前端 90s 静默即中断（chat.js STREAM_IDLE_TIMEOUT_MS）。
        // 最终回答 complete/error/cancel 时由 doFinally 触发该信号，停止心跳流。
        Sinks.One<Object> heartbeatStop = Sinks.one();

        Flux<String> answerStream = Flux.defer(() -> {
            ToolCallContext.set(traceId, conversationId, request.getMessage());

            // 5. 在发送最终回答提示词前执行确定性预取。
            // 深度研究会在最终阶段禁用模型工具调用，只让模型基于已准备证据综合回答。
            boolean preparedContextOnly = toolPrefetchService.isDeepResearchPlan(executionPlan.actions());
            Coordinator.SelectedModel selectedModel = coordinator.selectFinalAnswerModel(executionPlan.modelTier(), preparedContextOnly, hasImages);
            ToolPrefetchService.PreparedToolContext preparedToolContext = toolPrefetchService.prefetch(
                    executionPlan,
                    request.getMessage(),
                    traceId,
                    conversationId,
                    request.getUserId(),
                    selectedModel
            );
            String preparedEventTraceId = preparedToolContext.eventTraceId();
            if (preparedEventTraceId == null || preparedEventTraceId.isBlank()) {
                preparedEventTraceId = traceId;
            }
            eventTraceId.set(preparedEventTraceId);
            backgroundTaskSubmitted.set(preparedToolContext.submittedTaskId() != null);
            relayTraceReady.tryEmitValue(preparedEventTraceId);

            if (preparedContextOnly && preparedToolContext.hasDirectAnswer()) {
                return streamPreparedDirectAnswer(
                        preparedToolContext.directAnswer(),
                        request,
                        conversation,
                        newConversation,
                        traceId,
                        conversationId,
                        startTime,
                        fullResponse,
                        terminalRecorded,
                        backgroundTaskSubmitted,
                        heartbeatStop
                );
            }
            List<org.springframework.ai.chat.messages.Message> promptMessages =
                    buildPromptMessages(request.getUserId(), conversationId, preparedToolContext,
                            retrievedDocs, researchMemory.promptContext(), imageMedia);
            Flux<String> modelStarted = Flux.just(toJson(ChatChunk.builder()
                    .type("model")
                    .content(selectedModel.modelName())
                    .modelTier(selectedModel.tier().name())
                    .modelName(selectedModel.modelName())
                    .traceId(traceId)
                    .conversationId(conversationId)
                    .build()));
            Flux<String> responseTokens = coordinator.streamAnswer(promptMessages, preparedContextOnly, selectedModel.tier(), hasImages);

            return Flux.concat(modelStarted, responseTokens
                .doFirst(() -> ToolCallContext.set(traceId, conversationId, request.getMessage()))
                .map(token -> {
                    fullResponse.append(token);
                    return toJson(ChatChunk.builder()
                            .type("answer")
                            .content(token)
                            .traceId(traceId)
                            .conversationId(conversationId)
                            .build());
                })
                .doOnComplete(() -> {
                    long durationMs = System.currentTimeMillis() - startTime;
                    if (terminalRecorded.compareAndSet(false, true)) {
                        String assistantText = fullResponse.toString();
                        saveMessage(conversationId, "assistant", assistantText, traceId, selectedModel);
                        shortTermMemory.addMessage(conversationId, "assistant", assistantText);
                        touchConversation(conversation);
                        maybeGenerateConversationTitle(conversationId, request.getUserId(), request.getMessage(),
                                newConversation, conversation.getOrigin());
                        CompletableFuture.runAsync(() -> longTermMemory.extractAndUpdate(
                                request.getUserId(), assistantText, request.getMessage()), agentTaskExecutor);
                        traceService.addStep(traceId, AgentStep.builder()
                                .thought("Generated streamed assistant answer.")
                                .action(null)
                                .actionInput(null)
                                .observation(null)
                                .durationMs(durationMs)
                                .tokenCount(0)
                                .build());
                        int estimatedTokens = estimateTokens(promptMessages, assistantText);
                        traceService.endTrace(traceId, "success", estimatedTokens, durationMs);
                        log.info("Chat completed, conversationId={}, traceId={}, responseLength={}",
                                conversationId, traceId, fullResponse.length());
                    }
                })
                .onErrorResume(e -> {
                    long durationMs = System.currentTimeMillis() - startTime;
                    if (terminalRecorded.compareAndSet(false, true)) {
                        traceService.addStep(traceId, AgentStep.builder()
                                .thought("Streaming chat failed.")
                                .action(null)
                                .actionInput(null)
                                .observation(e.getMessage())
                                .durationMs(durationMs)
                                .tokenCount(0)
                                .build());
                        traceService.endTrace(traceId, "error", 0, durationMs);
                        log.error("Streaming chat failed, conversationId={}, traceId={}", conversationId, traceId, e);
                    }
                    return Flux.just(toJson(ChatChunk.builder()
                            .type("error")
                            .content("服务暂时出错，请稍后重试。")
                            .traceId(traceId)
                            .conversationId(conversationId)
                            .build()));
                })
                .doFinally(signalType -> {
                    if (signalType == SignalType.CANCEL && terminalRecorded.compareAndSet(false, true)) {
                        long durationMs = System.currentTimeMillis() - startTime;
                        if (!fullResponse.isEmpty()) {
                            saveMessage(conversationId, "assistant", fullResponse.toString(), traceId, selectedModel);
                            touchConversation(conversation);
                        }
                        traceService.addStep(traceId, AgentStep.builder()
                                .thought("Streaming chat was cancelled by the client.")
                                .action(null)
                                .actionInput(null)
                                .observation(null)
                                .durationMs(durationMs)
                                .tokenCount(0)
                                .build());
                        traceService.endTrace(traceId, "cancelled", 0, durationMs);
                        log.info("Chat cancelled, conversationId={}, traceId={}, responseLength={}",
                                conversationId, traceId, fullResponse.length());
                    }
                    if (!backgroundTaskSubmitted.get()) {
                        ToolCallContext.unregister(traceId);
                        chatStreamEmitter.emit(traceId, conversationId, "stream-end", "");
                        toolCallEventBus.complete(traceId);
                        heartbeatStop.tryEmitValue(Boolean.TRUE);
                    }
                }));
        }).onErrorResume(error -> {
            relayTraceReady.tryEmitValue(traceId);
            if (terminalRecorded.compareAndSet(false, true)) {
                long durationMs = System.currentTimeMillis() - startTime;
                traceService.endTrace(traceId, "error", 0, durationMs);
                log.error("Chat preparation failed, conversationId={}, traceId={}", conversationId, traceId, error);
            }
            if (!backgroundTaskSubmitted.get()) {
                chatStreamEmitter.emit(traceId, conversationId, "stream-end", "");
                heartbeatStop.tryEmitValue(Boolean.TRUE);
            }
            return Flux.just(toJson(ChatChunk.builder()
                    .type("error")
                    .content("服务暂时出错，请稍后重试。")
                    .traceId(traceId)
                    .conversationId(conversationId)
                    .build()));
        }).subscribeOn(Schedulers.boundedElastic());

        var switchToDifferentTrace = relayTraceReady.asMono().flatMap(nextTraceId ->
                traceId.equals(nextTraceId)
                        ? reactor.core.publisher.Mono.<String>never()
                        : reactor.core.publisher.Mono.just(nextTraceId));
        Flux<String> initialTraceEvents = traceEventRelay.live(traceId, null)
                .map(TraceEventStore.StoredEvent::chunkJson)
                .takeUntilOther(switchToDifferentTrace);
        Flux<String> switchedTraceEvents = relayTraceReady.asMono()
                .filter(nextTraceId -> !traceId.equals(nextTraceId))
                .flatMapMany(nextTraceId -> traceEventRelay.live(nextTraceId, null)
                        .map(TraceEventStore.StoredEvent::chunkJson));
        Flux<String> toolEvents = Flux.merge(initialTraceEvents, switchedTraceEvents)
                .timeout(Duration.ofMinutes(30))
                .onErrorResume(error -> {
                    relayFailed.set(true);
                    log.warn("Trace event relay ended with an error, traceId={}, eventTraceId={}: {}",
                            traceId, eventTraceId.get(), error.getMessage());
                    return Flux.just(toJson(ChatChunk.builder()
                            .type("error")
                            .content("深度研究事件流等待超时或暂时不可用，请稍后从任务卡继续查看。")
                            .traceId(eventTraceId.get())
                            .conversationId(conversationId)
                            .build()));
                })
                .doFinally(signalType -> {
                    if (!backgroundTaskSubmitted.get()) {
                        return;
                    }
                    long durationMs = System.currentTimeMillis() - startTime;
                    if (terminalRecorded.compareAndSet(false, true)) {
                        String status = relayFailed.get()
                                ? "error"
                                : signalType == SignalType.CANCEL ? "cancelled" : "success";
                        traceService.endTrace(traceId, status, 0, durationMs);
                    }
                    ToolCallContext.unregister(traceId);
                    if (signalType != SignalType.CANCEL) {
                        toolCallEventBus.complete(traceId);
                    }
                    if (signalType != SignalType.CANCEL && traceId.equals(eventTraceId.get())) {
                        ToolCallContext.unregister(eventTraceId.get());
                        toolCallEventBus.complete(eventTraceId.get());
                    }
                    heartbeatStop.tryEmitValue(Boolean.TRUE);
                });

        // 6. 心跳流：在 DEEP 辩论、最终答案首字延迟等纯模型静默阶段周期性补发轻量数据块，
        // 让前端的空闲计时器持续复位，避免把"还在工作"误判为"流已中断"。
        // 前端 onChunk 不识别 type=heartbeat，会静默忽略，无需改动前端。
        // answerStream 的工具预取和上下文构建可能同步阻塞；把它调度到弹性线程，
        // 避免长会话 follow-up 在 model chunk 前饿死 heartbeat。
        Flux<String> heartbeat = Flux.interval(Duration.ofSeconds(streamHeartbeatSeconds))
                .map(tick -> toJson(ChatChunk.builder()
                        .type("heartbeat")
                        .content("")
                        .traceId(traceId)
                        .conversationId(conversationId)
                        .build()))
                .takeUntilOther(heartbeatStop.asMono());

        // 7. 将元数据、推理计划、检索提示、实时工具事件、心跳和最终回答令牌
        // 合并成一条 SSE 流发送给前端。
        return Flux.concat(conversationStarted, routeDecisionStarted, executionPlanStarted, retrievalObservation,
                Flux.merge(toolEvents, heartbeat, answerStream));
    }

    private Flux<String> streamPreparedDirectAnswer(
            String directAnswer,
            ChatRequest request,
            Conversation conversation,
            boolean newConversation,
            String traceId,
            Long conversationId,
            long startTime,
            StringBuilder fullResponse,
            AtomicBoolean terminalRecorded,
            AtomicBoolean backgroundTaskSubmitted,
            Sinks.One<Object> heartbeatStop
    ) {
        String assistantText = directAnswer == null ? "" : directAnswer.trim();
        fullResponse.append(assistantText);
        return Flux.just(toJson(ChatChunk.builder()
                        .type("answer")
                        .content(assistantText)
                        .traceId(traceId)
                        .conversationId(conversationId)
                        .build()))
                .doOnComplete(() -> {
                    if (!assistantText.isBlank()) {
                        saveMessage(conversationId, "assistant", assistantText, traceId);
                        shortTermMemory.addMessage(conversationId, "assistant", assistantText);
                        touchConversation(conversation);
                        maybeGenerateConversationTitle(
                                conversationId,
                                request.getUserId(),
                                request.getMessage(),
                                newConversation,
                                conversation.getOrigin()
                        );
                    }
                    long durationMs = System.currentTimeMillis() - startTime;
                    if (backgroundTaskSubmitted.get()) {
                        traceService.addStep(traceId, AgentStep.builder()
                                .thought("Accepted DEEP research task for background execution.")
                                .durationMs(durationMs)
                                .tokenCount(0)
                                .build());
                        log.info("Background research accepted, conversationId={}, traceId={}",
                                conversationId, traceId);
                        return;
                    }
                    if (terminalRecorded.compareAndSet(false, true)) {
                        CompletableFuture.runAsync(() -> longTermMemory.extractAndUpdate(
                                request.getUserId(), assistantText, request.getMessage()), agentTaskExecutor);
                        traceService.endTrace(traceId, "success", 0, durationMs);
                        log.info("Direct chat answer completed, conversationId={}, traceId={}, responseLength={}",
                                conversationId, traceId, fullResponse.length());
                    }
                })
                .doFinally(signalType -> {
                    if (backgroundTaskSubmitted.get()) {
                        return;
                    }
                    if (signalType == SignalType.CANCEL && terminalRecorded.compareAndSet(false, true)) {
                        traceService.endTrace(
                                traceId,
                                "cancelled",
                                0,
                                System.currentTimeMillis() - startTime
                        );
                    }
                    ToolCallContext.unregister(traceId);
                    chatStreamEmitter.emit(traceId, conversationId, "stream-end", "");
                    toolCallEventBus.complete(traceId);
                    heartbeatStop.tryEmitValue(Boolean.TRUE);
                });
    }

    public List<Conversation> listConversations(String userId) {
        return conversationRepository.findByUserIdAndOriginOrderByUpdatedAtDesc(userId, CONVERSATION_ORIGIN_CHAT);
    }

    public List<Message> listMessages(String userId, Long conversationId) {
        getConversationForUser(conversationId, userId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** 后台研究完成后复用同一条会话消息落库契约。 */
    public void persistAssistantReport(Long conversationId, String userId, String text, String traceId) {
        conversationMessageService.persistAssistantReport(conversationId, userId, text, traceId);
        shortTermMemory.addMessage(conversationId, "assistant", text);
    }

    @Transactional
    public void deleteConversation(String userId, Long conversationId) {
        Conversation conversation = getConversationForUser(conversationId, userId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conversation);
        shortTermMemory.clear(conversationId);
        log.info("Conversation deleted, userId={}, conversationId={}", userId, conversationId);
    }

    /**
     * 构造最终模型调用的有序提示词栈。
     *
     * <p>顺序很重要：先放时间规则和用户记忆，再放 RAG 参考上下文、确定性工具观测、
     * 可选报告草稿，最后放最近对话历史。</p>
     */
    private List<org.springframework.ai.chat.messages.Message> buildPromptMessages(
            String userId,
            Long conversationId,
            ToolPrefetchService.PreparedToolContext preparedToolContext,
            List<Document> retrievedDocs,
            String researchMemoryContext,
            List<Media> imageMedia
    ) {
        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        String deterministicToolContext = preparedToolContext == null ? "" : preparedToolContext.context();
        messages.add(new SystemMessage(buildTemporalSystemPrompt()));
        if (imageMedia != null && !imageMedia.isEmpty()) {
            messages.add(new SystemMessage("""
                    用户当前轮附带了图片。请直接读取图片内容，并把图像中的可见事实、图表趋势、截图文字或界面状态纳入回答。
                    如果图片中的标的、数值或时间无法可靠识别，必须说明不确定性；不要把模糊图像内容编造成精确数据。
                    """));
        }
        String userMemoryContext = longTermMemory.buildPromptContext(userId);
        if (userMemoryContext != null && !userMemoryContext.isBlank()) {
            messages.add(new SystemMessage(userMemoryContext));
        }
        if (retrievedDocs != null && !retrievedDocs.isEmpty()) {
            String ragContext = buildNumberedRagContext(retrievedDocs);
            messages.add(new SystemMessage("""
                    以下是从知识库检索到的相关文档片段，仅供参考。每个片段都带有引用编号和来源信息。
                    如果内容与用户问题相关，请在使用该片段事实的句子末尾标注对应编号，例如 [1]。
                    不要标注未使用的片段；不要编造不存在的引用编号。
                    如果最终回答使用了任何编号片段，必须在回答末尾添加“## 参考来源”，列出实际使用过的编号及其来源摘要。
                    如果片段不相关，可以忽略，基于工具观察和你自身的知识回答。
                    如果片段中的 ticker、公司名、行业、主营业务与后端已解析的目标标的不一致，必须忽略该片段，不得把其他公司的业务或财报事实套用到目标公司。

                    %s
                    """.formatted(PromptText.truncate(ragContext, 4000))));
        }
        if (researchMemoryContext != null && !researchMemoryContext.isBlank()) {
            messages.add(new SystemMessage(researchMemoryContext));
        }
        if (deterministicToolContext != null && !deterministicToolContext.isBlank()) {
            messages.add(new SystemMessage("""
                    后端已根据公开计划预先执行以下工具观察。请优先结合这些运行时观察回答用户问题；
                    若知识库上下文与工具观察冲突，以更新、更具体的工具观察为准。
                    必须先检查 resolvedStockIdentity；公司名称、ticker、主营业务、行业描述必须与 resolvedStockIdentity 和后续工具观察一致。
                    如果主营业务没有被工具、搜索结果或可靠知识库片段确认，直接说明“主营业务暂未由当前数据源确认”，不要凭常识或相似名称补全。

                    %s
                    """.formatted(deterministicToolContext)));
            if (deterministicToolContext.contains("## Research Manager")) {
                messages.add(new SystemMessage("""
                        你现在只负责把 Research Manager 的中文裁决润色成最终用户答案。
                        必须使用简体中文；可以使用 Markdown 表格、加粗、分节、引用块和行动建议。
                        不要展示 Bull/Bear 原始辩论内容；不要出现内部 agent 名、Java 方法名或工具函数名。
                        必须保留证据优先投研报告结构：投资结论、核心依据、证据表、多空权衡、适合/不适合、风险与未知项、数据来源与时间说明。
                        不要删除“未知项”和“数据缺口”；缺少证据的地方直接说明，不要为了完整而补编事实。
                        """));
            }
        }
        if (preparedToolContext != null && preparedToolContext.hasDirectAnswer()) {
            messages.add(new SystemMessage("""
                    以下是后端已按“证据优先投研报告模板”整理的回答草稿。请以它为主线生成最终回答：
                    - 保留一级结构和证据表。
                    - 可以润色语言、压缩重复内容。
                    - 不得新增草稿和预取观察之外的精确事实或数值。
                    - 不得删除风险、未知项、数据来源与时间说明。

                    %s
                    """.formatted(preparedToolContext.directAnswer())));
        }
        messages.addAll(buildShortTermPromptMessages(conversationId, history, imageMedia));
        return messages;
    }

    private List<org.springframework.ai.chat.messages.Message> buildShortTermPromptMessages(
            Long conversationId,
            List<Message> history,
            List<Media> imageMedia
    ) {
        shortTermMemory.compressIfNeeded(conversationId);
        List<String> memoryEntries = shortTermMemory.getContext(conversationId);
        if (memoryEntries.isEmpty() && history != null && !history.isEmpty()) {
            rebuildShortTermMemory(conversationId, history);
            shortTermMemory.compressIfNeeded(conversationId);
            memoryEntries = shortTermMemory.getContext(conversationId);
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
    private List<Document> safeRetrieve(String query, String traceId, long retrievalStart) {
        try {
            return ragService.retrieve(query);
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
            sources.append(formatCitationSource(i + 1, doc)).append("\n");
            String snippet = doc.getText().length() > 150
                    ? doc.getText().substring(0, 150) + "..."
                    : doc.getText();
            sources.append("  ").append(snippet).append("\n");
        }
        return sources.toString().trim();
    }

    /**
     * 构造带编号的 RAG 上下文，供最终回答模型引用。
     */
    private String buildNumberedRagContext(List<Document> retrievedDocs) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document doc = retrievedDocs.get(i);
            context.append(formatCitationSource(i + 1, doc))
                    .append("\nContent:\n")
                    .append(doc.getText())
                    .append("\n\n");
        }
        return context.toString().trim();
    }

    /**
     * 把单个检索文档格式化成可读引用来源。
     */
    private String formatCitationSource(int index, Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String ticker = metadataValue(meta, "ticker");
        String filingType = metadataValue(meta, "filing_type");
        String date = metadataValue(meta, "filing_date", "date", "ingested_date");
        String section = metadataValue(meta, "section", "section_title");
        String title = metadataValue(meta, "title");
        String source = metadataValue(meta, "source", "source_id");

        StringBuilder sourceLine = new StringBuilder("[").append(index).append("] Source: ");
        appendSourcePart(sourceLine, "ticker", ticker);
        appendSourcePart(sourceLine, "filing_type", filingType);
        appendSourcePart(sourceLine, "section", section);
        appendSourcePart(sourceLine, "date", date);
        appendSourcePart(sourceLine, "title", title);
        appendSourcePart(sourceLine, "source", source.isBlank() ? "unknown" : source);
        return sourceLine.toString();
    }

    /**
     * 向引用来源摘要追加非空字段。
     */
    private void appendSourcePart(StringBuilder builder, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.toString().endsWith(": ")) {
            builder.append("; ");
        }
        builder.append(name).append("=").append(value);
    }

    /**
     * 按候选 key 顺序读取第一个非空元数据值。
     */
    private String metadataValue(Map<String, Object> metadata, String... keys) {
        if (metadata == null) {
            return "";
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }


    /**
     * 根据文本长度估算令牌数。
     * 中文平均约 1.5 个令牌/字符，英文约 0.25 个令牌/词（约 4 字符/令牌）。
     * 中英混合内容采用折中估算：字符数 / 2。
     */
    /**
     * 粗略估算本轮提示词和回答 token 数。
     */
    private int estimateTokens(List<org.springframework.ai.chat.messages.Message> promptMessages, String response) {
        int promptChars = promptMessages.stream()
                .mapToInt(m -> m.getText() != null ? m.getText().length() : 0)
                .sum();
        int responseChars = response != null ? response.length() : 0;
        return (promptChars + responseChars) / 2;
    }

    /**
     * 将相对日期推理锚定到服务器日期，避免“最新”类问题继承旧示例或模型先验中的过期年份。
     */
    /**
     * 构造带当前日期的时效性系统提示。
     */
    private String buildTemporalSystemPrompt() {
        LocalDate today = LocalDate.now();
        return """
                当前日期是 %s。凡是用户提到“最近、近期、最新、当前、现在、今天、本周、本月、今年”等时效性问题，必须以这个日期作为时间锚点。
                如果用户提到具体股票/公司但市场或 ticker 不确定，先使用 searchStocks/resolveStock；如果需要外部信息，再调用 searchNews 或 webSearch，并使用当前年份或不带过去年份的查询词。
                除非用户明确询问某个历史年份，不要把搜索词或结论锚定到 2024、2025 等过去年份。
                回答时不要写“截至2024年”这类过期表述；应说明检索结果的日期或明确数据缺口。
                如果回答依赖 RAG、搜索结果或工具观测中的外部事实，必须在回答末尾保留“数据来源与时间说明”或“参考来源”小节。
                """.formatted(today);
    }

    private org.springframework.ai.chat.messages.Message toAiMessage(Message message) {
        return toAiMessage(message.getRole(), message.getContent());
    }

    private org.springframework.ai.chat.messages.Message toAiMessage(String role, String content) {
        String normalizedRole = role == null ? "" : role.toLowerCase(Locale.ROOT);
        return switch (normalizedRole) {
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }

    /**
     * 获取已有会话或创建新会话。
     */
    private Conversation getOrCreateConversation(ChatRequest request) {
        if (request.getConversationId() != null) {
            return getConversationForUser(request.getConversationId(), request.getUserId());
        }

        Conversation conversation = new Conversation();
        conversation.setUserId(request.getUserId());
        String origin = normalizeConversationOrigin(request.getOrigin());
        conversation.setOrigin(origin);
        String msg = request.getMessage();
        conversation.setTitle(initialConversationTitle(request, origin, msg));
        return conversationRepository.save(conversation);
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
        CompletableFuture.runAsync(() -> {
            String title = conversationTitleService.generateTitle(userMessage);
            conversationRepository.findById(conversationId)
                    .filter(conversation -> conversation.getUserId().equals(userId))
                    .ifPresent(conversation -> {
                        conversation.setTitle(title);
                        conversationRepository.save(conversation);
                    });
        }, agentTaskExecutor);
    }

    /**
     * 当用户要求重新生成时，删除上一次用户轮次之后的消息并重建短期记忆。
     */
    private void replaceLastTurnIfRequested(ChatRequest request, Conversation conversation) {
        if (!Boolean.TRUE.equals(request.getReplaceLastTurn()) || request.getConversationId() == null) {
            return;
        }

        Long conversationId = conversation.getId();
        List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int turnStartIndex = findLastUserTurnStart(history);
        if (turnStartIndex < 0) {
            return;
        }

        Message firstMessageToDelete = history.get(turnStartIndex);
        messageRepository.deleteByConversationIdAndIdGreaterThanEqual(conversationId, firstMessageToDelete.getId());
        List<Message> remaining = history.subList(0, turnStartIndex);
        rebuildShortTermMemory(conversationId, remaining);
        touchConversation(conversation);
        log.info("Replaced last conversation turn, conversationId={}, deletedFromMessageId={}, remainingMessages={}",
                conversationId, firstMessageToDelete.getId(), remaining.size());
    }

    /**
     * 在历史消息中定位最后一条用户消息的位置。
     */
    private int findLastUserTurnStart(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return -1;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if ("user".equalsIgnoreCase(message.getRole())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 根据数据库消息重建 Redis 短期记忆。
     */
    private void rebuildShortTermMemory(Long conversationId, List<Message> messages) {
        List<String> memoryMessages = messages.stream()
                .map(this::toMemoryMessage)
                .toList();
        shortTermMemory.replaceWithMessages(conversationId, memoryMessages);
    }

    /**
     * 把数据库消息转换成短期记忆行。
     */
    private String toMemoryMessage(Message message) {
        String role = message.getRole() == null ? "user" : message.getRole().trim().toLowerCase(Locale.ROOT);
        String content = message.getContent() == null ? "" : message.getContent().trim();
        return role + ": " + content;
    }

    /**
     * 读取并校验会话归属用户。
     *
     * <p>会话不存在与不属于当前用户都抛 {@link ResourceNotFoundException}（→404），不区分两者、
     * 不回显 conversationId：多租户下不应向调用者泄露某会话是否存在或归属他人。</p>
     */
    private Conversation getConversationForUser(Long conversationId, String userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        return conversation;
    }

    /**
     * 保存不绑定 traceId 的聊天消息。
     */
    private void saveMessage(Long conversationId, String role, String content) {
        saveMessage(conversationId, role, content, null);
    }

    /**
     * 保存聊天消息并关联追踪 ID。
     */
    private void saveMessage(Long conversationId, String role, String content, String traceId) {
        saveMessage(conversationId, role, content, traceId, null);
    }

    /**
     * 保存聊天消息，并在助手消息上记录最终回答模型。
     */
    private void saveMessage(Long conversationId, String role, String content, String traceId, Coordinator.SelectedModel selectedModel) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setTraceId(traceId);
        if (selectedModel != null && "assistant".equalsIgnoreCase(role)) {
            message.setModelTier(selectedModel.tier().name());
            message.setModelName(selectedModel.modelName());
        }
        messageRepository.save(message);
    }

    /**
     * 刷新会话更新时间。
     */
    private void touchConversation(Conversation conversation) {
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    /**
     * 将 SSE 分片序列化为 JSON 字符串。
     */
    private String toJson(ChatChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ChatChunk", e);
            return "{\"type\":\"error\",\"content\":\"Internal serialization error\"}";
        }
    }
}
