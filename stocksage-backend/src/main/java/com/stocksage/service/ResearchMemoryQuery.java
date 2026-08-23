package com.stocksage.service;

import com.stocksage.agent.intent.AnalysisDepth;
import com.stocksage.agent.intent.TimeSensitivity;

/**
 * 一次研究记忆检索所需的用户、问题和意图上下文。
 *
 * <p>该契约只承载上游已经识别出的信息，不触发新的意图识别或模型调用。</p>
 */
public record ResearchMemoryQuery(
        String userId,
        String ticker,
        String query,
        String traceId,
        AnalysisDepth analysisDepth,
        TimeSensitivity timeSensitivity
) {

    /** 把可选文本收敛为空串，并为缺失的意图枚举提供安全默认值。 */
    public ResearchMemoryQuery {
        userId = safeText(userId);
        ticker = safeText(ticker);
        query = safeText(query);
        traceId = safeText(traceId);
        analysisDepth = analysisDepth == null ? AnalysisDepth.UNSPECIFIED : analysisDepth;
        timeSensitivity = timeSensitivity == null
                ? TimeSensitivity.UNSPECIFIED
                : timeSensitivity;
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
