package com.stocksage.repository;

import com.stocksage.model.entity.InvestmentReportReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestmentReportReviewRepository extends JpaRepository<InvestmentReportReview, Long> {

    List<InvestmentReportReview> findByReportVersionIdOrderByCreatedAtAscIdAsc(Long reportVersionId);
}
