package com.stocksage.identity;

import com.stocksage.security.AuthenticatedUser;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 解析当前请求的已认证用户 ID。
 *
 * <p>刻意 fail-closed：没有已认证主体时抛异常（→401），绝不回退到某个真实账号。
 * 此前的 u_001 兜底会在任何缺失主体的路径上静默以 demo 账号身份读写数据——那正是本轮要根除的硬编码。
 * 所有调用者都位于 Spring Security 的 authenticated 段，正常情况下不会触达抛出分支。</p>
 */
@Component
public class RequestIdentity {

    /**
     * 从 Spring Security 上下文读取当前用户 ID。
     *
     * @return 去除首尾空白后的内部用户 ID
     * @throws AuthenticationCredentialsNotFoundException 请求没有有效登录主体时抛出
     */
    public String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user
                && hasText(user.getUserId())) {
            return user.getUserId().trim();
        }
        throw new AuthenticationCredentialsNotFoundException("No authenticated user in security context");
    }

    /** 判断字符串是否包含非空白字符。 */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
