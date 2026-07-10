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

@Component
public class AdminApiInterceptor implements HandlerInterceptor {

    @Value("${stocksage.admin.enabled:true}")
    private boolean enabled;

    @Value("${stocksage.admin.header-name:X-StockSage-Admin-Token}")
    private String headerName;

    @Value("${stocksage.admin.token:local-admin}")
    private String adminToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (adminToken == null || adminToken.isBlank()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Admin token is not configured");
            return false;
        }
        String providedToken = request.getHeader(headerName);
        if (providedToken == null || providedToken.isBlank()
                || !constantTimeEquals(adminToken.trim(), providedToken.trim())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Admin token required");
            return false;
        }
        return true;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"timestamp":"%s","status":%d,"message":"%s"}
                """.formatted(LocalDateTime.now(), status, message).trim());
    }
}
