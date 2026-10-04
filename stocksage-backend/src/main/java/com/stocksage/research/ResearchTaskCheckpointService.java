package com.stocksage.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * 持久化长时研究任务的可恢复 {@link AnalysisState} 检查点。
 *
 * <p>{@link DeepResearchPipeline} 在证据、每轮辩论和综合阶段调用本服务；生产写入必须携带
 * 当前 leaseToken，并通过数据库 owner fence。普通证据/报告检查点写入失败时 fail-open；
 * Harness 决策和辩论续停快照则 fail-closed，防止未持久化决定后继续执行副作用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchTaskCheckpointService {

    // 存储格式独立于 Harness/决策策略版本；无外层版本的历史 AnalysisState 按 v0 读取。
    private static final int SCHEMA_VERSION = 1;

    /** 读写每个 taskId 唯一的检查点，并执行 owner-fence 加锁查询。 */
    private final ResearchTaskCheckpointRepository repository;
    /** 在 AnalysisState 与 JSON 载荷间转换。 */
    private final ObjectMapper objectMapper;

    /**
     * 恢复管线所需的阶段、已完成轮次和完整状态快照。
     *
     * @param stageCompleted 已持久化完成的最高阶段
     * @param debateRoundsCompleted 已完成辩论轮数
     * @param plannedRounds Research Manager 当前授权到的轮次上界
     * @param state 可继续执行的分析状态
     */
    public record CheckpointState(
            ResearchTask.Stage stageCompleted,
            int debateRoundsCompleted,
            int plannedRounds,
            AnalysisState state
    ) {
    }

    /**
     * 读取历史裸状态或当前版本快照；未知格式必须停止恢复，不能丢弃已持久化的执行决定。
     *
     * @param taskId 研究任务 ID
     * @return 可恢复状态；仅无记录时为空
     * @throws CheckpointFormatException 载荷损坏或当前 worker 不支持其格式
     */
    public Optional<CheckpointState> load(Long taskId) {
        return repository.findByTaskId(taskId).map(entity -> {
            try {
                JsonNode payload = objectMapper.readTree(entity.getPayloadJson());
                if (payload == null || !payload.isObject()) {
                    throw new CheckpointFormatException(taskId, "payload must be an object");
                }
                JsonNode stateNode = payload;
                if (payload.has("schemaVersion")) {
                    JsonNode version = payload.get("schemaVersion");
                    if (!version.isIntegralNumber() || !version.canConvertToInt()
                            || version.intValue() != SCHEMA_VERSION) {
                        throw new CheckpointFormatException(taskId, "unsupported schemaVersion=" + version);
                    }
                    stateNode = payload.get("state");
                    if (stateNode == null || !stateNode.isObject()) {
                        throw new CheckpointFormatException(taskId, "v1 state must be an object");
                    }
                } else if (payload.has("state")) {
                    throw new CheckpointFormatException(taskId, "envelope is missing schemaVersion");
                }
                AnalysisState state = objectMapper.treeToValue(stateNode, AnalysisState.class);
                return new CheckpointState(
                        entity.getStageCompleted(),
                        safeInt(entity.getDebateRoundsCompleted()),
                        safeInt(entity.getPlannedRounds()),
                        state
                );
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new CheckpointFormatException(taskId, "invalid checkpoint JSON", e);
            }
        });
    }

    /**
     * 保存证据收集完成状态。
     *
     * @param taskId 任务 ID
     * @param leaseToken 当前 owner token
     * @param state 已包含证据的分析状态
     */
    @Transactional
    public void saveEvidence(Long taskId, String leaseToken, AnalysisState state) {
        requireOwnership(taskId, leaseToken);
        upsert(taskId, state, ResearchTask.Stage.DATA_PREFETCH, 0, 0);
    }

    /**
     * 在执行恢复动作前持久化 Harness 决策。
     *
     * <p>不同于普通可恢复检查点，本方法 fail-closed：owner fence 丢失、序列化失败或数据库失败
     * 都会阻止后续恢复副作用。</p>
     *
     * @param taskId 任务 ID
     * @param leaseToken 当前 owner token
     * @param state 已写入 Harness 决策的状态
     */
    @Transactional
    public void saveHarnessSnapshot(Long taskId, String leaseToken, AnalysisState state) {
        requireOwnership(taskId, leaseToken);
        upsertStrict(taskId, state, ResearchTask.Stage.DATA_PREFETCH, 0, 0, false);
    }

    /** 在调用补证能力前后严格保存 exact plan 和 effect key。 */
    @Transactional
    public void saveEvidenceReplan(Long taskId, String leaseToken, AnalysisState state) {
        requireOwnership(taskId, leaseToken);
        upsertStrict(taskId, state, ResearchTask.Stage.DATA_PREFETCH, 0, 0, false);
    }

    /**
     * 严格保存一轮 Agent 辩论及其续停决策；失败时不得开始下一轮。
     *
     * @param taskId 任务 ID
     * @param leaseToken 当前 owner token
     * @param state 当前分析状态
     * @param roundsCompleted 已完成轮数
     * @param plannedRounds 当前授权上界；STOP 等于已完成轮次，CONTINUE 等于已完成轮次加一
     */
    @Transactional
    public void saveDebateRound(
            Long taskId,
            String leaseToken,
            AnalysisState state,
            int roundsCompleted,
            int plannedRounds
    ) {
        if (roundsCompleted < 1
                || (plannedRounds != roundsCompleted && plannedRounds != roundsCompleted + 1)) {
            throw new IllegalArgumentException(
                    "Debate checkpoint must encode STOP=N or CONTINUE=N+1");
        }
        requireOwnership(taskId, leaseToken);
        upsertStrict(
                taskId,
                state,
                ResearchTask.Stage.AGENT_DEBATE,
                roundsCompleted,
                plannedRounds,
                true
        );
    }

    /**
     * 保存报告综合完成状态，并保留之前的辩论轮次计数。
     *
     * @param taskId 任务 ID
     * @param leaseToken 当前 owner token
     * @param state 已包含结构化报告的状态
     */
    @Transactional
    public void saveSynthesis(Long taskId, String leaseToken, AnalysisState state) {
        requireOwnership(taskId, leaseToken);
        ResearchTaskCheckpoint existing = repository.findByTaskId(taskId).orElse(null);
        int rounds = existing == null ? 0 : safeInt(existing.getDebateRoundsCompleted());
        int planned = existing == null ? 0 : safeInt(existing.getPlannedRounds());
        upsert(taskId, state, ResearchTask.Stage.REPORT_SYNTHESIS, rounds, planned);
    }

    /**
     * 仅由当前 owner 删除未完成任务检查点。
     *
     * @param taskId 任务 ID
     * @param leaseToken 当前 owner token
     */
    @Transactional
    public void deleteForTask(Long taskId, String leaseToken) {
        requireOwnership(taskId, leaseToken);
        repository.deleteByTaskId(taskId);
    }

    /**
     * 删除成功任务的检查点；先锁定 SUCCEEDED 任务，拒绝提前清理。
     *
     * @param taskId 已完成任务 ID
     */
    @Transactional
    public void deleteForCompletedTask(Long taskId) {
        if (repository.lockSucceededTask(taskId).isEmpty()) {
            throw new CheckpointCleanupRejectedException(taskId);
        }
        repository.deleteByTaskId(taskId);
    }

    /**
     * 将分析状态序列化为新的检查点实体，主要供同包测试验证映射。
     *
     * @param taskId 任务 ID
     * @param state 当前分析状态
     * @param stage 已完成阶段
     * @param roundsCompleted 已完成辩论轮数
     * @param plannedRounds 当前授权到的轮次上界
     * @return 尚未持久化的检查点实体
     */
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
            entity.setPayloadJson(serialize(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize research task checkpoint", e);
        }
        return entity;
    }

    /**
     * 尽力更新可恢复检查点；拒绝阶段/轮次倒退，失败仅记录日志。
     */
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
            if (isRegression(entity, stage, roundsCompleted)) {
                log.debug(
                        "Ignoring stale checkpoint update, taskId={}, currentStage={}, incomingStage={}, "
                                + "currentRounds={}, incomingRounds={}",
                        taskId,
                        entity.getStageCompleted(),
                        stage,
                        safeInt(entity.getDebateRoundsCompleted()),
                        roundsCompleted
                );
                return;
            }
            entity.setTaskId(taskId);
            entity.setStageCompleted(stage);
            entity.setDebateRoundsCompleted(Math.max(
                    safeInt(entity.getDebateRoundsCompleted()), roundsCompleted));
            entity.setPlannedRounds(Math.max(safeInt(entity.getPlannedRounds()), plannedRounds));
            entity.setPayloadJson(serialize(state));
            // 调用仓储覆盖同一 taskId 的最新可恢复状态；唯一性由数据库约束保证。
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Checkpoint save failed, taskId={}, continuing without checkpoint: {}", taskId, e.getMessage());
        }
    }

    /** 严格持久化副作用计划；任何失败向上传播以阻止未落库的恢复或补证动作。 */
    private void upsertStrict(
            Long taskId,
            AnalysisState state,
            ResearchTask.Stage stage,
            int roundsCompleted,
            int plannedRounds,
            boolean replaceDebateProgress
    ) {
        Optional<ResearchTaskCheckpoint> existing = repository.findByTaskId(taskId);
        ResearchTaskCheckpoint entity = existing.orElseGet(ResearchTaskCheckpoint::new);
        if (replaceDebateProgress && isRegression(entity, stage, roundsCompleted)) {
            throw new IllegalStateException("Refusing stale debate checkpoint update");
        }
        ResearchTask.Stage effectiveStage = entity.getStageCompleted() != null
                && entity.getStageCompleted().ordinal() > stage.ordinal()
                ? entity.getStageCompleted()
                : stage;
        entity.setTaskId(taskId);
        entity.setStageCompleted(effectiveStage);
        entity.setDebateRoundsCompleted(replaceDebateProgress
                ? roundsCompleted
                : Math.max(safeInt(entity.getDebateRoundsCompleted()), roundsCompleted));
        entity.setPlannedRounds(replaceDebateProgress
                ? plannedRounds
                : Math.max(safeInt(entity.getPlannedRounds()), plannedRounds));
        try {
            entity.setPayloadJson(serialize(state));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize strict research checkpoint", error);
        }
        // 立即 flush，确保恢复动作开始前数据库已经确认该决策快照。
        repository.saveAndFlush(entity);
    }

    private String serialize(AnalysisState state) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new CheckpointPayload(SCHEMA_VERSION, state));
    }

    private record CheckpointPayload(int schemaVersion, AnalysisState state) {
    }

    /** 通过数据库行锁验证任务仍为 RUNNING 且 leaseToken 属于当前 worker。 */
    private void requireOwnership(Long taskId, String leaseToken) {
        if (leaseToken == null || leaseToken.isBlank()
                || repository.lockOwnedRunningTask(taskId, leaseToken).isEmpty()) {
            throw new OwnershipLostException(taskId);
        }
    }

    /** 判断传入检查点是否会让已持久化阶段或辩论轮次倒退。 */
    private boolean isRegression(
            ResearchTaskCheckpoint existing,
            ResearchTask.Stage incomingStage,
            int incomingRounds
    ) {
        ResearchTask.Stage currentStage = existing.getStageCompleted();
        if (currentStage == null) {
            return false;
        }
        if (incomingStage.ordinal() < currentStage.ordinal()) {
            return true;
        }
        return incomingStage == ResearchTask.Stage.AGENT_DEBATE
                && currentStage == ResearchTask.Stage.AGENT_DEBATE
                && incomingRounds < safeInt(existing.getDebateRoundsCompleted());
    }

    /** 将可空计数转换为零起始整数。 */
    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** 格式失败不能进入普通模型故障的 fallback，也不能以空断点重放研究。 */
    public static final class CheckpointFormatException extends IllegalStateException {

        CheckpointFormatException(Long taskId, String detail) {
            this(taskId, detail, null);
        }

        CheckpointFormatException(Long taskId, String detail, Throwable cause) {
            super("研究任务 " + taskId + " 的 Checkpoint 格式无法恢复（" + detail
                    + "）；原快照已保留，请使用兼容版本检查快照，或另行发起新的研究。", cause);
        }
    }

    /** owner fence 校验失败，调用方必须停止当前任务副作用。 */
    public static final class OwnershipLostException extends IllegalStateException {

        /** @param taskId 已丢失 checkpoint 写权限的任务 ID */
        public OwnershipLostException(Long taskId) {
            super("Research task checkpoint ownership lost, taskId=" + taskId);
        }
    }

    /** 非 SUCCEEDED 任务尝试删除检查点时抛出。 */
    public static final class CheckpointCleanupRejectedException extends IllegalStateException {

        /** @param taskId 尚未成功却请求清理 checkpoint 的任务 ID */
        public CheckpointCleanupRejectedException(Long taskId) {
            super("Research task checkpoint cleanup requires SUCCEEDED status, taskId=" + taskId);
        }
    }
}
