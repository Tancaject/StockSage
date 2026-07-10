package com.stocksage.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 回答级 RAG 评估接口的请求体。
 *
 * @param question 评测问题，不能为空
 * @param referenceAnswer 数据集中的参考答案
 * @param answerable 该问题是否应当能被当前知识库回答
 * @param includeIntermediate 是否返回检索中间阶段，空值默认返回
 * @param maxContextChars 注入回答模型的最大上下文字数，空值或非正数使用默认值
 */
public record RagEvalRequest(
        @JsonProperty("question")
        @NotBlank
        String question,

        @JsonProperty("reference_answer")
        String referenceAnswer,

        @JsonProperty("answerable")
        Boolean answerable,

        @JsonProperty("include_intermediate")
        Boolean includeIntermediate,

        @JsonProperty("max_context_chars")
        Integer maxContextChars
) {
    /**
     * 返回 includeIntermediate 的业务默认值。
     *
     * <p>评测脚本通常需要中间阶段来定位问题，因此未传值时默认开启。</p>
     */
    public boolean includeIntermediateOrDefault() {
        return includeIntermediate == null || includeIntermediate;
    }

    /**
     * 返回最大上下文字数的业务默认值。
     *
     * <p>默认 6000 字符在保留足够证据和控制评测耗时之间做了折中。</p>
     */
    public int maxContextCharsOrDefault() {
        if (maxContextChars == null || maxContextChars <= 0) {
            return 6000;
        }
        return maxContextChars;
    }
}
