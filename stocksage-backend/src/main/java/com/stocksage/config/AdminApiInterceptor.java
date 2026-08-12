package com.stocksage.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 管理接口的令牌校验拦截器。
 *
 * <p>{@link WebConfig} 只把它挂到管理类路由；请求必须携带配置的请求头和令牌。
 * 校验失败时本类直接写入 JSON 错误响应，后续 Controller 不会执行。</p>
 */
@Component
public class AdminApiInterceptor implements HandlerInterceptor {

    /** 管理接口总开关；关闭时统一返回 503。 */
    @Value("${stocksage.admin.enabled:true}")
    private boolean enabled;

    /** 客户端传递管理令牌时使用的请求头名称。 */
    @Value("${stocksage.admin.header-name:X-StockSage-Admin-Token}")
    private String headerName;

    /** 服务端期望的管理令牌；空值会被视为未完成安全配置。 */
    @Value("${stocksage.admin.token:}")
    private String adminToken;

    /**
     * 在管理 Controller 执行前验证接口开关、配置和令牌。
     *
     * @param request 当前 HTTP 请求
     * @param response 校验失败时写入错误结果的 HTTP 响应
     * @param handler Spring 已解析出的目标处理器
     * @return {@code true} 表示允许继续执行；{@code false} 表示响应已由本类完成
     * @throws IOException 写入错误响应失败时抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!enabled) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Admin API is disabled");
            return false;
        }
        if (headerName == null || headerName.isBlank() || adminToken == null || adminToken.isBlank()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Admin token is not configured");
            return false;
        }
        String providedToken = request.getHeader(headerName);
        // MessageDigest.isEqual 避免普通字符串比较产生与匹配位置相关的时间差。
        if (providedToken == null || providedToken.isBlank()
                || !constantTimeEquals(adminToken.trim(), providedToken.trim())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Admin token required");
            return false;
        }
        return true;
    }

    /**
     * 使用恒定时间算法比较 UTF-8 令牌。
     *
     * @param expected 服务端配置的令牌
     * @param actual 请求携带的令牌
     * @return 两个令牌的字节内容是否一致
     */
    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    /**
     * 写入拦截器统一的 JSON 错误结构。
     *
     * @param response 待写入的 HTTP 响应
     * @param status HTTP 状态码
     * @param message 面向调用方的错误说明
     * @throws IOException 响应输出流不可写时抛出
     */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"timestamp":"%s","status":%d,"message":"%s"}
                """.formatted(LocalDateTime.now(), status, message).trim());
    }
}
