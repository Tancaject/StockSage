package com.stocksage.repository;

import com.stocksage.model.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话仓储。
 *
 * <p>负责持久化用户与系统之间的对话会话元数据，消息正文由 {@link MessageRepository} 管理。
 * 列表查询按更新时间倒序返回，便于前端优先展示最近活跃的会话。</p>
 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 按来源过滤用户会话并以最近活跃优先返回。
     *
     * @param userId 会话所属用户标识
     * @param origin 会话来源，例如 chat 或 workbench
     * @return 指定来源的会话列表；没有记录时为空列表
     */
    List<Conversation> findByUserIdAndOriginOrderByUpdatedAtDesc(String userId, String origin);

    /** 摘要期间若旧分支被删除或其他压缩已提交，不覆盖新的记忆。 */
    @Modifying
    @Transactional
    @Query("""
            update Conversation c set c.memorySummary = :summary, c.memorySummaryThroughMessageId = :through
            where c.id = :id
              and (c.memorySummaryThroughMessageId = :previous
                   or (c.memorySummaryThroughMessageId is null and :previous is null))
              and exists (select m.id from Message m where m.id = :through and m.conversationId = :id)
            """)
    int saveMemorySummary(@Param("id") Long conversationId, @Param("summary") String summary,
                          @Param("through") Long throughMessageId, @Param("previous") Long previousMessageId);
}
