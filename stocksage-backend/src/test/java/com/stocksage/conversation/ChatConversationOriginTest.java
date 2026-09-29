package com.stocksage.conversation;

import com.stocksage.knowledge.ResearchMemoryService;
import com.stocksage.service.TickerResolutionService;
import com.stocksage.service.ToolPrefetchService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.ShortTermMemory;
import com.stocksage.model.dto.ChatRequest;
import com.stocksage.model.entity.Conversation;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.MessageRepository;
import com.stocksage.rag.RagService;
import com.stocksage.tool.ToolCallEventBus;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatConversationOriginTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ConversationMessageService conversationMessageService = new ConversationMessageService(conversationRepository, messageRepository);
    private final ChatService service = new ChatService(
            mock(ConversationTitleService.class),
            new ObjectMapper().findAndRegisterModules(),
            mock(TraceService.class),
            mock(RagService.class),
            mock(ToolCallEventBus.class),
            mock(TraceEventRelay.class),
            mock(ChatStreamEmitter.class),
            mock(Coordinator.class),
            mock(ShortTermMemory.class),
            mock(LongTermMemory.class),
            mock(AsyncTaskExecutor.class),
            mock(ImageAttachmentService.class),
            mock(ToolPrefetchService.class),
            conversationMessageService,
            mock(com.stocksage.agent.RoutingDecisionObserver.class),
            mock(ResearchMemoryService.class),
            mock(TickerResolutionService.class),
            new ChatPromptAssembler(),
            mock(com.stocksage.research.ResearchTaskService.class),
            mock(com.stocksage.research.ResearchTaskObservationService.class));

    @Test
    void chatRequestAndConversationDefaultToChatOrigin() {
        assertThat(new ChatRequest().getOrigin()).isEqualTo("chat");
        assertThat(new Conversation().getOrigin()).isEqualTo("chat");
    }

    @Test
    void listConversationsOnlyReturnsChatOriginRows() {
        Conversation conversation = new Conversation();
        conversation.setUserId("u_001");
        conversation.setOrigin("chat");
        when(conversationRepository.findByUserIdAndOriginOrderByUpdatedAtDesc("u_001", "chat"))
                .thenReturn(List.of(conversation));

        List<Conversation> conversations = service.listConversations("u_001");

        assertThat(conversations).containsExactly(conversation);
        verify(conversationRepository).findByUserIdAndOriginOrderByUpdatedAtDesc("u_001", "chat");
    }
}
