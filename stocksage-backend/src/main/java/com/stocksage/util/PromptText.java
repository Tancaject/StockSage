package com.stocksage.util;

/**
 * 提示词文本的通用处理工具。
 *
 * <p>仅负责确定性长度收敛，不做语义摘要；调用方仍应选择哪些数据值得注入提示词。</p>
 */
public final class PromptText {

    private PromptText() {
    }

    /**
     * 截断写入提示词的大段文本。
     *
     * @param text 原始文本
     * @param maxLength 保留的最大字符数
     * @return 截断文本；发生截断时追加可见标记，null 返回空串
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || maxLength <= 0) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        String marker = "\n...[truncated]";
        if (maxLength <= marker.length()) {
            return text.substring(0, maxLength);
        }
        return text.substring(0, maxLength - marker.length()) + marker;
    }
}
