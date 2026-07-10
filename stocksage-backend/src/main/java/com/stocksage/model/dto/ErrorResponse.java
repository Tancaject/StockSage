package com.stocksage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 统一的 HTTP 错误响应格式。
 *
 * 前端通过 error=true 判断是否为错误响应，message 展示给用户。
 */
@Data
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /** 固定为 true，方便前端统一识别错误响应。 */
    @Builder.Default
    private final boolean error = true;

    /** HTTP 状态码，例如 400、404 或 500。 */
    private final int status;

    /** 可展示给用户或用于调试的错误摘要。 */
    private final String message;

    /** 错误发生时间，使用 ISO-8601 字符串便于前端直接展示和日志检索。 */
    @Builder.Default
    private final String timestamp = Instant.now().toString();
}
