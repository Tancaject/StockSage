package com.stocksage.agent.intent;

/** 用户问题对数据时间范围的要求。 */
public enum TimeSensitivity {
    NONE,
    REAL_TIME,
    RECENT,
    HISTORICAL,
    UNSPECIFIED
}
