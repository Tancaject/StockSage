package com.stocksage.repository;

import com.stocksage.model.entity.CompanyRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 公司关系边仓储。
 *
 * <p>按主体 ticker 读取已抽取的关系图谱；重新抽取时先按 ticker 整体清理再写入，
 * 保证同一标的的图谱与最新财报一致（幂等刷新）。</p>
 */
public interface CompanyRelationRepository extends JpaRepository<CompanyRelation, Long> {

    /** 按主体 ticker 读取关系边，置信度高的在前。 */
    List<CompanyRelation> findBySourceTickerOrderByConfidenceDesc(String sourceTicker);

    /** 重新抽取前清理该 ticker 的旧关系边。 */
    @Transactional
    void deleteBySourceTicker(String sourceTicker);
}
