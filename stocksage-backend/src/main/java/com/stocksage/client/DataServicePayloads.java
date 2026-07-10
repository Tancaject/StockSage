package com.stocksage.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 数据服务响应体的错误契约单点判定。
 *
 * <p>此前"是否失败"有两套判定：DataServiceClient 用子串包含、ToolResultCache 用正则，
 * 且都会把正文里恰好出现的 {@code "error":} 字样误判为失败。这里统一为解析 JSON 后
 * 只看<b>顶层</b> {@code error} 字段——Java 端兜底错误与 Python 服务的失败响应都写在顶层。</p>
 */
public final class DataServicePayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DataServicePayloads() {
    }

    /**
     * 判断响应体是否代表上游失败。
     *
     * <p>失败信号：顶层 {@code error} 字段为 {@code true} 或非空字符串；
     * 空响应与无法解析为 JSON 的响应也按失败处理（不缓存、计入熔断统计）。
     * 顶层无 {@code error} 字段的对象、以及数组等其他合法 JSON 视为成功。</p>
     */
    public static boolean isFailure(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!root.isObject()) {
                return false;
            }
            JsonNode error = root.get("error");
            if (error == null || error.isNull()) {
                return false;
            }
            if (error.isBoolean()) {
                return error.booleanValue();
            }
            if (error.isTextual()) {
                return !error.asText().isBlank();
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}
