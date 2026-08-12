package com.stocksage.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * RAG 检索查询改写器。
 *
 * <p>用户原始问题往往包含口语表达、上下文代词或完整问句，不一定适合直接做向量检索。
 * 该服务通过轻量 ChatClient 把问题压缩成一行检索关键词，并在模型输出异常时回退到原始问题，
 * 确保查询改写只提升召回质量，不影响主流程稳定性。</p>
 */
@Slf4j
@Service
public class QueryRewriter {

    /** 只负责输出一行检索词的轻量模型客户端。 */
    private final ChatClient chatClient;

    /** 是否在召回前调用模型改写查询。 */
    @Value("${stocksage.rag.rewrite-query:true}")
    private boolean enabled;

    /** 改写结果允许的最大字符数，避免关键词失焦。 */
    @Value("${stocksage.rag.rewrite-query.max-length:180}")
    private int maxLength;

    /**
     * 注入专门用于查询改写的 ChatClient。
     *
     * <p>使用独立 Bean 可以把改写提示词和主聊天/研究智能体隔离开，避免检索预处理污染业务对话上下文。</p>
     *
     * @param chatClient {@code queryRewriteChatClient} 专用 Bean
     */
    public QueryRewriter(@Qualifier("queryRewriteChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 将用户问题改写为适合向量检索的一行关键词。
     *
     * <p>当开关关闭、问题为空、模型调用失败或模型输出明显无效时，都会直接返回原始 query。
     * 这种失败回退策略让 RAG 流程保持可用：改写是增强项，不是检索链路的硬依赖。</p>
     *
     * @param query 用户原始问题
     * @return 可用于召回的单行查询；改写失败时返回原文
     */
    public String rewrite(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return query;
        }

        try {
            // 专用 ChatClient.call() 只生成检索词，不进入主对话记忆或工具链。
            String rewritten = chatClient.prompt()
                    .user("""
                            用户问题：
                            %s

                            请输出适合向量检索的一行关键词。
                            """.formatted(query))
                    .call()
                    .content();

            String normalized = normalize(rewritten);
            if (normalized.isBlank() || isLikelyInvalid(normalized)) {
                return query;
            }
            return normalized;
        } catch (Exception e) {
            log.warn("RAG query rewrite failed, using original query: {}", e.getMessage());
            return query;
        }
    }

    /**
     * 清理模型返回的检索词。
     *
     * <p>模型可能返回 Markdown 代码块、项目符号、多行文本或多余引号，这里统一压缩成单行文本，
     * 并根据配置截断最大长度，避免过长查询拉低向量检索的语义聚焦度。</p>
     *
     * @param rewritten 模型原始输出
     * @return 去围栏、去换行并按上限截断的文本
     */
    private String normalize(String rewritten) {
        if (rewritten == null) {
            return "";
        }
        String normalized = rewritten.strip()
                .replaceAll("(?is)^```[a-zA-Z]*\\s*", "")
                .replaceAll("(?is)\\s*```$", "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("^[-*]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();

        normalized = stripWrappingQuotes(normalized);
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength).trim();
        }
        return normalized;
    }

    /**
     * 去掉包裹在整段检索词外侧的成对引号。
     *
     * <p>循环处理是为了兼容模型输出 {@code "关键词"}、{@code ```"关键词"```} 等多层包裹场景。</p>
     *
     * @param text 已完成基础清洗的文本
     * @return 去除所有成对外层包裹符的文本
     */
    private String stripWrappingQuotes(String text) {
        String result = text;
        while (result.length() >= 2 && isWrappingQuote(result.charAt(0), result.charAt(result.length() - 1))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    /**
     * 判断首尾字符是否是一组可剥离的包裹符。
     *
     * @param start 首字符
     * @param end 尾字符
     * @return 是否构成支持的引号或反引号对
     */
    private boolean isWrappingQuote(char start, char end) {
        return (start == '"' && end == '"')
                || (start == '\'' && end == '\'')
                || (start == '`' && end == '`')
                || (start == '“' && end == '”')
                || (start == '’' && end == '‘');
    }

    /**
     * 过滤模型拒答、道歉或无法处理类输出。
     *
     * <p>这些内容如果进入向量检索，会把召回方向带偏；识别到后直接使用用户原始问题更可靠。</p>
     *
     * @param text 规范化后的模型输出
     * @return 是否像拒答而不是检索词
     */
    private boolean isLikelyInvalid(String text) {
        String lower = text.toLowerCase();
        return lower.contains("无法")
                || lower.contains("不能")
                || lower.contains("sorry")
                || lower.contains("cannot")
                || lower.contains("can't")
                || lower.contains("i am unable");
    }
}
