package com.stocksage.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.ErrorResponse;
import com.stocksage.repository.UserAccountRepository;
import com.stocksage.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * 后端登录、Session、CSRF 与 API 授权规则的集中配置。
 *
 * <p>用户接口依赖 Redis Session 保存 SecurityContext；认证失败统一返回 JSON。
 * 管理类路由在过滤器链中允许进入，再由 {@link AdminApiInterceptor} 校验独立管理令牌。</p>
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    /** 用于序列化 401/403 错误响应，保证与业务异常格式一致。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 BCrypt 密码哈希器。
     *
     * @return 注册和登录共用的单向密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 创建按规范化邮箱查询账号的 Spring Security 用户加载器。
     *
     * @param userAccountRepository 用户账号仓库
     * @return 将账号实体包装为 {@link AuthenticatedUser} 的加载函数
     */
    @Bean
    public UserDetailsService userDetailsService(UserAccountRepository userAccountRepository) {
        return username -> userAccountRepository.findByEmail(normalizeEmail(username))
                .map(AuthenticatedUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
    }

    /**
     * 暴露 Spring 自动装配的认证管理器，供登录服务主动发起认证。
     *
     * @param configuration Spring Security 认证配置
     * @return 汇总已注册认证提供器的 AuthenticationManager
     * @throws Exception 无法构建认证管理器时抛出
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * 使用 HTTP Session 持久化登录后的 SecurityContext。
     *
     * @return Session 型安全上下文仓库
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 定义公开路由、登录路由和需登录 API 的过滤器链。
     *
     * @param http Spring Security 链式配置入口
     * @param securityContextRepository 保存登录状态的 Session 仓库
     * @return 构建完成的安全过滤器链
     * @throws Exception 安全规则无法构建时抛出
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository
    ) throws Exception {
        // SPA 从可读 Cookie 取 CSRF token，再通过请求头提交；部分机器接口另有管理令牌边界。
        http
                .cors(cors -> { })
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(
                                "/api/docs/**",
                                "/api/eval/**",
                                "/api/admin/**",
                                "/api/memory/**",
                                "/api/chat/regression/**",
                                "/api/chat/test"
                        ))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpStatus.FORBIDDEN, "Access denied")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/csrf").permitAll()
                        .requestMatchers(
                                "/api/docs/**",
                                "/api/eval/**",
                                "/api/admin/**",
                                "/api/memory/**",
                                "/api/chat/regression/**",
                                "/api/chat/test"
                        ).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll());

        return http.build();
    }

    /** 将登录邮箱规范化为不区分大小写的仓库查询键。 */
    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /**
     * 将安全过滤器异常写成前端可直接处理的统一 JSON。
     *
     * @param response 当前 HTTP 响应
     * @param status 要返回的 HTTP 状态
     * @param message 面向客户端的错误信息
     * @throws IOException 序列化或写出失败时抛出
     */
    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.builder()
                .status(status.value())
                .message(message)
                .build());
    }
}
