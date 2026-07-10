package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公司关系图谱的一条有向边。
 *
 * <p>由 {@code RelationExtractionService} 从已入库的 SEC 财报章节（Item 1 业务、
 * Item 1A 风险因素）抽取：主体公司与某个被点名实体之间的 竞争/客户/供应/合作 关系。
 * 每条边都携带来源财报的逐字证据片段与出处元数据，供前端下钻核验——这正是
 * StockSage "结论可追溯到证据" 这条主线在关系图谱上的复用。</p>
 */
@Data
@Entity
@Table(name = "company_relations")
public class CompanyRelation {

    /** 关系型数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 主体公司 ticker（关系发起方），如 NVDA。 */
    @Column(name = "source_ticker", length = 32, nullable = false)
    private String sourceTicker;

    /** 主体公司名称。 */
    @Column(name = "source_name", length = 256)
    private String sourceName;

    /** 关系对端实体名称（财报中被点名的公司），如 Advanced Micro Devices。 */
    @Column(name = "target_name", length = 256, nullable = false)
    private String targetName;

    /** 关系对端 ticker；v1 不做名称→代码解析，解析不到时为 null。 */
    @Column(name = "target_ticker", length = 32)
    private String targetTicker;

    /** 关系类型：COMPETITOR | CUSTOMER | SUPPLIER | PARTNER。 */
    @Column(name = "relation_type", length = 32, nullable = false)
    private String relationType;

    /** 模型给出的置信度 0~1，反映该关系在原文中的明确程度。 */
    @Column(nullable = false)
    private Double confidence;

    /** 支撑该关系的财报逐字片段（必须能在来源文本中原样命中）。 */
    @Column(name = "evidence_snippet", columnDefinition = "TEXT")
    private String evidenceSnippet;

    /** 证据来源切片在向量库镜像表中的 doc_id，供前端回溯原文。 */
    @Column(name = "evidence_doc_id", length = 128)
    private String evidenceDocId;

    /** 证据来源财报的 SEC accession number。 */
    @Column(name = "evidence_accession", length = 64)
    private String evidenceAccession;

    /** 证据来源章节，如 "Item 1. Business"。 */
    @Column(name = "evidence_section", length = 128)
    private String evidenceSection;

    /** 证据来源财报的提交日期。 */
    @Column(name = "filing_date", length = 16)
    private String filingDate;

    /** 抽取所用模型名，便于回溯与质量追踪。 */
    @Column(name = "extraction_model", length = 64)
    private String extractionModel;

    /** 该边写入时间。 */
    @Column(name = "extracted_at")
    private LocalDateTime extractedAt;

    /**
     * 新增记录前补齐抽取时间，用于排查抽取批次与刷新图谱。
     */
    @PrePersist
    protected void onCreate() {
        extractedAt = LocalDateTime.now();
    }
}
