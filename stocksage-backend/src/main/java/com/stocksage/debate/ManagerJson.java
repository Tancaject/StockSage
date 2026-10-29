package com.stocksage.debate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Research Manager 各阶段共用的严格 JSON 读取与提示词截断工具。 */
final class ManagerJson {

    /** 未提供执行权检查器时使用的无操作实现。 */
    static final Runnable NO_OP_EXECUTION_GUARD = () -> {
    };

    private ManagerJson() {
    }

    /** 严格读取评分契约中的字符串数组；非字符串、重复值和非数组都会使契约失效。 */
    static List<String> readStrictStringArray(
            JsonNode node,
            List<String> issues,
            String field
    ) {
        if (node == null || !node.isArray()) {
            issues.add(field);
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                issues.add(field);
                continue;
            }
            String value = item.textValue().strip();
            if (value.isBlank() || !values.add(value)) {
                issues.add(field);
            }
        }
        return List.copyOf(values);
    }

    /** 禁止 Manager 偷带 winner、总分、recommendation 等契约外字段。 */
    static void appendUnknownFields(
            JsonNode object,
            Set<String> allowedFields,
            List<String> issues,
            String location
    ) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                issues.add(location + ".unknownField:" + field);
            }
        });
    }

    /** 只接受唯一 JSON 值；多个对象或对象后的第二个 JSON token 会被拒绝。 */
    static JsonNode readStrictJson(ObjectMapper objectMapper, String json) throws java.io.IOException {
        return objectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(json);
    }

    /** 严格读取必需文本字段；数字、布尔值、对象和空白都记为 schema 问题。 */
    static String readStrictText(
            JsonNode object,
            String field,
            List<String> issues
    ) {
        return readText(object, field, issues, false);
    }

    /** 读取文本字段；source 等由后端重绑的字段允许模型按契约输出空字符串。 */
    static String readText(
            JsonNode object,
            String field,
            List<String> issues,
            boolean allowBlank
    ) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual()
                || (!allowBlank && value.textValue().strip().isBlank())) {
            issues.add(field);
            return "";
        }
        return value.textValue().strip();
    }

    /**
     * 截断上游长报告，避免研究经理提示词超过模型上下文预算。
     */
    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...[truncated]";
    }
}
