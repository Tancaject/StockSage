package com.stocksage.service;

/**
 * 提示词文本的通用处理工具。
 *
 * <p>仅负责确定性长度收敛，不做语义摘要；调用方仍应选择哪些数据值得注入提示词。</p>
 */
final class PromptText {

    private PromptText() {
    }

    /**
     * 截断写入提示词的大段文本。
     *
     * @param text 原始文本
     * @param maxLength 保留的最大字符数
     * @return 截断文本；发生截断时追加可见标记，null 返回空串
     */
    static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "\n...[truncated]" : text;
    }
}
