package com.stocksage.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.model.entity.AgentTrace;
import com.stocksage.model.entity.EvolutionFailureCase;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.EvolutionFailureCaseRepository;
import com.stocksage.repository.MessageRepository;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 自进化失败池：收集可能的回答失败，供人工分诊后交给离线反思流程。
 *
 * <p>只有分诊为 METHOD 的条目才会被 evals 导出为学习素材和回归题；入池本身不改变任何线上行为。</p>
 */
@Service
@RequiredArgsConstructor
public class EvolutionFailurePool {

    /** 普通 FUNDAMENTALS 回答的这些结果会自动入池。 */
    private static final Set<String> FAILED_OUTCOMES = Set.of("BLOCKED", "DEGRADED", "FAILED");

    private final EvolutionFailureCaseRepository repository;
    private final TraceService traceService;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    /** 用户标记自己的某次回答有问题；同一 Trace 重复反馈只保留第一次。 */
    @Transactional
    public void recordUserFeedback(String userId, String traceId, String note) {
        AgentTrace trace = traceService.getTraceForUser(traceId, userId);
        if (repository.existsBySourceAndTraceId(EvolutionFailureCase.Source.USER_FEEDBACK, traceId)) return;
        save(fromTrace(trace, EvolutionFailureCase.Source.USER_FEEDBACK, note));
    }

    /** 普通回答结束后调用；只记录 FUNDAMENTALS 路由的未完成结果。 */
    @Transactional
    public void recordOrdinaryOutcome(String traceId, String taskOutcome) {
        if (taskOutcome == null || !FAILED_OUTCOMES.contains(taskOutcome)
                || repository.existsBySourceAndTraceId(EvolutionFailureCase.Source.ORDINARY_OUTCOME, traceId)) return;
        EvolutionFailureCase item = fromTrace(traceService.getTrace(traceId), EvolutionFailureCase.Source.ORDINARY_OUTCOME, null);
        if (!"FUNDAMENTALS".equals(item.getRoute())) return;
        item.setTaskOutcome(taskOutcome);
        save(item);
    }

    /** DEEP 报告被人工驳回或要求补充研究时入池，评论作为失败说明。 */
    @Transactional
    public void recordReportRejection(InvestmentReportVersion report) {
        if (repository.existsBySourceAndReportVersionId(EvolutionFailureCase.Source.REPORT_REVIEW, report.getId())) return;
        EvolutionFailureCase item = new EvolutionFailureCase();
        item.setUserId(report.getUserId());
        item.setSource(EvolutionFailureCase.Source.REPORT_REVIEW);
        item.setRoute("DEEP");
        item.setReportVersionId(report.getId());
        item.setTaskOutcome(report.getReviewStatus().name());
        item.setUserQuery(report.getUserQuery());
        item.setNote(report.getReviewComment());
        save(item);
    }

    @Transactional(readOnly = true)
    public List<EvolutionFailureCase> list(EvolutionFailureCase.Status status, EvolutionFailureCase.FailureType failureType, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(200, limit)));
        if (failureType != null) return repository.findByFailureTypeOrderByCreatedAtDesc(failureType, page);
        if (status != null) return repository.findByStatusOrderByCreatedAtDesc(status, page);
        return repository.findAllByOrderByCreatedAtDesc(page);
    }

    /** 分诊详情：失败条目、完整 Trace 和最终回答正文。 */
    @Transactional(readOnly = true)
    public Detail detail(Long id) {
        EvolutionFailureCase item = require(id);
        if (item.getTraceId() == null) return new Detail(item, null, null);
        String answer = messageRepository.findFirstByTraceIdAndRoleOrderByIdDesc(item.getTraceId(), "assistant")
                .map(Message::getContent).orElse(null);
        return new Detail(item, traceService.getTrace(item.getTraceId()), answer);
    }

    @Transactional
    public EvolutionFailureCase triage(Long id, EvolutionFailureCase.FailureType failureType, String note) {
        EvolutionFailureCase item = require(id);
        item.setFailureType(failureType);
        item.setTriageNote(note);
        item.setStatus(EvolutionFailureCase.Status.TRIAGED);
        item.setTriagedAt(LocalDateTime.now());
        return repository.save(item);
    }

    private EvolutionFailureCase require(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Failure case not found: " + id));
    }

    private EvolutionFailureCase fromTrace(AgentTrace trace, EvolutionFailureCase.Source source, String note) {
        EvolutionFailureCase item = new EvolutionFailureCase();
        item.setUserId(trace.getUserId());
        item.setSource(source);
        item.setTraceId(trace.getTraceId());
        item.setTaskOutcome(trace.getTaskOutcome());
        item.setUserQuery(trace.getUserQuery());
        item.setNote(note);
        item.setRoute("UNKNOWN");
        JsonNode evidence = ordinaryEvidenceStep(trace);
        if (evidence != null) {
            item.setRoute(evidence.path("route").asText("UNKNOWN"));
            String bundleId = evidence.path("methodBundle").path("bundleId").asText("");
            item.setMethodBundleId(bundleId.isBlank() ? null : bundleId);
        }
        return item;
    }

    /** Trace 中 kind=ordinary-evidence 步骤的属性；DEEP 等路线没有该步骤。 */
    private JsonNode ordinaryEvidenceStep(AgentTrace trace) {
        if (trace.getSteps() == null || trace.getSteps().isBlank()) return null;
        try {
            for (JsonNode step : objectMapper.readTree(trace.getSteps())) {
                JsonNode attributes = step.path("attributes");
                if ("ordinary-evidence".equals(attributes.path("kind").asText())) return attributes;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new IllegalStateException("Trace steps are not valid JSON: " + trace.getTraceId(), unreadable);
        }
        return null;
    }

    private void save(EvolutionFailureCase item) {
        item.setStatus(EvolutionFailureCase.Status.NEW);
        item.setCreatedAt(LocalDateTime.now());
        repository.save(item);
    }

    public record Detail(EvolutionFailureCase failure, AgentTrace trace, String answer) {}
}
