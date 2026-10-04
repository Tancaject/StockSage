package com.stocksage.conversation;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 会话与消息的数据库事务边界。
 *
 * <p>用户轮次裁剪、消息写入和会话排序时间在短事务内完成。Redis 记忆、标题生成、Trace
 * 和模型调用由调用方在事务外处理；后台报告写入加入研究服务已有的发布事务。</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationMessageService {

    private static final String CONVERSATION_ORIGIN_CHAT = "chat";
    private static final int ROUTING_HISTORY_MAX_TURNS = 3;
    private static final int ROUTING_HISTORY_MAX_MESSAGE_CHARS = 600;
    private static final int ROUTING_HISTORY_MAX_TOTAL_CHARS = 3000;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @Transactional
    public Conversation createConversation(String userId, String normalizedOrigin, String initialTitle) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setOrigin(normalizedOrigin);
        conversation.setTitle(initialTitle);
        return conversationRepository.save(conversation);
    }

    /** 不存在与非本人会话使用相同错误，避免泄露他人的会话归属。 */
    @Transactional(readOnly = true)
    public Conversation getConversationForUser(Long conversationId, String userId) {
        return conversationRepository.findById(conversationId)
                .filter(conversation -> conversation.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }

    @Transactional(readOnly = true)
    public List<Conversation> listConversations(String userId) {
        return conversationRepository.findByUserIdAndOriginOrderByUpdatedAtDesc(userId, CONVERSATION_ORIGIN_CHAT);
    }

    @Transactional(readOnly = true)
    public List<Message> listMessages(String userId, Long conversationId) {
        getConversationForUser(conversationId, userId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public void deleteConversation(String userId, Long conversationId) {
        Conversation conversation = getConversationForUser(conversationId, userId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conversation);
    }

    /** 异步标题生成返回时，会话可能已经删除；保持归属检查后的静默跳过行为。 */
    @Transactional
    public void updateTitleIfOwned(Long conversationId, String userId, String title) {
        conversationRepository.findById(conversationId)
                .filter(conversation -> conversation.getUserId().equals(userId))
                .ifPresent(conversation -> {
                    conversation.setTitle(title);
                    conversationRepository.save(conversation);
                });
    }

    /** 路由历史在当前问题写入前读取；重生成的分支删除与新问题写入同进退。 */
    @Transactional
    public UserTurn appendUserTurn(String userId, Long conversationId, String text, boolean replaceLastTurn) {
        Conversation conversation = getConversationForUser(conversationId, userId);
        boolean replaced = false;
        List<Message> remainingHistory = List.of();
        if (replaceLastTurn) {
            List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            int turnStartIndex = findLastUserTurnStart(history);
            if (turnStartIndex >= 0) {
                Message firstMessageToDelete = history.get(turnStartIndex);
                messageRepository.deleteByConversationIdAndIdGreaterThanEqual(
                        conversationId, firstMessageToDelete.getId());
                remainingHistory = List.copyOf(history.subList(0, turnStartIndex));
                replaced = true;
            }
        }
        List<String> routingTurns = recentRoutingTurns(conversationId);
        Message sourceMessage = insertMessage(conversationId, "user", text, null, null, null);
        touchConversation(conversation);
        return new UserTurn(sourceMessage, routingTurns, replaced, remainingHistory);
    }

    /** remainingHistory 仅在发生替换时提供，供调用方在提交后重建派生记忆。 */
    public record UserTurn(Message sourceMessage, List<String> routingTurns,
                           boolean replaced, List<Message> remainingHistory) {
    }

    @Transactional
    public Message appendAssistantMessage(Long conversationId, String userId, String text,
                                          String traceId, String modelTier, String modelName) {
        Conversation conversation = getConversationForUser(conversationId, userId);
        Message message = insertMessage(conversationId, "assistant", text, traceId, modelTier, modelName);
        touchConversation(conversation);
        return message;
    }

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
        Conversation conversation = getConversationForUser(conversationId, userId);

        String content = text == null ? "" : text;
        if (messageRepository.existsByConversationIdAndRoleAndTraceIdAndContent(
                conversationId, "assistant", traceId, content)) {
            return;
        }

        insertMessage(conversationId, "assistant", content, traceId, ModelTier.STRONG.name(), null);
        touchConversation(conversation);
    }

    private Message insertMessage(Long conversationId, String role, String content,
                                  String traceId, String modelTier, String modelName) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setTraceId(traceId);
        message.setModelTier(modelTier);
        message.setModelName(modelName);
        return messageRepository.save(message);
    }

    private void touchConversation(Conversation conversation) {
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    /** 在历史消息中定位最后一条用户消息的位置。 */
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
     * 从 MySQL 真源读取最近三个完整问答轮次，供路由模型处理指代和省略表达。
     *
     * <p>仓储返回最多六条倒序消息。这里仅接受同一会话内相邻的 assistant/user 配对，
     * 忽略没有助手回答的用户消息和多余角色，再把完整轮次恢复为时间正序。每条消息和
     * 整体上下文都有限长，避免历史对话挤占路由提示词。</p>
     */
    private List<String> recentRoutingTurns(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<Message> newestMessages = messageRepository.findTop6ByConversationIdOrderByIdDesc(conversationId);
        if (newestMessages == null || newestMessages.isEmpty()) {
            return List.of();
        }

        List<List<Message>> newestTurns = new ArrayList<>();
        Message pendingAssistant = null;
        for (Message message : newestMessages) {
            if (message == null || !Objects.equals(conversationId, message.getConversationId())) {
                continue;
            }
            String role = normalizedRoutingRole(message.getRole());
            if ("assistant".equals(role)) {
                // 连续 assistant 消息只保留最新一条，避免后台重复发布占用一个完整轮次。
                if (pendingAssistant == null) {
                    pendingAssistant = message;
                }
            } else if ("user".equals(role) && pendingAssistant != null) {
                newestTurns.add(List.of(message, pendingAssistant));
                pendingAssistant = null;
                if (newestTurns.size() == ROUTING_HISTORY_MAX_TURNS) {
                    break;
                }
            }
        }
        if (newestTurns.isEmpty()) {
            return List.of();
        }

        Collections.reverse(newestTurns);
        List<Message> chronologicalMessages = newestTurns.stream()
                .flatMap(List::stream)
                .toList();
        int separatorCharacters = chronologicalMessages.size() - 1;
        int perMessageLimit = Math.min(
                ROUTING_HISTORY_MAX_MESSAGE_CHARS,
                (ROUTING_HISTORY_MAX_TOTAL_CHARS - separatorCharacters) / chronologicalMessages.size()
        );
        return chronologicalMessages.stream()
                .map(message -> formatRoutingMessage(message, perMessageLimit))
                .toList();
    }

    /** 只允许路由上下文使用 user/assistant 两种明确角色。 */
    private String normalizedRoutingRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "user", "assistant" -> normalized;
            default -> "";
        };
    }

    /** 把单条持久化消息压平成有角色前缀、单行且有限长的路由上下文。 */
    private String formatRoutingMessage(Message message, int maxLength) {
        String role = normalizedRoutingRole(message.getRole());
        String content = message.getContent() == null
                ? ""
                : message.getContent().replaceAll("\\s+", " ").trim();
        String formatted = role + ": " + content;
        return formatted.substring(0, Math.min(maxLength, formatted.length()));
    }
}
