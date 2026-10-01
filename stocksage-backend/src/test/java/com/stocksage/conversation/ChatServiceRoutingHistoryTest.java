package com.stocksage.conversation;

import com.stocksage.knowledge.KnowledgeIngestionService;
import com.stocksage.knowledge.ResearchMemoryService;
import com.stocksage.service.TickerResolutionService;
import com.stocksage.service.ToolPrefetchService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.AgentStep;
import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.ShortTermMemory;
import com.stocksage.model.dto.ChatRequest;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.MessageRepository;
import com.stocksage.rag.RagService;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.ToolCallEventBus;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;

class ChatServiceRoutingHistoryTest {

    private static final long CONVERSATION_ID = 55L;

    @Test
    void ordinaryEvidenceMustFitAsAWholeInsteadOfLeavingPartialCitations() {
        String content = "本轮标的：AAPL\n[E1] " + "evidence".repeat(100);
        StringBuilder target = new StringBuilder();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(assembler,
                "appendContextSection", target, "TOOL_OBSERVATIONS", content, 300, 300))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("上下文预算");
        assertThat(target).isEmpty();
        ReflectionTestUtils.invokeMethod(assembler, "appendContextSection", target,
                "TOOL_OBSERVATIONS", content, 2000, 2000);
        assertThat(target.toString()).contains(content);
    }

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ShortTermMemory shortTermMemory = mock(ShortTermMemory.class);
    private final TraceService traceService = mock(TraceService.class);
    private final ChatPromptAssembler assembler = new ChatPromptAssembler();
    private final ConversationMessageService conversationMessageService = new ConversationMessageService(conversationRepository, messageRepository);
    private final com.stocksage.research.ResearchTaskService tasks = mock(com.stocksage.research.ResearchTaskService.class);
    private final com.stocksage.research.ResearchTaskObservationService observations = mock(com.stocksage.research.ResearchTaskObservationService.class);
    private final ChatService service = new ChatService(
            mock(ConversationTitleService.class),
            new ObjectMapper().findAndRegisterModules(),
            traceService,
            mock(RagService.class),
            mock(ToolCallEventBus.class),
            mock(TraceEventRelay.class),
            mock(ChatStreamEmitter.class),
            mock(Coordinator.class),
            shortTermMemory,
            mock(LongTermMemory.class),
            mock(AsyncTaskExecutor.class),
            mock(ImageAttachmentService.class),
            mock(ToolPrefetchService.class),
            conversationMessageService,
            mock(com.stocksage.agent.RoutingDecisionObserver.class),
            mock(ResearchMemoryService.class),
            mock(TickerResolutionService.class),
            assembler,
            tasks, observations);

    @Test
    void repeatedSubmissionObservesOriginalRunBeforeAnyConversationOrRoutingWork() {
        ChatRequest request = new ChatRequest();
        request.setUserId("u1");
        request.setSubmissionId("same-run");
        request.setConversationId(null);
        request.setMessage("different routing context");
        var task = new com.stocksage.model.entity.ResearchTask();
        task.setId(7L);
        task.setUserId("u1");
        task.setConversationId(55L);
        when(tasks.buildRunSubmissionKey("u1", "same-run")).thenReturn("run-key");
        when(tasks.findSubmission("run-key")).thenReturn(java.util.Optional.of(task));
        when(observations.observeChat(task, "u1")).thenReturn(reactor.core.publisher.Flux.just("original run"));

        assertThat(service.streamChat(request).collectList().block()).containsExactly("original run");
        org.mockito.Mockito.verifyNoInteractions(conversationRepository, messageRepository,
                traceService, shortTermMemory);
        org.mockito.Mockito.verifyNoInteractions(ReflectionTestUtils.getField(service, "coordinator"),
                ReflectionTestUtils.getField(service, "imageAttachmentService"),
                ReflectionTestUtils.getField(service, "toolPrefetchService"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void attributionBindsRenderedPromptAndSeparatesSourceEvidenceFromGeneratedDraft() throws Exception {
        ReflectionTestUtils.setField(assembler, "promptMaxTextChars", 24000);
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId("u_A");
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(java.util.Optional.of(conversation));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(message(1, CONVERSATION_ID, "user", "解释营收变化")));
        String source = "[E1] 营收为100美元。";
        String prefix = "本轮标的：AAPL\n" + source + "\n";
        List<org.springframework.ai.chat.messages.Message> actual = null;
        for (String draft : List.of("第一种解读", "第二种解读")) {
            actual = ReflectionTestUtils.invokeMethod(service, "buildPromptMessages",
                    "u_A", CONVERSATION_ID,
                    new ToolPrefetchService.PreparedToolContext(prefix + draft,
                            "", null, "trace-answer", "COMPLETED", List.of("E1"), source,
                            new ToolPrefetchService.AnalystSection(prefix.length(), prefix.length() + draft.length())),
                    List.of(), "历史研究结论", "", List.of(), false, "trace-answer");
        }
        ArgumentCaptor<AgentStep> steps = ArgumentCaptor.forClass(AgentStep.class);
        verify(traceService, times(4)).addStep(eq("trace-answer"), steps.capture());
        List<Map<String, Object>> contexts = steps.getAllValues().stream().map(AgentStep::getAttributes)
                .filter(attrs -> "answer-context".equals(attrs.get("kind"))).toList();
        assertThat(contexts).hasSize(2);
        Map<String, Object> first = contexts.get(0);
        Map<String, Object> second = contexts.get(1);
        assertThat(second.get("context").toString()).contains(source, "第二种解读", "历史研究结论");
        assertThat(second.get("evidenceContext")).isEqualTo(source);
        assertThat(second.get("evidenceCaptureComplete")).isEqualTo(true);
        assertThat(second.get("evidenceSha256")).isEqualTo(first.get("evidenceSha256"));
        assertThat(second.get("promptSha256")).isNotEqualTo(first.get("promptSha256"));
        List<Map<String, String>> rendered = (List<Map<String, String>>) second.get("messages");
        assertThat(rendered.stream().map(row -> row.get("text")).toList())
                .containsExactlyElementsOf(actual.stream().map(org.springframework.ai.chat.messages.Message::getText).toList());
        assertThat(second.get("promptSha256")).isEqualTo(KnowledgeIngestionService.sha256(
                new ObjectMapper().writeValueAsString(rendered)));
        assertThat(second.get("contextSha256")).isEqualTo(KnowledgeIngestionService.sha256(second.get("context").toString()));
        Map<String, Object> span = (Map<String, Object>) second.get("analystSpan");
        assertThat(span.get("schemaVersion")).isEqualTo(1);
        assertThat(span.get("offsetUnit")).isEqualTo("UNICODE_CODE_POINT");
        String sent = rendered.get((int) span.get("messageIndex")).get("text");
        String removed = sent.substring(sent.offsetByCodePoints(0, (int) span.get("start")),
                sent.offsetByCodePoints(0, (int) span.get("end")));
        assertThat(removed).isEqualTo("第二种解读");
        assertThat(span.get("removedTextSha256")).isEqualTo(KnowledgeIngestionService.sha256(removed));

        org.mockito.Mockito.doThrow(new IllegalStateException("trace store unavailable"))
                .when(traceService).addStep(eq("trace-answer"), org.mockito.ArgumentMatchers.any(AgentStep.class));
        List<org.springframework.ai.chat.messages.Message> withoutObservation = ReflectionTestUtils.invokeMethod(
                service, "buildPromptMessages", "u_A", CONVERSATION_ID,
                new ToolPrefetchService.PreparedToolContext("本轮标的：AAPL\n" + source + "\n第二种解读",
                        "", null, "trace-answer", "COMPLETED", List.of("E1"), source),
                List.of(), "历史研究结论", "", List.of(), false, "trace-answer");
        assertThat(withoutObservation.stream().map(org.springframework.ai.chat.messages.Message::getText).toList())
                .containsExactlyElementsOf(actual.stream().map(org.springframework.ai.chat.messages.Message::getText).toList());
    }

    @Test
    void restoresThreeCompleteTurnsFromNewestFirstRowsIntoChronologicalOrder() {
        when(messageRepository.findTop6ByConversationIdOrderByIdDesc(CONVERSATION_ID)).thenReturn(List.of(
                message(6, CONVERSATION_ID, "assistant", "third answer"),
                message(5, CONVERSATION_ID, "user", "third question"),
                message(4, CONVERSATION_ID, "assistant", "second answer"),
                message(3, CONVERSATION_ID, "user", "second question"),
                message(2, CONVERSATION_ID, "assistant", "first answer"),
                message(1, CONVERSATION_ID, "user", "first question")
        ));

        assertThat(recentRoutingTurns(CONVERSATION_ID)).containsExactly(
                "user: first question",
                "assistant: first answer",
                "user: second question",
                "assistant: second answer",
                "user: third question",
                "assistant: third answer"
        );
    }

    @Test
    void queriesOnlyTheRequestedConversationAndRejectsUnexpectedRows() {
        when(messageRepository.findTop6ByConversationIdOrderByIdDesc(CONVERSATION_ID)).thenReturn(List.of(
                message(4, 99L, "assistant", "foreign answer"),
                message(3, 99L, "user", "foreign question"),
                message(2, CONVERSATION_ID, "assistant", "owned answer"),
                message(1, CONVERSATION_ID, "user", "owned question")
        ));

        assertThat(recentRoutingTurns(CONVERSATION_ID)).containsExactly(
                "user: owned question",
                "assistant: owned answer"
        );
        verify(messageRepository).findTop6ByConversationIdOrderByIdDesc(CONVERSATION_ID);
    }

    @Test
    void ignoresAnUnansweredNewestUserMessageAndKeepsOnlyCompleteTurns() {
        when(messageRepository.findTop6ByConversationIdOrderByIdDesc(CONVERSATION_ID)).thenReturn(List.of(
                message(6, CONVERSATION_ID, "user", "current question must not be routed as history"),
                message(5, CONVERSATION_ID, "assistant", "second answer"),
                message(4, CONVERSATION_ID, "user", "second question"),
                message(3, CONVERSATION_ID, "system", "internal summary"),
                message(2, CONVERSATION_ID, "assistant", "first answer"),
                message(1, CONVERSATION_ID, "user", "first question")
        ));

        assertThat(recentRoutingTurns(CONVERSATION_ID)).containsExactly(
                "user: first question",
                "assistant: first answer",
                "user: second question",
                "assistant: second answer"
        );
    }

    @Test
    void boundsEachMessageAndTheCombinedRoutingHistory() {
        String longContent = "line one\n" + "x".repeat(900);
        when(messageRepository.findTop6ByConversationIdOrderByIdDesc(CONVERSATION_ID)).thenReturn(List.of(
                message(6, CONVERSATION_ID, "assistant", longContent),
                message(5, CONVERSATION_ID, "user", longContent),
                message(4, CONVERSATION_ID, "assistant", longContent),
                message(3, CONVERSATION_ID, "user", longContent),
                message(2, CONVERSATION_ID, "assistant", longContent),
                message(1, CONVERSATION_ID, "user", longContent)
        ));

        List<String> history = recentRoutingTurns(CONVERSATION_ID);

        assertThat(history).hasSize(6).allMatch(item -> item.length() <= 600);
        assertThat(String.join("\n", history).length()).isLessThanOrEqualTo(3000);
        assertThat(history).allMatch(item -> item.startsWith("user: ") || item.startsWith("assistant: "));
        assertThat(history).noneMatch(item -> item.contains("\n"));
    }

    @Test
    void replaceLastTurnDeletesTheOldBranchBeforeRoutingHistoryIsRead() {
        Message firstUser = message(1, CONVERSATION_ID, "user", "first question");
        Message firstAssistant = message(2, CONVERSATION_ID, "assistant", "first answer");
        Message replacedUser = message(3, CONVERSATION_ID, "user", "old question");
        Message replacedAssistant = message(4, CONVERSATION_ID, "assistant", "old answer");
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(firstUser, firstAssistant, replacedUser, replacedAssistant));
        when(messageRepository.findTop6ByConversationIdOrderByIdDesc(CONVERSATION_ID))
                .thenReturn(List.of(firstAssistant, firstUser));

        ChatRequest request = new ChatRequest();
        request.setConversationId(CONVERSATION_ID);
        request.setReplaceLastTurn(true);
        request.setUserId("u_A");
        request.setMessage("new question");
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId("u_A");

        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(java.util.Optional.of(conversation));
        when(messageRepository.save(org.mockito.ArgumentMatchers.any(Message.class))).thenAnswer(call -> call.getArgument(0));
        ConversationMessageService.UserTurn turn = ReflectionTestUtils.invokeMethod(service, "beginUserTurn", request, CONVERSATION_ID);
        List<String> history = turn.routingTurns();
        assertThat(turn.sourceMessage().getContent()).isEqualTo("new question");

        assertThat(history).containsExactly("user: first question", "assistant: first answer");
        InOrder repositoryOrder = inOrder(messageRepository, conversationRepository, shortTermMemory);
        repositoryOrder.verify(messageRepository).findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID);
        repositoryOrder.verify(messageRepository)
                .deleteByConversationIdAndIdGreaterThanEqual(CONVERSATION_ID, replacedUser.getId());
        repositoryOrder.verify(messageRepository).findTop6ByConversationIdOrderByIdDesc(CONVERSATION_ID);
        repositoryOrder.verify(messageRepository).save(org.mockito.ArgumentMatchers.any(Message.class));
        repositoryOrder.verify(conversationRepository).save(conversation);
        repositoryOrder.verify(shortTermMemory).replaceWithMessages(CONVERSATION_ID, List.of(
                "user: first question",
                "assistant: first answer"
        ));
        repositoryOrder.verify(shortTermMemory).addMessage(CONVERSATION_ID, "user", "new question");
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleRedisHistoryFallsBackToTheCurrentDatabaseTurn() {
        List<Message> history = List.of(
                message(1, CONVERSATION_ID, "assistant", "previous answer"),
                message(2, CONVERSATION_ID, "user", "current question")
        );
        List<String> stale = List.of("user: previous question", "assistant: previous answer");
        when(shortTermMemory.getContext(CONVERSATION_ID)).thenReturn(stale, stale);

        List<org.springframework.ai.chat.messages.Message> promptHistory =
                (List<org.springframework.ai.chat.messages.Message>) ReflectionTestUtils.invokeMethod(
                        service,
                        "buildShortTermPromptMessages",
                        CONVERSATION_ID,
                        history,
                        List.of()
                );

        assertThat(promptHistory)
                .extracting(org.springframework.ai.chat.messages.Message::getText)
                .containsExactly("previous answer", "current question");
        verify(shortTermMemory).replaceWithMessages(CONVERSATION_ID, List.of(
                "assistant: previous answer",
                "user: current question"
        ));
    }

    @SuppressWarnings("unchecked")
    private List<String> recentRoutingTurns(Long conversationId) {
        return (List<String>) ReflectionTestUtils.invokeMethod(conversationMessageService, "recentRoutingTurns", conversationId);
    }

    private Message message(long id, Long conversationId, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
