package com.stocksage.research.deep;

import com.stocksage.util.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.research.report.InvestmentReportVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Evidence input history survives checkpoint cleanup and never rewrites earlier snapshots. */
@Service
@RequiredArgsConstructor
public class ResearchEvidenceSnapshotService {
    private final JdbcTemplate jdbc;
    private final ResearchTaskCheckpointRepository checkpoints;
    private final ObjectMapper objectMapper;
    private final InvestmentReportVersionService reportVersions;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String capture(Long runId, int attempt, String leaseToken, AnalysisState state) {
        try {
            if (runId == null || attempt < 1 || leaseToken == null || leaseToken.isBlank() || state == null) {
                throw new IllegalArgumentException("run, attempt, owner and state are required");
            }
            if (checkpoints.lockOwnedRunningTask(runId, leaseToken).isEmpty()
                    || !Integer.valueOf(attempt).equals(jdbc.queryForObject(
                            "SELECT attempts FROM research_tasks WHERE id = ?", Integer.class, runId))) {
                throw new IllegalStateException("ownership or attempt changed");
            }
            reportVersions.prepareHashes(state);
            Map<String, Object> payload = new TreeMap<>();
            payload.put("schemaVersion", 1);
            payload.put("query", state.getQuery());
            payload.put("ticker", state.getPrimaryTicker());
            payload.put("timeSensitivity", state.getTimeSensitivity());
            payload.put("dataSnapshotHash", state.getDataSnapshotHash());
            payload.put("contextHash", state.getContextHash());
            payload.put("evidenceLedger", state.getEvidenceLedger());
            // These are the collected model inputs; further truncation would misrepresent consumed evidence.
            payload.put("fundamentalsReport", state.getFundamentalsReport());
            payload.put("marketReport", state.getMarketReport());
            payload.put("newsReport", state.getNewsReport());
            payload.put("citations", state.getCitations());
            String json = objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(payload);
            String hash = Hashing.sha256Hex(json);
            var existing = jdbc.queryForList("""
                    SELECT id FROM research_evidence_snapshots
                    WHERE run_id = ? AND attempt = ? AND payload_hash = ?
                    """, String.class, runId, attempt, hash);
            String id = existing.isEmpty() ? UUID.randomUUID().toString() : existing.get(0);
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO research_evidence_snapshots
                            (id, run_id, attempt, data_snapshot_hash, context_hash, payload_hash, payload_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, id, runId, attempt, state.getDataSnapshotHash(), state.getContextHash(), hash, json);
            }
            if (jdbc.update("""
                    UPDATE research_tasks SET final_evidence_snapshot_id = ?
                    WHERE id = ? AND attempts = ? AND status = 'RUNNING' AND lease_token = ?
                    """, id, runId, attempt, leaseToken) != 1) {
                throw new IllegalStateException("ownership changed before linking snapshot");
            }
            return id;
        } catch (Exception failure) {
            throw new SnapshotException(runId, "证据快照无法保存", failure);
        }
    }

    /** 任务最终证据快照的原始 JSON（即辩论和报告模型实际消费的证据）；供评测读取，不存在时为空。 */
    public java.util.Optional<String> finalSnapshotJson(Long taskId) {
        return jdbc.queryForList("""
                SELECT s.payload_json FROM research_tasks t
                JOIN research_evidence_snapshots s ON s.id = t.final_evidence_snapshot_id
                WHERE t.id = ?
                """, String.class, taskId).stream().findFirst();
    }

    public static class SnapshotException extends IllegalStateException {
        public SnapshotException(Long runId, String reason, Throwable cause) {
            super("研究任务 " + runId + " 的证据快照操作失败（" + reason
                    + "）；执行已停止，请检查持有者状态与数据库，或另行发起新的研究。", cause);
        }
    }
}
