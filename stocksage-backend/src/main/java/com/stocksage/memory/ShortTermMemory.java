package com.stocksage.memory;

import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.MessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis 的滚动式会话记忆。
 *
 * <p>数据库保存完整消息历史；该组件只保留一小段可直接放入提示词的窗口，
 * 并在需要时附加摘要，避免长对话撑爆模型上下文。</p>
 */
@Slf4j
@Component
public class ShortTermMemory {

    /** Redis 会话窗口键前缀。 */
    private static final String KEY_PREFIX = "chat:conv:";
    /** 压缩摘要写回缓存的内部角色；ChatService 注入时会降为不可信用户上下文。 */
    private static final String SUMMARY_ROLE = "system";
    private static final int SUMMARY_INPUT_MAX_CHARS = 20_000;
    private static final int DETERMINISTIC_SUMMARY_MAX_CHARS = 800;

    /** 读写会话消息列表和 TTL 的 Redis 客户端。 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 长窗口压缩所用的专用模型客户端。 */
    private final ChatClient memoryChatClient;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    /** Redis 持续不可用时仅记录一次警告的状态位。 */
    private final AtomicBoolean redisUnavailableWarned = new AtomicBoolean(false);
    /** 正在后台压缩的会话，避免同一会话并发压缩互相覆盖。 */
    private final Set<Long> compressing = ConcurrentHashMap.newKeySet();

    /** 每次写入后刷新的会话记忆 TTL，单位小时。 */
    @Value("${stocksage.memory.short-term-ttl-hours}")
    private int ttlHours;

    /** 触发后台压缩的消息条数阈值。 */
    @Value("${stocksage.memory.max-context-messages}")
    private int maxContextMessages;

    /** 是否优先用模型生成旧消息摘要；关闭时使用确定性拼接。 */
    @Value("${stocksage.memory.llm-summary.enabled:true}")
    private boolean llmSummaryEnabled;

    /**
     * 创建短期记忆组件。
     *
     * @param stringRedisTemplate Redis 字符串客户端
     * @param memoryChatClient 摘要模型客户端
     */
    public ShortTermMemory(StringRedisTemplate stringRedisTemplate,
                           @Qualifier("memoryChatClient") ChatClient memoryChatClient,
                           ConversationRepository conversationRepository,
                           MessageRepository messageRepository) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.memoryChatClient = memoryChatClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 读取 Redis 中某个会话的可注入上下文列表。
     *
     * @param conversationId 会话 ID
     * @return 按写入顺序排列的消息；Redis 不可用时返回空列表
     */
    public List<String> getContext(Long conversationId) {
        try {
            // 调用 Redis LRANGE 一次取回当前缓存窗口，顺序与对话发生顺序一致。
            List<String> raw = stringRedisTemplate.opsForList().range(key(conversationId), 0, -1);
            markRedisAvailable();
            return raw == null ? List.of() : raw;
        } catch (Exception e) {
            warnRedisUnavailable("read", conversationId, e);
            return List.of();
        }
    }

    /**
     * 向短期记忆追加一条角色消息；压缩由回答完成后的后台任务触发。
     *
     * @param conversationId 会话 ID
     * @param role 消息角色
     * @param content 消息正文
     */
    public void addMessage(Long conversationId, String role, String content) {
        try {
            String value = normalizeRole(role) + ": " + content.trim();
            String key = key(conversationId);
            stringRedisTemplate.opsForList().rightPush(key, value);
            refreshTtl(key);
            markRedisAvailable();
        } catch (Exception e) {
            warnRedisUnavailable("append", conversationId, e);
        }
    }

