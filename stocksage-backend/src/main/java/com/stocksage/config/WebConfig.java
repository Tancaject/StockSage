package com.stocksage.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import lombok.RequiredArgsConstructor;

/**
 * Web 层配置。
 * 主要处理前后端分离开发时的 CORS 跨域问题。
 * 前端开发服务器跑在 :5173，后端跑在 :8080，浏览器会拦截跨域请求。
 * 生产环境通过 nginx 反向代理，不需要 CORS，但开发阶段必须放行。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminApiInterceptor adminApiInterceptor;

    /**
     * 配置开发环境前端访问后端 API 的跨域规则。
     *
     * <p>仅开放 /api/**，并限定 Vite 默认地址 http://localhost:5173；
     * 生产部署通常由反向代理同源转发，不依赖这里扩大 CORS 范围。</p>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminApiInterceptor)
                .addPathPatterns(
                        "/api/docs/**",
                        "/api/eval/**",
                        "/api/memory/**",
                        "/api/chat/regression/**",
                        "/api/chat/test"
                )
                // 只读检索探针放行：GET /api/docs/search 不改数据、不耗入库额度，
                // 且被前端工作台当作 RAG 健康探针使用（无 admin token）。
                // admin 保护的真正目标是 /api/docs 下的写操作（ingest/edgar/cleanup/regression）。
                .excludePathPatterns("/api/docs/search");
    }
}
