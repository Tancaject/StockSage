package com.stocksage.repository;

import com.stocksage.model.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 会话仓储。
 *
 * <p>负责持久化用户与系统之间的对话会话元数据，消息正文由 {@link MessageRepository} 管理。
 * 列表查询按更新时间倒序返回，便于前端优先展示最近活跃的会话。</p>
 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /** 按更新时间倒序查询用户的所有会话，保留给后台排查和兼容路径。 */
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(String userId);

    /** 按来源过滤会话，聊天侧栏只展示 origin=chat 的记录。 */
    List<Conversation> findByUserIdAndOriginOrderByUpdatedAtDesc(String userId, String origin);
}
