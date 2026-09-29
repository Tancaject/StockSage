package com.stocksage.conversation;

import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.MessageRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void assistantMessagePreservesActualModelMetadataAndTouchesOwnedConversation() {
        Conversation conversation = conversation(5L, "u1");
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(Message.class))).thenAnswer(call -> call.getArgument(0));

        Message message = service.appendAssistantMessage(
                5L, "u1", "answer", "trace-2", "STANDARD", "actual-model");

        assertThat(message.getConversationId()).isEqualTo(5L);
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getContent()).isEqualTo("answer");
        assertThat(message.getTraceId()).isEqualTo("trace-2");
        assertThat(message.getModelTier()).isEqualTo("STANDARD");
        assertThat(message.getModelName()).isEqualTo("actual-model");
        assertThat(conversation.getUpdatedAt()).isNotNull();
        verify(messageRepository).save(message);
        verify(conversationRepository).save(conversation);
    }

    @Test
    void nonOwnerCannotReplaceAUserTurnOrAppendAnAssistantMessage() {
        Conversation conversation = conversation(5L, "owner");
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> service.appendUserTurn("other", 5L, "new question", true))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Conversation not found");
        assertThatThrownBy(() -> service.appendAssistantMessage(
                5L, "other", "answer", "trace-2", "STANDARD", "actual-model"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Conversation not found");

        verifyNoInteractions(messageRepository);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void asynchronousTitleUpdateOnlyWritesAnExistingOwnedConversation() {
        Conversation owned = conversation(5L, "u1");
        Conversation other = conversation(6L, "u2");
        other.setTitle("unchanged");
        when(conversationRepository.findById(5L)).thenReturn(Optional.of(owned));
        when(conversationRepository.findById(6L)).thenReturn(Optional.of(other));
        when(conversationRepository.findById(7L)).thenReturn(Optional.empty());

        service.updateTitleIfOwned(5L, "u1", "generated title");
        service.updateTitleIfOwned(6L, "u1", "generated title");
        service.updateTitleIfOwned(7L, "u1", "generated title");

        assertThat(owned.getTitle()).isEqualTo("generated title");
        assertThat(other.getTitle()).isEqualTo("unchanged");
        verify(conversationRepository).save(owned);
        verify(conversationRepository, never()).save(other);
        verifyNoInteractions(messageRepository);
    }

    private Conversation conversation(Long id, String userId) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        return conversation;
    }
}
