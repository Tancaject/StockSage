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

    /**
     * 按创建时间正序查询会话全部消息，用于重建提示词和前端历史。
     *
     * @param conversationId 会话主键
     * @return 从最早到最新排列的消息；没有消息时为空列表
     */
    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /**
     * 读取同一会话最近六条持久化消息，供 Coordinator 路由前恢复最多三轮上下文。
     *
     * <p>结果按消息主键倒序返回；调用方在筛出完整 user/assistant 轮次后恢复为时间正序。
     * 查询条件包含 conversationId，避免不同会话的消息进入同一次路由判断。</p>
     *
     * @param conversationId 会话主键
     * @return 从最新到较早排列的最多六条消息
     */
    List<Message> findTop6ByConversationIdOrderByIdDesc(Long conversationId);

    /**
     * 检查同一后台任务是否已经写入完全相同的最终消息。
     *
     * <p>恢复或接管任务在持久化报告前调用它，避免同一 trace 的最终答复重复插入。</p>
     *
     * @param conversationId 目标会话主键
     * @param role 消息角色，后台最终答复通常为 assistant
     * @param traceId 后台任务关联链路 ID
     * @param content 最终消息完整正文
     * @return 四个条件均命中现有消息时为 true
     */
    boolean existsByConversationIdAndRoleAndTraceIdAndContent(
            Long conversationId,
            String role,
            String traceId,
            String content
    );

    /**
     * 删除某个会话下的全部消息，通常与会话元数据删除配套调用。
     *
     * @param conversationId 要清空的会话主键
     */
    @Transactional
    void deleteByConversationId(Long conversationId);

    /**
     * 删除会话中消息 ID 大于等于边界的所有记录。
     *
     * <p>用于“编辑后发送”或“重新生成”前裁剪旧分支；调用方必须先校验消息属于该会话和当前用户。</p>
     *
     * @param conversationId 目标会话主键
     * @param id 首条要删除的消息主键，边界包含自身
     */
    @Transactional
    void deleteByConversationIdAndIdGreaterThanEqual(Long conversationId, Long id);
}
