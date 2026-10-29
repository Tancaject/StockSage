package com.stocksage.util;

/**
 * 提示词文本的通用处理工具。
 *
 * <p>负责长度收敛和数据区段边界，不做语义摘要。</p>
 */
public final class PromptText {

    private PromptText() {
    }

    /** 只转义提示词边界词；保留正文和证据协议标记，供后续引用核对。 */
    public static String untrustedContext(String text) {
        String content = (text == null ? "" : text)
                .replace("UNTRUSTED_CONTEXT", "UNTRUSTED＿CONTEXT")
                .replace("=== 本次调用的角色要求", "＝＝＝ 本次调用的角色要求");
        return "--- BEGIN UNTRUSTED_CONTEXT:DEEP_INPUT ---\n" + content
                + "\n--- END UNTRUSTED_CONTEXT:DEEP_INPUT ---\n";
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
