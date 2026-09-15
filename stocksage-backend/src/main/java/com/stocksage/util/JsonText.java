package com.stocksage.util;

/** 模型输出的 JSON 文本提取；结构校验和解析失败策略由调用方负责。 */
public final class JsonText {
    private JsonText() {
    }

    public static String extractObject(String content) {
        if (content == null) {
            return "{}";
        }
        String trimmed = content.trim()
                .replaceAll("(?is)^```json\\s*", "")
                .replaceAll("(?is)^```\\s*", "")
                .replaceAll("(?is)\\s*```$", "")
                .trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }
}
