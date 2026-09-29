package com.stocksage.conversation;

import com.stocksage.knowledge.ResearchMemoryService;
import com.stocksage.service.TickerResolutionService;
import com.stocksage.service.ToolPrefetchService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.ShortTermMemory;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多租户隔离的真实逻辑测试。
 *
 * <p>直接驱动真实的 {@link ChatService#listMessages} / {@link ChatService#deleteConversation}，
 * 通过真实 ConversationMessageService 执行归属校验——不同于集成测试里那个重写了校验逻辑的合成控制器。
 * 跨用户访问必须抛 {@link ResourceNotFoundException}（→404），且不得触达底层删除/读取。</p>
 */
class ChatServiceTenancyTest {

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

    private Conversation conversationOwnedBy(String userId) {
        Conversation conversation = new Conversation();
        conversation.setId(55L);
        conversation.setUserId(userId);
        return conversation;
    }

    @Test
    void ownerCanListMessages() {
        when(conversationRepository.findById(55L)).thenReturn(Optional.of(conversationOwnedBy("u_A")));
        Message message = new Message();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(55L)).thenReturn(List.of(message));

        assertThat(service.listMessages("u_A", 55L)).containsExactly(message);
    }

    @Test
    void nonOwnerListMessagesThrowsNotFoundWithoutReadingMessages() {
        when(conversationRepository.findById(55L)).thenReturn(Optional.of(conversationOwnedBy("u_A")));

        assertThatThrownBy(() -> service.listMessages("u_B", 55L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(messageRepository, never()).findByConversationIdOrderByCreatedAtAsc(55L);
    }

    @Test
    void missingConversationThrowsNotFound() {
        when(conversationRepository.findById(55L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listMessages("u_A", 55L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nonOwnerDeleteThrowsNotFoundWithoutDeletingAnything() {
        when(conversationRepository.findById(55L)).thenReturn(Optional.of(conversationOwnedBy("u_A")));

        assertThatThrownBy(() -> service.deleteConversation("u_B", 55L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(messageRepository, never()).deleteByConversationId(55L);
        verify(conversationRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
