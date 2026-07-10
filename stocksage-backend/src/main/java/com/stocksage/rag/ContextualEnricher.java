package com.stocksage.rag;

import com.stocksage.repository.ContextualGistCacheRepository;
import com.stocksage.service.KnowledgeIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Contextual Retrieval 的情境说明（gist）生成器。
 *
 * <p>给定切片及其所在父块与结构化元信息，生成一句"这段在该财报中讲什么"的说明，
 * 供入库时拼进子块 embedding 文本，使向量与 FULLTEXT 两条检索腿都携带上下文。
 * gist 按目标文本 sha256 缓存，重新入库幂等；任何失败都返回 null（fail-open），
 * 由调用方回退到纯结构化前缀，绝不阻断入库。</p>
 */
@Slf4j
@Service
public class ContextualEnricher {

    private static final int MAX_GIST_CHARS = 200;

    private final ChatClient chatClient;
    private final ContextualGistCacheRepository cacheRepository;

    @Value("${stocksage.rag.contextual.enabled:false}")
    private boolean enabled;

    @Value("${stocksage.rag.contextual.max-gist-words:40}")
    private int maxGistWords;

    @Value("${stocksage.chat.model-routing.fast-model:qwen3.6-flash}")
    private String modelName;

    /**
     * 注入专用于 gist 生成的 FAST 档 ChatClient，与主聊天链路隔离。
     */
    public ContextualEnricher(
            @Qualifier("contextualGistChatClient") ChatClient chatClient,
            ContextualGistCacheRepository cacheRepository) {
        this.chatClient = chatClient;
        this.cacheRepository = cacheRepository;
    }

    /**
     * 是否启用 gist 生成；供入库方在切片循环外快速短路。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 为目标切片生成一句情境说明。
     *
     * @param contextText 情境来源（父块全文）
     * @param targetText  被标注的切片文本，同时作为缓存 key 的哈希来源
     * @param granularity child 或 parent，仅用于缓存记录与排查
     * @return 一句情境说明；关闭、失败或输出无效时返回 null
     */
    public String generateGist(
            String contextText,
            String targetText,
            String granularity,
            String ticker,
            String companyName,
            String filingType,
            String filingDate,
            String sectionName) {
        if (!enabled || targetText == null || targetText.isBlank()) {
            return null;
        }

        String hash = KnowledgeIngestionService.sha256(targetText);
        try {
            var cached = cacheRepository.findGist(hash);
            if (cached.isPresent()) {
                return cached.get();
            }

            String gist = normalize(chatClient.prompt()
                    .user(buildUserPrompt(contextText, targetText, ticker, companyName,
                            filingType, filingDate, sectionName))
                    .call()
                    .content());
            if (gist.isBlank() || isLikelyInvalid(gist)) {
                return null;
            }

            cacheRepository.save(hash, gist, modelName, granularity);
            return gist;
        } catch (Exception e) {
            log.warn("Contextual gist generation failed (fail-open) for hash {}: {}", hash, e.getMessage());
            return null;
        }
    }

    /**
     * 构造 gist 生成的用户提示词：结构化元信息 + 父块情境 + 目标切片。
     */
    private String buildUserPrompt(
            String contextText,
            String targetText,
            String ticker,
            String companyName,
            String filingType,
            String filingDate,
            String sectionName) {
        return """
                公司：%s（%s）
                财报：%s，提交日期 %s
                章节：%s

                该切片所在的上下文（父块）：
                <context>
                %s
                </context>

                需要标注的切片：
                <chunk>
                %s
                </chunk>

                请输出一句不超过 %d 个词的中文情境说明，解释这段切片在该财报中讨论什么。
                """.formatted(
                companyName == null || companyName.isBlank() ? ticker : companyName,
                ticker == null ? "" : ticker.toUpperCase(),
                filingType, filingDate, sectionName,
                contextText == null ? "" : contextText,
                targetText,
                maxGistWords);
    }

    /**
     * 把模型输出压成单行短句：去代码块、去换行、去包裹引号、限制长度。
     */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.strip()
                .replaceAll("(?is)^```[a-zA-Z]*\\s*", "")
                .replaceAll("(?is)\\s*```$", "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        while (normalized.length() >= 2 && isWrapped(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if (normalized.length() > MAX_GIST_CHARS) {
            normalized = normalized.substring(0, MAX_GIST_CHARS).trim();
        }
        return normalized;
    }

    /**
     * 判断整句是否被成对引号包裹。
     */
    private boolean isWrapped(String text) {
        char start = text.charAt(0);
        char end = text.charAt(text.length() - 1);
        return (start == '"' && end == '"')
                || (start == '\'' && end == '\'')
                || (start == '“' && end == '”');
    }

    /**
     * 过滤模型拒答类输出，避免把"无法确定"之类的句子当成情境说明入库。
     */
    private boolean isLikelyInvalid(String text) {
        String lower = text.toLowerCase();
        return lower.contains("无法") || lower.contains("抱歉")
                || lower.contains("sorry") || lower.contains("cannot");
    }
}
