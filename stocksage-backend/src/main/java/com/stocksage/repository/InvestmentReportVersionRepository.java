package com.stocksage.repository;

import com.stocksage.model.entity.InvestmentReportVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvestmentReportVersionRepository extends JpaRepository<InvestmentReportVersion, Long> {

    Optional<InvestmentReportVersion> findByIdAndUserId(Long id, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select report
            from InvestmentReportVersion report
            where report.id = :id and report.userId = :userId
            """)
    Optional<InvestmentReportVersion> findByIdAndUserIdForUpdate(
            @Param("id") Long id,
            @Param("userId") String userId
    );

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
