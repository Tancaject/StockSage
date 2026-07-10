package com.stocksage.service;

/**
 * 提示词文本的通用处理工具。
 */
final class PromptText {

    private PromptText() {
    }

    /**
     * 截断写入提示词的大段文本。
     */
    static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "\n...[truncated]" : text;
    }
}
