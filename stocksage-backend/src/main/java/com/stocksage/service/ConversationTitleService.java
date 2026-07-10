package com.stocksage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ConversationTitleService {

    private static final int MAX_TITLE_LENGTH = 24;

    private final ChatClient chatClient;

    @Value("${stocksage.chat.title-generation.enabled:true}")
    private boolean enabled;

    public ConversationTitleService(@Qualifier("memoryChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String fallbackTitle(String userMessage) {
        String message = userMessage == null || userMessage.isBlank() ? "新对话" : userMessage.trim();
        return message.length() > 50 ? message.substring(0, 50) + "..." : message;
    }

    public String generateTitle(String userMessage) {
        if (!enabled || userMessage == null || userMessage.isBlank()) {
            return fallbackTitle(userMessage);
        }
        try {
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
