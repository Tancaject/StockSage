package com.stocksage.repository;

import com.stocksage.model.entity.InvestmentReportVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestmentReportVersionRepository extends JpaRepository<InvestmentReportVersion, Long> {

    Optional<InvestmentReportVersion> findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
            String userId,
            String ticker,
            String dataSnapshotHash,
            String contextHash
    );

    Optional<InvestmentReportVersion> findTopByUserIdAndTickerOrderByReportVersionDesc(
            String userId,
            String ticker
    );

    List<InvestmentReportVersion> findByUserIdOrderByCreatedAtDesc(
            String userId,
            Pageable pageable
    );

    List<InvestmentReportVersion> findByUserIdAndTickerOrderByReportVersionDesc(
            String userId,
            String ticker,
            Pageable pageable
    );
}
