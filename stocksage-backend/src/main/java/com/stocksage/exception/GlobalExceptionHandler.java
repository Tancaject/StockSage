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
     *
     * @param ex Spring MVC 收集的字段校验异常
     * @return HTTP 400 及合并后的字段错误
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
     *
     * @param ex 参数或业务前置条件异常
     * @return HTTP 400 错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 将重复邮箱注册映射为资源冲突。
     *
     * @param ex 重复邮箱异常
     * @return HTTP 409 错误响应
     */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException ex) {
        return respond(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 处理资源不存在 / 不属于当前用户（多租户下统一为 404，不区分两者、不回显资源 ID）。
     *
     * @param ex 资源缺失或归属不匹配异常
     * @return HTTP 404 错误响应
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * 将认证阶段异常统一为不泄露原因的 401。
     *
     * @param ex Spring Security 认证异常
     * @return HTTP 401 错误响应
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /**
     * 处理尚未实现的功能分支。
     *
     * @param ex 未支持操作异常
     * @return HTTP 501 错误响应
     */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ErrorResponse> handleNotImplemented(UnsupportedOperationException ex) {
        return respond(HttpStatus.NOT_IMPLEMENTED, "Feature not yet implemented");
    }

    /**
     * 处理带明确 HTTP 状态码的异常。
     *
     * @param ex 携带状态码和可选原因的异常
     * @return 保留原状态码的错误响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason();
        return respond(HttpStatus.valueOf(ex.getStatusCode().value()), message);
    }

    /**
     * 将未匹配到静态资源或路由的请求转为统一 404 JSON。
     *
     * @param ex Spring MVC 资源缺失异常
     * @return HTTP 404 错误响应
     */
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
     *
     * @param ex SSE 写出或普通响应写出期间的 I/O 异常
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
     *
     * @param ex 未被更具体处理器捕获的异常
     * @return 包装的容量拒绝返回 HTTP 503；其余异常返回 HTTP 500 通用错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        ResearchCapacityExceededException capacity = ResearchCapacityExceededException.find(ex);
        if (capacity != null) return handleCapacity(capacity);
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    @ExceptionHandler(ResearchCapacityExceededException.class)
    public ResponseEntity<ErrorResponse> handleCapacity(ResearchCapacityExceededException ex) {
        log.warn("Request execution not admitted: {}", ex.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /**
     * 构造统一错误响应实体。
     *
     * @param status HTTP 状态
     * @param message 可安全展示给客户端的错误说明
     * @return 带 {@link ErrorResponse} 主体的响应实体
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
     *
     * @param ex 待检查的顶层异常
     * @return 异常链中是否出现已知客户端断开信号
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
