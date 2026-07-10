package com.stocksage.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * RAG 文档模型 —— 知识库中的一个检索单元。
 *
 * 与 Naive RAG 只存纯文本不同，每个文档块携带丰富的元数据，
 * 支持在检索阶段做精确过滤（如"只搜茅台相关的研报"）。
 *
 * 存储位置：
 * - 正文 + 向量 → Milvus（用于语义搜索）
 * - 元数据 → MySQL vector_documents 表（用于过滤和溯源）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocument {

    /** Milvus 中的唯一 ID */
    private String id;

    /** 文档块的文本内容 */
    private String content;

    // ===== 元数据 =====

    /** 来源文件名，如 "贵州茅台2025Q3研报.pdf" */
    private String sourceName;

    /**
     * 文档类型，决定分块策略和检索权重：
     * - RESEARCH_REPORT: 研报（按章节分块，权重高）
     * - NEWS:            新闻（按段落分块，时效性强）
     * - GLOSSARY:        术语解释（整条为一个切片，精确匹配）
     * - ARTICLE:         财经科普文章（按段落分块）
     */
    private String docType;

    /** 关联的股票代码列表，如 ["sh.600519", "sz.000858"] */
    private List<String> stockCodes;

    /** 关联的行业/板块，如 "白酒"、"新能源" */
    private String sector;

    /** 文档发布日期，格式 yyyy-MM-dd，用于时效性过滤 */
    private String publishDate;

    /** 在原文中的块索引（第几块），用于上下文关联 */
    private int chunkIndex;

    /** 原文总块数，用于判断是否需要拉取相邻块 */
    private int totalChunks;

    /** 检索时的相似度得分（仅检索结果中有值） */
    private double score;

    /** 扩展元数据，灵活存储其他信息 */
    private Map<String, String> extra;
}
