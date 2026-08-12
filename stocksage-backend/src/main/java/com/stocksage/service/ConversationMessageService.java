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

/**
 * 后台研究任务写入会话消息的事务服务。
 *
 * <p>{@link DeepResearchPipeline} 发布最终报告时调用本类；写入前校验会话归属，并以
 * conversationId、role、traceId、content 去重，避免 worker 重放产生重复助手消息。</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationMessageService {

    /** 校验会话存在且属于当前用户，并刷新会话更新时间。 */
    private final ConversationRepository conversationRepository;
    /** 检查幂等性并持久化助手报告消息。 */
    private final MessageRepository messageRepository;

    /**
     * 持久化后台研究报告；已有完全相同消息时安全返回。
     *
     * @param conversationId 目标会话 ID
     * @param userId 任务所属用户 ID，用于归属校验
     * @param text 最终报告 Markdown
     * @param traceId 关联研究链路 ID
     * @throws ResourceNotFoundException 会话不存在或不属于该用户时
     */
    @Transactional
    public void persistAssistantReport(Long conversationId, String userId, String text, String traceId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

        String content = text == null ? "" : text;
        if (messageRepository.existsByConversationIdAndRoleAndTraceIdAndContent(
                conversationId, "assistant", traceId, content)) {
            return;
        }

        // 调用消息仓储写入 STRONG 模型层级的最终助手报告，再刷新会话排序时间。
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole("assistant");
        message.setContent(content);
        message.setTraceId(traceId);
        message.setModelTier(ModelTier.STRONG.name());
        messageRepository.save(message);

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }
}
