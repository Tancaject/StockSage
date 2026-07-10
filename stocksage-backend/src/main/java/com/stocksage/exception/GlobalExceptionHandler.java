package com.stocksage.exception;

import com.stocksage.model.dto.ErrorResponse;
import java.io.IOException;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理器。
 *
 * 所有 Controller 异常统一转为 {@link ErrorResponse} JSON：
 * {@code {"error":true, "status":400, "message":"...", "timestamp":"..."}}
 *
 * 前端通过 {@code response.error === true} 判断失败，message 直接展示。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 Bean Validation 参数校验失败。
     *
     * <p>多个字段错误会拼接成一条消息返回，方便前端直接展示。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        return respond(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 处理业务层主动抛出的非法参数错误。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException ex) {
        return respond(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 处理资源不存在 / 不属于当前用户（多租户下统一为 404，不区分两者、不回显资源 ID）。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /**
     * 处理尚未实现的功能分支。
     */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ErrorResponse> handleNotImplemented(UnsupportedOperationException ex) {
        return respond(HttpStatus.NOT_IMPLEMENTED, "Feature not yet implemented");
    }

    /**
     * 处理带明确 HTTP 状态码的异常。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason();
        return respond(HttpStatus.valueOf(ex.getStatusCode().value()), message);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, "Resource not found");
    }

    /**
     * 处理流式响应写入中断。
     *
     * <p>SSE 流已提交后，客户端断开（关闭页面、刷新、网络中断）会让后续写入抛 {@link IOException}。
     * 此时响应早已提交、Content-Type 已固定为 {@code text/event-stream}，无法再写任何错误体——
     * 若仍交给 {@link #handleGeneral} 返回 {@code ErrorResponse}，会二次抛
     * {@code HttpMessageNotWritableException}。因此这里单独拦截：返回 {@code void}、不写响应体。
     * 客户端断开属于预期内事件，按 DEBUG 记录，不作为服务端错误。</p>
     *
     * <p>该处理器比 {@link #handleGeneral} 更具体，{@link IOException}（含 Spring 的
     * {@code AsyncRequestNotUsableException}、Tomcat 的 {@code ClientAbortException}）会优先命中这里。</p>
     */
    @ExceptionHandler(IOException.class)
    public void handleStreamingIoError(IOException ex) {
        if (isClientDisconnect(ex)) {
            log.debug("Streaming response aborted by client disconnect: {}", ex.getMessage());
        } else {
            log.warn("I/O error while writing response", ex);
        }
    }

    /**
     * 兜底处理未预期异常。
     *
     * <p>详细堆栈只写入服务端日志，响应体避免把内部实现细节泄露给前端。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    /**
     * 构造统一错误响应实体。
     */
    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(
                ErrorResponse.builder()
                        .status(status.value())
                        .message(message)
                        .build());
    }

    /**
     * 判断异常链是否属于客户端断开（broken pipe / connection reset / 连接被中止）。
     *
     * <p>断开消息由操作系统给出、随系统语言变化，因此同时匹配英文与中文 Windows 的典型措辞。</p>
     */
    private static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("connection was aborted")
                        || lower.contains("connection abort")
                        || message.contains("中止")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
