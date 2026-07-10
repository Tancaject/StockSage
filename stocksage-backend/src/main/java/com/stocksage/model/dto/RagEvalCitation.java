package com.stocksage.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 从评估回答中抽取的引用信息。
 *
 * <p>rank 会指回 {@link RagEvalContext} 返回的编号上下文，
 * 便于评估脚本打分引用精度和元数据准确性。</p>
 *
 * @param rank 引用指向的上下文排名
 * @param sourceId 来源 ID，用于跨上下文和原始文档定位
 * @param ticker 证券代码，便于评测脚本判断引用是否命中目标公司
 * @param filingType 公告或财报类型，例如 10-K、10-Q
 * @param filingDate 公告日期
 * @param section 引用所在章节或分区
 */
public record RagEvalCitation(
        @JsonProperty("rank")
        int rank,

        @JsonProperty("source_id")
        String sourceId,

        @JsonProperty("ticker")
        String ticker,

        @JsonProperty("filing_type")
        String filingType,

        @JsonProperty("filing_date")
        String filingDate,

        @JsonProperty("section")
        String section
) {
}
