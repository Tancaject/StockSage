package com.stocksage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 为侧边栏会话生成短标题。
 *
 * <p>{@link ChatService} 在首轮消息后异步调用该服务；模型关闭、失败或输出无效时，
 * 始终回退到用户原话截断，因此标题生成不会阻断聊天主链。</p>
 */
@Slf4j
@Service
public class ConversationTitleService {

    /** 模型标题清洗后的最大字符数。 */
    private static final int MAX_TITLE_LENGTH = 24;

    /** 复用记忆模型生成低成本短标题。 */
    private final ChatClient chatClient;

    /** 是否启用模型标题；关闭时只使用确定性标题。 */
    @Value("${stocksage.chat.title-generation.enabled:true}")
    private boolean enabled;

    /** @param chatClient 标题生成所用的记忆模型客户端。 */
    public ConversationTitleService(@Qualifier("memoryChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 从首条用户消息构造无需模型的保底标题。
     *
     * @param userMessage 首条用户消息
     * @return 最长 50 字符的标题；空消息返回“新对话”
     */
    public String fallbackTitle(String userMessage) {
        String message = userMessage == null || userMessage.isBlank() ? "新对话" : userMessage.trim();
        return message.length() > 50 ? message.substring(0, 50) + "..." : message;
    }

    /**
     * 调用记忆模型生成投研主题标题，并清洗代码围栏、引号和换行。
     *
     * @param userMessage 首条用户消息
     * @return 清洗后的短标题；禁用或失败时返回 fallbackTitle
     */
    public String generateTitle(String userMessage) {
        if (!enabled || userMessage == null || userMessage.isBlank()) {
            return fallbackTitle(userMessage);
        }
        try {
            // 调用 memoryChatClient；该次附属调用失败不会影响聊天回答。
            String content = chatClient.prompt()
                    .user("""
                            请为下面这轮投研对话生成一个侧边栏标题。
                            要求：
                            - 使用简体中文
                            - 不超过 12 个汉字或 24 个英文字符
                            - 不要加引号、句号、解释或 Markdown
                            - 优先包含 ticker、公司名、财报/估值/持仓等主题词

                            用户问题：
                            %s
                            """.formatted(userMessage.trim()))
                    .call()
                    .content();
            String title = sanitizeTitle(content);
            return title.isBlank() ? fallbackTitle(userMessage) : title;
        } catch (Exception e) {
            log.warn("Conversation title generation failed: {}", e.getMessage());
            return fallbackTitle(userMessage);
        }
    }

    /** 将模型输出收敛为侧边栏可直接展示的一行短文本。 */
    private String sanitizeTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        String title = rawTitle
                .replaceAll("(?is)^```.*?\\R", "")
                .replace("```", "")
                .replace("\"", "")
                .replace("“", "")
                .replace("”", "")
                .trim();
        int newline = title.indexOf('\n');
        if (newline >= 0) {
            title = title.substring(0, newline).trim();
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
        return title;
    }
}
