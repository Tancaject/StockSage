package com.stocksage.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServicePayloads;

/** 识别提供方以正常返回值承载的结构化失败。 */
public final class ToolResultInspector {

    private ToolResultInspector() {
    }

    public static boolean isErrorPayload(Object result, ObjectMapper objectMapper) {
        if (result == null) {
            return false;
        }
        try {
            JsonNode payload = result instanceof CharSequence
                    ? objectMapper.readTree(result.toString())
                    : objectMapper.valueToTree(result);
            return DataServicePayloads.hasTopLevelError(payload);
        } catch (Exception ignored) {
            return false;
        }
    }
}
