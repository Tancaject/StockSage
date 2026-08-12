package com.stocksage.security;

import com.stocksage.model.entity.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security 使用的轻量登录主体。
 *
 * <p>它在认证时从 {@link User} 复制必要字段，之后存入 HTTP Session；业务代码通过
 * {@code RequestIdentity} 读取 {@link #userId}，不会把完整 JPA 实体放入安全上下文。</p>
 */
public class AuthenticatedUser implements UserDetails {

    /** 业务表中稳定的用户标识，用于隔离会话、报告和画像。 */
    private final String userId;

    /** 登录邮箱，同时作为 Spring Security 的 username。 */
    private final String email;

    /** 数据库保存的 BCrypt 哈希，仅供认证比较。 */
    private final String passwordHash;

    /** 前端展示用昵称，不参与权限判断。 */
    private final String nickname;

    /** 当前账号是否允许登录；项目以邮箱验证状态作为开关。 */
    private final boolean enabled;

    /**
     * 从持久化账号创建 Session 登录主体。
     *
     * @param user 已通过邮箱查询得到的用户账号实体
     */
    public AuthenticatedUser(User user) {
        this.userId = user.getUserId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.nickname = user.getNickname();
        this.enabled = user.isEmailVerified();
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    /** 当前版本没有角色分级，已登录用户不附加额外 authority。 */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
