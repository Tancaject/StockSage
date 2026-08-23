package com.stocksage.model.dto;

/**
 * 投资研究结论适用的分析期限。
 *
 * <p>该枚举是报告 JSON 和研究记忆冲突键的一部分。无法从问题或报告中可靠判断期限时，
 * 必须显式使用 {@link #UNSPECIFIED}，不能猜测。</p>
 */
public enum AnalysisHorizon {
    /** 关注近期行情、事件或技术面变化。 */
    SHORT_TERM,

    /** 关注数月尺度的经营、估值和催化变化。 */
    MEDIUM_TERM,

    /** 关注长期基本面、竞争力和投资逻辑。 */
    LONG_TERM,

    /** 当前证据无法可靠确定分析期限。 */
    UNSPECIFIED
}