    /**
     * 当未压缩历史超过提示词窗口时持久化较早轮次摘要，并原样保留最近消息。
     *
     * @param conversationId 会话 ID
     */
    public void compressIfNeeded(Long conversationId) {
        // 同一会话只跑一个压缩；被跳过的触发会由下一次回答完成重新检查。
        if (!compressing.add(conversationId)) {
            return;
        }
        try {
            Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
            if (conversation == null) {
                return;
            }
            List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            // 旧 Redis 摘要没有消息 ID，只有尾部逐条对应数据库时才能确定覆盖边界。
            if (conversation.getMemorySummaryThroughMessageId() == null) {
                List<String> cached = getContext(conversationId);
                int tailSize = cached.size() - 1;
                if (tailSize > 0 && tailSize < history.size() && cached.get(0).startsWith(SUMMARY_ROLE + ": ")) {
                    List<Message> tail = history.subList(history.size() - tailSize, history.size());
                    boolean matches = true;
                    for (int i = 0; i < tail.size(); i++) {
                        Message message = tail.get(i);
                        String legacyEntry = normalizeRole(message.getRole()) + ": " + message.getContent().trim();
                        matches &= cached.get(i + 1).equals(toMemoryMessage(message))
                                || cached.get(i + 1).equals(legacyEntry);
                    }
                    String cachedSummary = cached.get(0).substring(SUMMARY_ROLE.length() + 2);
                    Long covered = history.get(history.size() - tailSize - 1).getId();
                    if (matches && conversationRepository.saveMemorySummary(conversationId, cachedSummary, covered, null) > 0) {
                        conversation.setMemorySummary(cachedSummary);
                        conversation.setMemorySummaryThroughMessageId(covered);
                    }
                }
            }
            List<String> messages = restoreContext(conversation, history);
            int beforeCount = messages.size();
            if (beforeCount <= maxContextMessages) {
                return;
            }

            int keepRecent = Math.max(4, maxContextMessages / 2);
            int splitIndex = Math.max(0, beforeCount - keepRecent);
            List<String> older = messages.subList(0, splitIndex);
            if (splitIndex == 0 || (older.size() == 1 && older.get(0).startsWith(SUMMARY_ROLE + ": "))) {
                return;
            }
            Long throughMessageId = history.get(history.size() - keepRecent - 1).getId();
            String summary = buildSummary(older);

            List<Message> latest = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            if (latest.size() < history.size() || !latest.subList(0, history.size()).equals(history)
                    || conversationRepository.saveMemorySummary(conversationId, summary, throughMessageId,
                    conversation.getMemorySummaryThroughMessageId()) == 0) {
                log.debug("Skipped stale short-term memory compression for conversationId={}", conversationId);
                return;
            }
            conversation.setMemorySummary(summary);
            conversation.setMemorySummaryThroughMessageId(throughMessageId);
            List<String> compacted = restoreContext(conversation, latest);
            replaceContext(conversationId, compacted);
            log.debug("Compressed short-term memory for conversationId={} from {} to {} messages",
                    conversationId, latest.size(), compacted.size());
        } finally {
            compressing.remove(conversationId);
        }
    }

    /**
     * 清空指定会话的短期记忆；数据库中的完整聊天记录不受影响。
     *
     * @param conversationId 会话 ID
     */
    public void clear(Long conversationId) {
        try {
            stringRedisTemplate.delete(key(conversationId));
            markRedisAvailable();
        } catch (Exception e) {
            warnRedisUnavailable("clear", conversationId, e);
        }
    }

    /**
     * 用一组消息整体替换会话短期记忆。
     *
     * <p>常用于从数据库历史消息重建 Redis 窗口，空值和空白消息会被过滤。</p>
     *
     * @param conversationId 会话 ID
     * @param messages 已按时间排序的角色消息文本
     */
    public void replaceWithMessages(Long conversationId, List<String> messages) {
        List<String> sanitized = messages == null
                ? List.of()
                : messages.stream()
                        .filter(message -> message != null && !message.isBlank())
                        .toList();
        replaceContext(conversationId, sanitized);
    }

    public List<String> restoreContext(Long conversationId, List<Message> history) {
        return restoreContext(conversationRepository.findById(conversationId).orElse(null), history);
    }

