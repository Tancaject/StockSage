package com.stocksage.repository;

import com.stocksage.model.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话消息仓储。
 *
 * <p>该仓储保存单条聊天消息正文，与 {@link ConversationRepository} 中的会话元数据配合使用。
 * 删除接口用于会话删除和“从某条消息重新生成”场景，调用方需要先确认会话归属用户。</p>
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /** 按时间正序查询会话的所有消息，用于加载完整对话历史。 */
    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /** Prevent a resumed background task from inserting the same final report twice. */
    boolean existsByConversationIdAndRoleAndTraceIdAndContent(
            Long conversationId,
            String role,
            String traceId,
            String content
    );

    /** 删除某个会话下的全部消息，通常在删除会话时一起执行。 */
    @Transactional
    void deleteByConversationId(Long conversationId);

    /** 删除某个会话中从指定消息 ID 开始的后续消息，用于重新生成回答前裁剪旧分支。 */
    @Transactional
    void deleteByConversationIdAndIdGreaterThanEqual(Long conversationId, Long id);
}
