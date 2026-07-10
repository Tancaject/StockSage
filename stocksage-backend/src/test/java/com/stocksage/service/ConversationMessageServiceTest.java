package com.stocksage.service;

import com.stocksage.model.entity.Conversation;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.MessageRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMessageServiceTest {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ConversationMessageService service =
            new ConversationMessageService(conversationRepository, messageRepository);

    @Test
    void resumedTaskDoesNotInsertTheSameFinalReportTwice() {
        Conversation conversation = new Conversation();
        conversation.setId(5L);
        conversation.setUserId("u1");
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conversation));
        when(messageRepository.existsByConversationIdAndRoleAndTraceIdAndContent(
                5L, "assistant", "trace-1", "final report"))
                .thenReturn(true);

        service.persistAssistantReport(5L, "u1", "final report", "trace-1");

        verify(messageRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(conversationRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
