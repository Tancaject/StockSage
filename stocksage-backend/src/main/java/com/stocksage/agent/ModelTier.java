package com.stocksage.agent;

import java.util.Locale;

/**
 * 最终回答模型的能力层级。
 *
 * <p>Coordinator 可以建议层级，但具体模型名称始终由后端配置映射，
 * 防止模型自行选择未审计或不存在的供应商模型。</p>
 */
public enum ModelTier {
    FAST,
    STANDARD,
    STRONG;

    /**
     * 从模型或配置文本解析层级，失败时返回调用方给定的兜底值。
     *
     * @param value 待解析文本
     * @param fallback 空值或未知值的安全兜底
     * @return 白名单层级或 fallback
     */
    public static ModelTier from(String value, ModelTier fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return ModelTier.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /**
     * 返回两个层级中能力更强的一个。
     *
     * @param left 第一个层级，空值按 STANDARD
     * @param right 第二个层级，空值按 STANDARD
     * @return ordinal 更高的层级
     */
    public static ModelTier max(ModelTier left, ModelTier right) {
        ModelTier safeLeft = left == null ? STANDARD : left;
        ModelTier safeRight = right == null ? STANDARD : right;
        return safeLeft.ordinal() >= safeRight.ordinal() ? safeLeft : safeRight;
    }
}
