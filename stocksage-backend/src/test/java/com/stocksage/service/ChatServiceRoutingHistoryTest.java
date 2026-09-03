package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
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
import org.mockito.InOrder;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceRoutingHistoryTest {

    private static final long CONVERSATION_ID = 55L;

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ShortTermMemory shortTermMemory = mock(ShortTermMemory.class);
    private final ChatService service = new ChatService(
            conversationRepository,
            messageRepository,
            mock(ConversationTitleService.class),
            new ObjectMapper().findAndRegisterModules(),
            mock(TraceService.class),
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
            mock(ConversationMessageService.class),
            mock(com.stocksage.agent.RoutingDecisionObserver.class),
            mock(ResearchMemoryService.class),
            mock(TickerResolutionService.class)
    );

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
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId("u_A");

        ReflectionTestUtils.invokeMethod(service, "replaceLastTurnIfRequested", request, conversation);
        List<String> history = recentRoutingTurns(CONVERSATION_ID);

        assertThat(history).containsExactly("user: first question", "assistant: first answer");
        InOrder repositoryOrder = inOrder(messageRepository);
        repositoryOrder.verify(messageRepository).findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID);
        repositoryOrder.verify(messageRepository)
                .deleteByConversationIdAndIdGreaterThanEqual(CONVERSATION_ID, replacedUser.getId());
        repositoryOrder.verify(messageRepository).findTop6ByConversationIdOrderByIdDesc(CONVERSATION_ID);
        verify(shortTermMemory).replaceWithMessages(CONVERSATION_ID, List.of(
                "user: first question",
                "assistant: first answer"
        ));
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
        return (List<String>) ReflectionTestUtils.invokeMethod(service, "recentRoutingTurns", conversationId);
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
