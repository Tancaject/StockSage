package com.stocksage.model.dto;

/**
 * 工作台股票搜索接口返回的一条候选标的。
 *
 * @param ticker 归一化后的证券代码，可直接写入工作台自选列表
 * @param name 证券或公司展示名；数据源缺失名称时可退化为 ticker
 * @param market 数据源识别的市场，例如 US、A_SHARE 或 HK；可能为空
 * @param source 候选数据提供方或解析来源；可能为空
 */
public record WorkbenchStockSuggestion(
        String ticker,
        String name,
        String market,
        String source
) {
}
