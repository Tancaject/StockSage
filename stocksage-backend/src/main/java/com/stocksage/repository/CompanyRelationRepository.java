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

    /**
     * 按主体 ticker 读取关系边，置信度高的在前。
     *
     * @param sourceTicker 关系发起方证券代码
     * @return 该公司的有向关系边；没有抽取结果时为空列表
     */
    List<CompanyRelation> findBySourceTickerOrderByConfidenceDesc(String sourceTicker);

    /**
     * 删除一个主体 ticker 的全部旧关系边。
     *
     * <p>该方法在事务中执行并产生批量删除副作用；调用方通常随后写入最新抽取结果。</p>
     *
     * @param sourceTicker 要整体刷新的主体证券代码
     */
    @Transactional
    void deleteBySourceTicker(String sourceTicker);
}
