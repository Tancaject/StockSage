package com.stocksage.config;

import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置。
 *
 * 架构设计（面试重点）：
 * - 检索由 RagService 统一执行（Milvus 向量最近邻搜索）
 * - 检索结果作为"参考性"SystemMessage 注入 Prompt，而非使用 RetrievalAugmentationAdvisor
 * - 这样做的原因：Advisor 的 ContextualQueryAugmenter 会注入"上下文不够则拒绝回答"的指令，
 *   导致模型在知识库无直接匹配时拒绝回答，即使工具观察（webSearch/searchNews）已有充足信息
 * - 当前方案让模型能综合 RAG 上下文 + 工具观察 + 自身知识灵活回答
 */
@Configuration
public class RagConfig {
}