    private List<String> restoreContext(Conversation conversation, List<Message> history) {
        List<String> restored = new ArrayList<>();
        int firstUncovered = 0;
        if (conversation != null && conversation.getMemorySummary() != null
                && !conversation.getMemorySummary().isBlank()) {
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).getId().equals(conversation.getMemorySummaryThroughMessageId())) {
                    restored.add(SUMMARY_ROLE + ": " + conversation.getMemorySummary());
                    firstUncovered = i + 1;
                    break;
                }
            }
        }
        history.subList(firstUncovered, history.size()).stream().map(ShortTermMemory::toMemoryMessage)
                .forEach(restored::add);
        return restored;
    }

    public static String toMemoryMessage(Message message) {
        String role = message.getRole() == null ? "user" : message.getRole().trim().toLowerCase(Locale.ROOT);
        String content = message.getContent() == null ? "" : message.getContent().trim();
        return role + ": " + ("assistant".equals(role)
                ? assistantMemoryContent(content, message.getCreatedAt()) : content);
    }

    public static String assistantMemoryContent(String content, LocalDateTime createdAt) {
        return (createdAt == null ? "" : "（" + createdAt.toLocalDate() + " 的回答）\n")
                + (content == null ? "" : content.trim());
    }

    /**
     * 先准备带 TTL 的临时列表，再原子替换会话上下文。
     *
     * @param conversationId 会话 ID
     * @param messages 新的完整窗口
     */
    private void replaceContext(Long conversationId, List<String> messages) {
        String key = key(conversationId);
        String temporaryKey = key + ":replace:" + UUID.randomUUID();
        try {
            if (messages.isEmpty()) {
                stringRedisTemplate.delete(key);
            } else {
                stringRedisTemplate.opsForList().rightPushAll(temporaryKey, messages);
                refreshTtl(temporaryKey);
                // shortcut: 压缩路径已在摘要后重读补尾，剩余的毫秒级窗口由 ChatService 的一致性校验自愈。
                stringRedisTemplate.rename(temporaryKey, key);
            }
            markRedisAvailable();
        } catch (Exception e) {
            try {
                stringRedisTemplate.delete(temporaryKey);
            } catch (Exception cleanupError) {
                e.addSuppressed(cleanupError);
            }
            warnRedisUnavailable("replace", conversationId, e);
        }
    }

    /**
     * 为较早消息生成摘要。
     *
     * <p>优先使用记忆模型压缩；模型失败或返回空内容时回退到确定性摘要，保证压缩流程不中断。</p>
     *
     * @param messages 要压缩的较早消息
     * @return 一段作为不可信历史上下文注入的摘要
     */
    private String buildSummary(List<String> messages) {
        if (!llmSummaryEnabled) {
            return buildDeterministicSummary(messages);
        }
        boolean hasPreviousSummary = !messages.isEmpty() && messages.get(0).startsWith(SUMMARY_ROLE + ": ");
        String previousSummary = hasPreviousSummary ? messages.get(0).substring(SUMMARY_ROLE.length() + 2) : "";
        List<String> olderMessages = hasPreviousSummary ? messages.subList(1, messages.size()) : messages;
        String olderText = String.join("\n", olderMessages);
        int offset = 0;
        try {
            while (offset < olderText.length()) {
                int available = SUMMARY_INPUT_MAX_CHARS - summaryPrompt(previousSummary, "").length();
                if (available <= 0) {
                    break;
                }
                int end = Math.min(olderText.length(), offset + available);
                String summary = memoryChatClient.prompt()
                        .user(summaryPrompt(previousSummary, olderText.substring(offset, end)))
                        .call().content();
                if (summary == null || summary.isBlank()) {
                    break;
                }
                previousSummary = summary.trim();
                offset = end;
            }
        } catch (Exception e) {
            log.warn("LLM short-term memory compression failed, using deterministic summary: {}", e.getMessage());
        }
        if (offset < olderText.length()) {
            List<String> remaining = new ArrayList<>();
            if (!previousSummary.isEmpty()) {
                remaining.add(SUMMARY_ROLE + ": " + previousSummary);
            }
            int messageStart = 0;
            for (String message : olderMessages) {
                int messageEnd = messageStart + message.length();
                if (offset < messageEnd) {
                    remaining.add(message.substring(Math.max(0, offset - messageStart)));
                }
                messageStart = messageEnd + 1;
            }
            return buildDeterministicSummary(remaining);
        }
        return previousSummary.isEmpty() ? "Earlier context was empty." : previousSummary;
    }

    private String summaryPrompt(String previousSummary, String olderText) {
        return """
                            请将已有摘要与新增的较早对话合并为滚动短期记忆摘要，输出不超过 600 字，保留：
                            1. 用户目标和约束；约束保留用户原话
                            2. 关键陈述的说话者、证据范围和不确定性；助手的推测或建议不能写成用户确认的事实
                            3. 尚未解决的问题
                            4. 用户偏好或已确认的选择

                            后续已更正的内容以更正为准；有争议或未核实的陈述保持原有状态，不合并成确定结论。

                            已有摘要：
                            %s

                            新增的较早对话：
                            %s
                            """.formatted(previousSummary.isEmpty() ? "无" : previousSummary, olderText);
    }

    /**
     * 无模型模式下的确定性摘要兜底：有损的近期历史摘录。
     *
     * <p>水位只表示这些消息已移出逐条窗口，不表示信息完整保留。从最新往前取整条消息，
     * 第一条放不下的只截到整段或整句并标记省略，更早的内容（含旧摘要）整体标记省略，不留半句残文。</p>
     *
     * @param messages 要压缩的较早消息
     * @return 不超过 {@value #DETERMINISTIC_SUMMARY_MAX_CHARS} 字左右的近期摘录
     */
    private String buildDeterministicSummary(List<String> messages) {
        if (messages.isEmpty()) {
            return "Earlier context was empty.";
        }
        String header = "较早对话的近期摘录（确定性兜底，有损，非完整记录）：";
        int remaining = DETERMINISTIC_SUMMARY_MAX_CHARS - header.length() - 40;
        LinkedList<String> kept = new LinkedList<>();
        int index = messages.size() - 1;
        for (; index >= 0; index--) {
            String message = messages.get(index);
            if (message.startsWith(SUMMARY_ROLE + ": ")) {
                message = "此前摘要：" + message.substring(SUMMARY_ROLE.length() + 2);
            }
            if (message.length() <= remaining) {
                kept.addFirst(message);
                remaining -= message.length() + 1;
                continue;
            }
            // 旧摘要只整体保留；消息只截到放得下的整段或整句为止，并明确标记后文省略。
            if (!message.startsWith("此前摘要：")) {
                String marker = "（本条后文已省略）";
                StringBuilder head = new StringBuilder();
                for (String sentence : message.split("(?<=[\n。！？!?])|(?<=\\. )")) {
                    if (head.length() + sentence.length() + marker.length() > remaining) {
                        break;
                    }
                    head.append(sentence);
                }
                int roleEnd = message.indexOf(": ");
                kept.addFirst(head.isEmpty()
                        ? (roleEnd > 0 ? message.substring(0, roleEnd + 2) : "") + "（内容过长，已省略）"
                        : head + marker);
                index--;
            }
            break;
        }
        if (index >= 0) {
            boolean summaryOmitted = messages.get(0).startsWith(SUMMARY_ROLE + ": ");
            int omittedMessages = index + 1 - (summaryOmitted ? 1 : 0);
            kept.addFirst("（更早" + (omittedMessages > 0 ? " " + omittedMessages + " 条消息" : "")
                    + (summaryOmitted ? (omittedMessages > 0 ? "及" : "") + "摘要" : "") + "已省略）");
        }
        return header + "\n" + String.join("\n", kept);
    }

    /** 刷新短期记忆 key 的过期时间。 */
    private void refreshTtl(String key) {
        stringRedisTemplate.expire(key, Duration.ofHours(ttlHours));
    }

    /** 在一段连续故障中只记录第一次 Redis 不可用警告。 */
    private void warnRedisUnavailable(String operation, Long conversationId, Exception error) {
        if (redisUnavailableWarned.compareAndSet(false, true)) {
            log.warn("Short-term memory Redis unavailable during {} for conversationId={}; "
                            + "continuing with empty context: {}",
                    operation, conversationId, error.getMessage());
        }
    }

    /** Redis 调用恢复成功后重置告警抑制状态。 */
    private void markRedisAvailable() {
        redisUnavailableWarned.set(false);
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
