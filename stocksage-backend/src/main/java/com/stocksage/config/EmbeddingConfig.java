package com.stocksage.config;

import com.stocksage.rag.LocalEmbeddingModel;
import com.stocksage.research.ModelInvocationStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * 向量模型配置。
 *
 * 通过 stocksage.embedding.provider 切换：
 * - ollama（默认）：本地 Ollama + bge-m3，消耗本地计算资源
 * - dashscope：使用 DashScope text-embedding-v4（自动配置，无需额外 bean）
 */
@Configuration
public class EmbeddingConfig {

    public EmbeddingConfig(@Value("${stocksage.embedding.provider:ollama}") String provider,
                           ModelTokenBudgetProperties tokenBudget) {
        if (tokenBudget.maxTokens() != null && !"ollama".equals(provider)) {
            throw new IllegalArgumentException("研究 token 预算尚未覆盖 embedding provider=" + provider
                    + "；请使用已接入账本的 ollama，或在接入该供应商后启用预算。");
        }
    }

    /**
     * 创建本地 Ollama 向量模型 Bean。
     *
     * <p>当 {@code stocksage.embedding.provider=ollama} 或未显式配置 provider 时启用。
     * 该 Bean 标记为 Primary，让 RAG 摄取和检索默认走本地 bge-m3；DashScope 模式由自动配置接管，
     * 注意 text-embedding-v4 的批量大小仍需由调用侧控制在项目约定范围内。</p>
     *
     * @param baseUrl Ollama 服务地址
     * @param model Ollama 中已安装的向量模型名
     * @param timeoutMs 单次 HTTP 调用超时，单位毫秒
     * @param dimension 与向量库保持一致、用于校验响应的向量维度
     * @param maxConcurrentCalls 本进程允许同时执行的 Ollama HTTP 请求数
     * @return 对接 Ollama {@code /api/embed} 的向量模型实现
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "stocksage.embedding.provider", havingValue = "ollama", matchIfMissing = true)
    public EmbeddingModel ollamaEmbeddingModel(
            @Value("${stocksage.embedding.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${stocksage.embedding.ollama.model:bge-m3}") String model,
            @Value("${stocksage.embedding.ollama.timeout-ms:120000}") long timeoutMs,
            @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}") int dimension,
            @Value("${stocksage.embedding.ollama.max-concurrent-calls:2}") int maxConcurrentCalls,
            ModelInvocationStore invocations, ModelTokenBudgetProperties tokenBudget) {
        return new LocalEmbeddingModel(baseUrl, model, Duration.ofMillis(timeoutMs), dimension, maxConcurrentCalls,
                invocations, tokenBudget);
    }
}
