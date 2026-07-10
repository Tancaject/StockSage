package com.stocksage.memory;

import com.stocksage.model.dto.MemoryCompressionResult;
import com.stocksage.model.dto.MemoryContextDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的滚动式会话记忆。
 *
 * <p>数据库保存完整消息历史；该组件只保留一小段可直接放入提示词的窗口，
 * 并在需要时附加摘要，避免长对话撑爆模型上下文。</p>
 */
@Slf4j
@Component
public class ShortTermMemory {

    private static final String KEY_PREFIX = "chat:conv:";
    private static final String SUMMARY_ROLE = "system";

    private final StringRedisTemplate stringRedisTemplate;
    private final ChatClient memoryChatClient;

    @Value("${stocksage.memory.short-term-ttl-hours}")
    private int ttlHours;

    @Value("${stocksage.memory.max-context-messages}")
    private int maxContextMessages;

    @Value("${stocksage.memory.llm-summary.enabled:true}")
    private boolean llmSummaryEnabled;

    public ShortTermMemory(StringRedisTemplate stringRedisTemplate,
                           @Qualifier("memoryChatClient") ChatClient memoryChatClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.memoryChatClient = memoryChatClient;
    }

    /**
     * 返回某个会话当前短期记忆快照。
     *
     * <p>该方法主要供调试接口使用，会同时返回消息列表、数量、窗口上限和 TTL 配置。</p>
     */
    public MemoryContextDTO getContextSnapshot(Long conversationId) {
        List<String> messages = getContext(conversationId);
        return MemoryContextDTO.builder()
                .conversationId(conversationId)
                .messages(messages)
                .messageCount(messages.size())
                .maxContextMessages(maxContextMessages)
                .ttlHours(ttlHours)
                .build();
    }

    /**
     * 读取 Redis 中某个会话的可注入上下文列表。
     */
    public List<String> getContext(Long conversationId) {
        List<String> raw = stringRedisTemplate.opsForList().range(key(conversationId), 0, -1);
        if (raw == null) {
            return List.of();
        }
        return raw;
    }

    /**
     * 向短期记忆追加一条角色消息，并在必要时触发压缩。
     *
     * @param conversationId 会话 ID
     * @param role 消息角色
     * @param content 消息正文
     * @return 写入后的短期记忆快照
     */
    public MemoryContextDTO addMessage(Long conversationId, String role, String content) {
        String value = normalizeRole(role) + ": " + content.trim();
        String key = key(conversationId);
        stringRedisTemplate.opsForList().rightPush(key, value);
        refreshTtl(key);
        compressIfNeeded(conversationId);
        return getContextSnapshot(conversationId);
    }

    /**
     * 当 Redis 列表超过配置的提示词窗口时压缩较早轮次，并原样保留最近消息。
     */
    public MemoryCompressionResult compressIfNeeded(Long conversationId) {
        List<String> messages = getContext(conversationId);
        int beforeCount = messages.size();
        if (beforeCount <= maxContextMessages) {
            return MemoryCompressionResult.builder()
                    .conversationId(conversationId)
                    .compressed(false)
                    .beforeCount(beforeCount)
                    .afterCount(beforeCount)
                    .maxContextMessages(maxContextMessages)
                    .build();
        }

        int keepRecent = Math.max(4, maxContextMessages / 2);
        int splitIndex = Math.max(0, beforeCount - keepRecent);
        List<String> older = messages.subList(0, splitIndex);
        List<String> recent = messages.subList(splitIndex, beforeCount);

        List<String> compacted = new ArrayList<>();
        compacted.add(SUMMARY_ROLE + ": " + buildSummary(older));
        compacted.addAll(recent);

        replaceContext(conversationId, compacted);
        log.debug("Compressed short-term memory for conversationId={} from {} to {} messages",
                conversationId, beforeCount, compacted.size());

        return MemoryCompressionResult.builder()
                .conversationId(conversationId)
                .compressed(true)
                .beforeCount(beforeCount)
                .afterCount(compacted.size())
                .maxContextMessages(maxContextMessages)
                .build();
    }

    /**
     * 清空指定会话的短期记忆。
     */
    public void clear(Long conversationId) {
        stringRedisTemplate.delete(key(conversationId));
    }

    /**
     * 用一组消息整体替换会话短期记忆。
     *
     * <p>常用于从数据库历史消息重建 Redis 窗口，空值和空白消息会被过滤。</p>
     */
    public void replaceWithMessages(Long conversationId, List<String> messages) {
        List<String> sanitized = messages == null
                ? List.of()
                : messages.stream()
                        .filter(message -> message != null && !message.isBlank())
                        .toList();
        replaceContext(conversationId, sanitized);
        if (!sanitized.isEmpty()) {
            compressIfNeeded(conversationId);
        }
    }

    /**
     * 原子化地删除旧列表并写入新上下文。
     *
     * <p>这里不做复杂事务控制，因为短期记忆只是提示词缓存，真实聊天历史仍由数据库保存。</p>
     */
    private void replaceContext(Long conversationId, List<String> messages) {
        String key = key(conversationId);
        stringRedisTemplate.delete(key);
        if (!messages.isEmpty()) {
            stringRedisTemplate.opsForList().rightPushAll(key, messages);
        }
        refreshTtl(key);
    }

    /**
     * 为较早消息生成摘要。
     *
     * <p>优先使用记忆模型压缩；模型失败或返回空内容时回退到确定性摘要，保证压缩流程不中断。</p>
     */
    private String buildSummary(List<String> messages) {
        if (!llmSummaryEnabled) {
            return buildDeterministicSummary(messages);
        }
        try {
            String summary = memoryChatClient.prompt()
                    .user("""
                            请将以下较早对话压缩为短期记忆摘要，保留：
                            1. 用户目标和约束
                            2. 已经给出的关键事实
                            3. 尚未解决的问题
                            4. 用户偏好或已确认的选择

                            对话：
                            %s
                            """.formatted(String.join("\n", messages)))
                    .call()
                    .content();
            if (summary == null || summary.isBlank()) {
                return buildDeterministicSummary(messages);
            }
            return summary.trim();
        } catch (Exception e) {
            log.warn("LLM short-term memory compression failed, using deterministic summary: {}", e.getMessage());
            return buildDeterministicSummary(messages);
        }
    }

    /**
     * 无模型模式下的确定性摘要兜底。
     *
     * <p>它不会真正理解内容，只保留前若干条消息作为可读提示，适合本地离线或模型不可用时使用。</p>
     */
    private String buildDeterministicSummary(List<String> messages) {
        if (messages.isEmpty()) {
            return "Earlier context was empty.";
        }
        int maxItems = Math.min(messages.size(), 12);
        String joined = String.join(" | ", messages.subList(0, maxItems));
        if (messages.size() > maxItems) {
            joined += " | ...";
        }
        return "Earlier conversation summary scaffold: " + joined;
    }

    /**
     * 刷新短期记忆 key 的过期时间。
     */
    private void refreshTtl(String key) {
        stringRedisTemplate.expire(key, Duration.ofHours(ttlHours));
    }

    /**
     * 归一化消息角色。
     *
     * <p>未知角色按 user 处理，避免非法角色污染提示词。</p>
     */
    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase();
        return switch (normalized) {
            case "user", "assistant", "system" -> normalized;
            default -> "user";
        };
    }

    /**
     * 构造会话短期记忆 Redis key。
     */
    private String key(Long conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
