package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.ResearchTaskCheckpoint;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchTaskCheckpointService {

    private final ResearchTaskCheckpointRepository repository;
    private final ObjectMapper objectMapper;

    public record CheckpointState(
            ResearchTask.Stage stageCompleted,
            int debateRoundsCompleted,
            int plannedRounds,
            AnalysisState state
    ) {
    }

    /** 损坏的 checkpoint 当不存在处理，避免恢复路径卡死整项研究任务。 */
    public Optional<CheckpointState> load(Long taskId) {
        return repository.findByTaskId(taskId).flatMap(entity -> {
            try {
                AnalysisState state = objectMapper.readValue(entity.getPayloadJson(), AnalysisState.class);
                return Optional.of(new CheckpointState(
                        entity.getStageCompleted(),
                        safeInt(entity.getDebateRoundsCompleted()),
                        safeInt(entity.getPlannedRounds()),
                        state
                ));
            } catch (Exception e) {
                log.warn("Checkpoint payload corrupted, taskId={}, treating as absent: {}", taskId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @Transactional
    public void saveEvidence(Long taskId, AnalysisState state) {
        upsert(taskId, state, ResearchTask.Stage.DATA_PREFETCH, 0, 0);
    }

    @Transactional
    public void saveDebateRound(Long taskId, AnalysisState state, int roundsCompleted, int plannedRounds) {
        upsert(taskId, state, ResearchTask.Stage.AGENT_DEBATE, roundsCompleted, plannedRounds);
    }

    @Transactional
    public void saveSynthesis(Long taskId, AnalysisState state) {
        ResearchTaskCheckpoint existing = repository.findByTaskId(taskId).orElse(null);
        int rounds = existing == null ? 0 : safeInt(existing.getDebateRoundsCompleted());
        int planned = existing == null ? 0 : safeInt(existing.getPlannedRounds());
        upsert(taskId, state, ResearchTask.Stage.REPORT_SYNTHESIS, rounds, planned);
    }

    @Transactional
    public void deleteForTask(Long taskId) {
        repository.deleteByTaskId(taskId);
    }

    ResearchTaskCheckpoint toEntity(
            Long taskId,
            AnalysisState state,
            ResearchTask.Stage stage,
            int roundsCompleted,
            int plannedRounds
    ) {
        ResearchTaskCheckpoint entity = new ResearchTaskCheckpoint();
        entity.setTaskId(taskId);
        entity.setStageCompleted(stage);
        entity.setDebateRoundsCompleted(roundsCompleted);
        entity.setPlannedRounds(plannedRounds);
        try {
            entity.setPayloadJson(objectMapper.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize research task checkpoint", e);
        }
        return entity;
    }

    private void upsert(
            Long taskId,
            AnalysisState state,
            ResearchTask.Stage stage,
            int roundsCompleted,
            int plannedRounds
    ) {
        try {
            Optional<ResearchTaskCheckpoint> existing = repository.findByTaskId(taskId);
            ResearchTaskCheckpoint entity = existing.orElseGet(ResearchTaskCheckpoint::new);
            entity.setTaskId(taskId);
            entity.setStageCompleted(stage);
            entity.setDebateRoundsCompleted(roundsCompleted);
            entity.setPlannedRounds(plannedRounds);
            entity.setPayloadJson(objectMapper.writeValueAsString(state));
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Checkpoint save failed, taskId={}, continuing without checkpoint: {}", taskId, e.getMessage());
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
