package com.stocksage.service;

import com.stocksage.agent.ModelTier;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ConversationMessageService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    /** 后台 worker 也要经过归属校验，避免任务载荷把报告写入其他用户会话。 */
    @Transactional
    public void persistAssistantReport(Long conversationId, String userId, String text, String traceId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole("assistant");
        message.setContent(text == null ? "" : text);
        message.setTraceId(traceId);
        message.setModelTier(ModelTier.STRONG.name());
        messageRepository.save(message);

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }
}
