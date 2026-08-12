package com.stocksage.model.dto;

/**
 * 单个规划路由的分类指标，供规划器回归报告按路由定位误判。
 *
 * @param precision 精确率，范围 0～1；表示预测为该路由的样本中实际命中的比例
 * @param recall 召回率，范围 0～1；表示期望该路由的样本中被正确识别的比例
 * @param f1 精确率与召回率的调和平均值，范围 0～1
 * @param expectedCount 评测集中期望路由为该值的样本数
 * @param predictedCount 规划器实际预测为该路由的样本数
 */
public record RouteEvalMetrics(
        double precision,
        double recall,
        double f1,
        int expectedCount,
        int predictedCount
) {
}
