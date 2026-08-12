package com.stocksage.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * 同时兼容 SPA 明文请求头 token 与 Spring XOR 表单 token 的 CSRF 解析器。
 *
 * <p>浏览器客户端通常把 Cookie 中的原始 token 放进请求头；服务端渲染表单仍可提交
 * XOR 编码值。两条路径共存，避免升级 Spring Security 后前端登录请求被误拒绝。</p>
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    /** 解析 SPA 请求头中的原始 token。 */
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();

    /** 生成和解析用于响应压缩攻击防护的 XOR token。 */
    private final XorCsrfTokenRequestAttributeHandler xor = new XorCsrfTokenRequestAttributeHandler();

    /**
     * 将 XOR token 暴露给请求，并立即触发延迟 token 生成以写入 Cookie。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param deferredCsrfToken 延迟创建的 CSRF token
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> deferredCsrfToken
    ) {
        xor.handle(request, response, deferredCsrfToken);
        deferredCsrfToken.get();
    }

    /**
     * 优先接受请求头中的原始 token，否则按 XOR 规则解析表单值。
     *
     * @param request 当前 HTTP 请求
     * @param csrfToken 服务端期望的 CSRF token
     * @return 客户端提交的可比较 token；未提交时可能为 {@code null}
     */
    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        if (StringUtils.hasText(headerValue)) {
            // 新版前端提交原始 token；旧客户端若提交 XOR 值，则继续走兼容解析。
            String plainValue = plain.resolveCsrfTokenValue(request, csrfToken);
            if (csrfToken.getToken().equals(plainValue)) {
                return plainValue;
            }
            String xorValue = xor.resolveCsrfTokenValue(request, csrfToken);
            return StringUtils.hasText(xorValue) ? xorValue : plainValue;
        }
        return xor.resolveCsrfTokenValue(request, csrfToken);
    }
}
