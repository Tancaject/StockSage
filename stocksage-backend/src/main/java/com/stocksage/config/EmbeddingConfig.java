package com.stocksage.config;

import com.stocksage.rag.LocalEmbeddingModel;
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
 * - ollama（默认）：本地 Ollama + bge-m3，零成本
 * - dashscope：使用 DashScope text-embedding-v4（自动配置，无需额外 bean）
 */
@Configuration
public class EmbeddingConfig {

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
     * @param fallbackDimension 服务不可探测时使用的向量维度
     * @return 对接 Ollama {@code /api/embed} 的向量模型实现
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "stocksage.embedding.provider", havingValue = "ollama", matchIfMissing = true)
    public EmbeddingModel ollamaEmbeddingModel(
            @Value("${stocksage.embedding.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${stocksage.embedding.ollama.model:bge-m3}") String model,
            @Value("${stocksage.embedding.ollama.timeout-ms:120000}") long timeoutMs,
            @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}") int fallbackDimension) {
        return new LocalEmbeddingModel(baseUrl, model, Duration.ofMillis(timeoutMs), fallbackDimension);
    }
}
