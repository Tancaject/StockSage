package com.stocksage.controller;

import com.stocksage.model.entity.User;
import com.stocksage.security.AuthenticatedUser;
import com.stocksage.identity.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 注册、登录、登出与当前会话查询接口。
 *
 * <p>登录成功后把 {@link Authentication} 保存到 Session 型 SecurityContext；后续业务接口
 * 只信任该会话主体。密码校验由 Spring Security 完成，本控制器不会直接读取密码哈希。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 负责账号注册和邮箱规范化。 */
    private final AuthService authService;

    /** 调用已配置的 UserDetailsService 与密码编码器完成认证。 */
    private final AuthenticationManager authenticationManager;

    /** 将认证结果持久化到 Redis 支持的 HTTP Session。 */
    private final SecurityContextRepository securityContextRepository;

    /**
     * 创建新账号并返回可公开的用户信息。
     *
     * @param request 已校验邮箱和密码长度的注册请求
     * @return 新建账号的 ID、邮箱与昵称
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthUserResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request.email(), request.password());
        return AuthUserResponse.from(user);
    }

    /**
     * 校验凭据并建立登录 Session。
     *
     * @param request 邮箱和明文密码，仅用于本次认证
     * @param servletRequest 当前请求，用于轮换或创建 Session
     * @param servletResponse 当前响应，用于保存安全上下文
     * @return 已认证用户的公开信息
     * @throws ResponseStatusException 凭据无效时以 401 返回统一错误
     */
    @PostMapping("/login")
    public AuthUserResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            authService.normalizeEmail(request.email()),
                            request.password()
                    ));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            HttpSession existingSession = servletRequest.getSession(false);
            if (existingSession != null) {
                // 登录时轮换 Session ID，保留会话数据同时防止 Session Fixation。
                servletRequest.changeSessionId();
            }
            // 显式保存上下文，保证无表单登录流程也能在后续请求恢复认证主体。
            securityContextRepository.saveContext(context, servletRequest, servletResponse);
            return AuthUserResponse.from((AuthenticatedUser) authentication.getPrincipal());
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
    }

    /**
     * 清除安全上下文并使当前 Session 失效。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param authentication 当前认证主体；未登录时可能为空
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    /**
     * 返回当前 Session 对应的用户信息。
     *
     * @param principal Spring Security 注入的登录主体
     * @return 当前用户的公开信息
     */
    @GetMapping("/me")
    public AuthUserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BadCredentialsException("Authentication required");
        }
        return AuthUserResponse.from(principal);
    }

    /**
     * 触发 CSRF token 创建并把前端提交所需名称和值返回。
     *
     * @param token Spring Security 为当前 Session 生成的 token
     * @return CSRF 请求头名、参数名和 token 值
     */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    /**
     * 注册接口载荷。
     *
     * @param email 作为登录名的有效邮箱
     * @param password 至少 8 个字符的明文密码，仅在请求生命周期内存在
     */
    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, message = "must be at least 8 characters") String password
    ) {
    }

    /**
     * 登录接口载荷。
     *
     * @param email 登录邮箱
     * @param password 待认证的明文密码
     */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    /**
     * 客户端可见的登录用户摘要，不包含密码哈希和验证内部字段。
     *
     * @param userId 业务用户标识
     * @param email 登录邮箱
     * @param nickname 展示昵称
     */
    public record AuthUserResponse(String userId, String email, String nickname) {

        /** 从注册后 JPA 实体创建响应。 */
        static AuthUserResponse from(User user) {
            return new AuthUserResponse(user.getUserId(), user.getEmail(), user.getNickname());
        }

        /** 从 Session 登录主体创建响应。 */
        static AuthUserResponse from(AuthenticatedUser user) {
            return new AuthUserResponse(user.getUserId(), user.getEmail(), user.getNickname());
        }
    }

    /**
     * SPA 获取 CSRF 提交约定时使用的响应。
     *
     * @param headerName 推荐的请求头名称
     * @param parameterName 表单参数名称
     * @param token 当前 Session 的 CSRF token
     */
    public record CsrfResponse(String headerName, String parameterName, String token) {
    }
}
