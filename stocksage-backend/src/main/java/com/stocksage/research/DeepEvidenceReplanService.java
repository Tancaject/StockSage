package com.stocksage.research;

import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.model.dto.AnalysisState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * DEEP 证据阶段的补证入口：基础证据通过验收后交给取证循环（ReAct）继续补证。
 *
 * <p>循环自己逐步落盘（PLANNED / REVALIDATED / STOPPED），接管时从同一 checkpoint 续跑。</p>
 */
@Service
public class DeepEvidenceReplanService {

    private final DeepEvidenceLoopService loopService;

    public DeepEvidenceReplanService(DeepEvidenceLoopService loopService) {
        this.loopService = loopService;
    }

    public Map<String, Object> runtimeConfiguration() {
        return Map.of("loop", loopService.runtimeConfiguration());
    }

    /**
     * 运行有界补证；调用方提供真实 ownership 检查和 owner-fenced 严格写入。
     */
    public DeepEvidenceCollector.EvidenceCollection replan(
            Long taskId,
            String userId,
            Long conversationId,
            String traceId,
            DeepEvidenceCollector.EvidenceCollection evidence,
            Runnable ownershipCheck,
            Consumer<AnalysisState> strictCheckpoint
    ) {
        if (evidence == null
                || evidence.harnessDecision().outcome() != HarnessOutcome.PASS
                || evidence.state() == null
                || !evidence.tickerResolved()
                || downstreamWorkStarted(evidence.state())) {
            return evidence;
        }
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(ownershipCheck, "ownershipCheck");
        Objects.requireNonNull(strictCheckpoint, "strictCheckpoint");
        return loopService.enabled()
                ? loopService.run(taskId, userId, conversationId, traceId, evidence, ownershipCheck, strictCheckpoint)
                        .evidence()
                : evidence;
    }

    private boolean downstreamWorkStarted(AnalysisState state) {
        return state.getInvestmentReport() != null
                || state.getManagerAssessment() != null
                || state.getDebateVerdict() != null
                || (state.getDebateTurns() != null && !state.getDebateTurns().isEmpty());
    }
}
