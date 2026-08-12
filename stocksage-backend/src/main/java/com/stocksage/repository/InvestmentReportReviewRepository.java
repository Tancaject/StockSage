package com.stocksage.repository;

import com.stocksage.model.entity.InvestmentReportReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 投资报告审核历史仓储。
 *
 * <p>审核记录只追加不覆盖；报告当前状态由 {@code InvestmentReportVersionRepository} 管理。</p>
 */
public interface InvestmentReportReviewRepository extends JpaRepository<InvestmentReportReview, Long> {

    /**
     * 按发生顺序读取一个报告版本的完整审核流水。
     *
     * <p>相同创建时间再按主键升序，保证 API 返回顺序稳定。</p>
     *
     * @param reportVersionId 报告版本主键
     * @return 从最早到最新排列的审核记录；没有记录时返回空列表
     */
    List<InvestmentReportReview> findByReportVersionIdOrderByCreatedAtAscIdAsc(Long reportVersionId);
}
