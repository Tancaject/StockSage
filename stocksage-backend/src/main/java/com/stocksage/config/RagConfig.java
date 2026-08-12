package com.stocksage.config;

import org.springframework.context.annotation.Configuration;

/**
 * RAG 架构说明占位配置。
 *
 * <p>实际检索由 {@link com.stocksage.rag.RagService} 完成，并把结果作为参考性 SystemMessage
 * 交给对话链路，而不使用 RetrievalAugmentationAdvisor。这样知识库未命中时，模型仍可综合
 * 已有工具观察回答；Advisor 的“上下文不足即拒答”规则不会遮蔽有效的实时数据。</p>
 */
@Configuration
public class RagConfig {
}
