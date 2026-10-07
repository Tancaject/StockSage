package com.stocksage.repository;

import com.stocksage.model.entity.EvolutionFailureCase;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 自进化失败池仓储。 */
public interface EvolutionFailureCaseRepository extends JpaRepository<EvolutionFailureCase, Long> {

    boolean existsBySourceAndTraceId(EvolutionFailureCase.Source source, String traceId);

    boolean existsBySourceAndReportVersionId(EvolutionFailureCase.Source source, Long reportVersionId);

    List<EvolutionFailureCase> findByStatusOrderByCreatedAtDesc(EvolutionFailureCase.Status status, Pageable pageable);

    List<EvolutionFailureCase> findByFailureTypeOrderByCreatedAtDesc(EvolutionFailureCase.FailureType failureType, Pageable pageable);

    List<EvolutionFailureCase> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
