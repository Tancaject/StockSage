package com.stocksage.repository;

import com.stocksage.model.entity.ResearchTaskCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResearchTaskCheckpointRepository extends JpaRepository<ResearchTaskCheckpoint, Long> {

    Optional<ResearchTaskCheckpoint> findByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);
}
