package com.stocksage.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 异步、开发环境 CORS 与管理路由拦截器配置。
 *
 * <p>开发前端和后端端口不同，因此只对指定前端源开放 {@code /api/**}；
 * 生产环境通常由反向代理同源转发。SSE 响应还使用独立执行器和超时，避免占用 Agent 线程。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 对管理类路由执行独立令牌校验。 */
    private final AdminApiInterceptor adminApiInterceptor;

    /** 专门写出 MVC 异步和 SSE 响应的线程池。 */
    private final AsyncTaskExecutor mvcAsyncTaskExecutor;

    /** MVC 异步请求默认超时，单位毫秒。 */
    private final long mvcAsyncTimeoutMs;

    /**
     * 创建 Web 层配置。
     *
     * @param adminApiInterceptor 管理令牌拦截器
     * @param mvcAsyncTaskExecutor MVC 异步响应专用执行器
     * @param mvcAsyncTimeoutMs 异步请求默认超时，单位毫秒
     */
    public WebConfig(
            AdminApiInterceptor adminApiInterceptor,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor mvcAsyncTaskExecutor,
            @Value("${stocksage.mvc.async.timeout-ms:1800000}") long mvcAsyncTimeoutMs) {
        this.adminApiInterceptor = adminApiInterceptor;
        this.mvcAsyncTaskExecutor = mvcAsyncTaskExecutor;
        this.mvcAsyncTimeoutMs = mvcAsyncTimeoutMs;
    }

    /**
     * 把 SSE/异步响应切换到专用线程池并设置统一超时。
     *
     * @param configurer Spring MVC 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncTaskExecutor);
        configurer.setDefaultTimeout(mvcAsyncTimeoutMs);
    }

    /**
     * 配置开发环境前端访问后端 API 的跨域规则。
     *
     * <p>仅开放 /api/**，并限定 Vite 默认地址 http://localhost:5173；
     * 生产部署通常由反向代理同源转发，不依赖这里扩大 CORS 范围。</p>
     *
     * @param registry MVC 跨域规则注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 将管理令牌拦截器挂到写入、评测和运维路由。
     *
     * @param registry MVC 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminApiInterceptor)
                .addPathPatterns(
                        "/api/docs/**",
                        "/api/eval/**",
                        "/api/admin/**"
                )
                // 只读检索探针放行：GET /api/docs/search 不改数据、不耗入库额度，
                // 且被前端工作台当作 RAG 健康探针使用（无 admin token）。
                // admin 保护的真正目标是 /api/docs 下的写操作（ingest/edgar/cleanup/regression）。
                .excludePathPatterns("/api/docs/search");
    }
}
