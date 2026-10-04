package com.stocksage.research;

import com.stocksage.agent.Coordinator;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.MarketTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 研究提交入口拥有去重、受理和观察选择；实际阶段与租约仍由研究管线负责。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ResearchSubmissionService {
    private final ResearchTaskService researchTaskService;
    private final ResearchTaskObservationService researchTaskObservationService;
    private final ResearchTaskQueue researchTaskQueue;
    private final ReportMarkdownRenderer reportRenderer;
    private final DeepResearchPipeline deepResearchPipeline;
    private final MarketTools marketTools;
    private final ChatStreamEmitter chatStreamEmitter;

    @Value("${stocksage.research-task.user-max-active:3}")
    private int userMaxActive = 3;

    /** 先观察同键运行，再受理新任务；仅在队列不可用时委托管线同步执行。 */
    public SubmissionResult submit(
            String userQuery,
            String traceId,
            Long conversationId,
            String userId,
            Coordinator.SelectedModel selectedModel,
            String primaryTicker,
            TimeSensitivity timeSensitivity,
            String submissionId
    ) {
        String submissionKey = submissionId == null
                ? researchTaskService.buildSubmissionKey(userId, primaryTicker, userQuery, conversationId)
                : researchTaskService.buildRunSubmissionKey(userId, submissionId);
        var existing = researchTaskService.findSubmission(submissionKey);
        if (existing.isPresent()) {
            return observeSubmission(existing.get(), traceId, conversationId);
        }
        int active = researchTaskService.countActiveTasks(userId);
        if (active >= Math.max(1, userMaxActive)) {
            String answer = reportRenderer.buildQuotaExceededAnswer(active, Math.max(1, userMaxActive));
            return new SubmissionResult("", answer, null, traceId, "BLOCKED");
        }

        String payload = researchTaskService.buildSubmissionPayload(
                primaryTicker, userQuery, traceId, conversationId, timeSensitivity);
        ResearchTaskService.TaskCreation creation = researchTaskService.createIfAbsent(
                submissionKey,
                userId,
                conversationId,
                primaryTicker,
                ResearchTask.Stage.CREATED,
                payload
        );
        ResearchTask task = creation.task();
        if (!creation.created()) {
            return observeSubmission(task, traceId, conversationId);
        }

        try {
            researchTaskQueue.enqueue(task.getId());
            chatStreamEmitter.emit(traceId, conversationId, "thought", "深度研究任务已受理，后台开始执行。");
            return new SubmissionResult(
                    "", reportRenderer.buildTaskAcceptedAnswer(task), task.getId(), traceId);
        } catch (ResearchTaskQueue.QueueUnavailableException error) {
            log.warn("Task queue unavailable, falling back to inline DEEP execution: {}", error.getMessage());
            StringBuilder context = new StringBuilder();
            appendResolvedStockIdentity(context, primaryTicker);
            DeepResearchPipeline.InlineResult result = deepResearchPipeline.runInlineFallback(
                    primaryTicker, userQuery, traceId, conversationId, userId, selectedModel, task,
                    timeSensitivity, context.toString());
            return new SubmissionResult(result.context(), result.directAnswer(), result.submittedTaskId(),
                    result.eventTraceId(), result.taskOutcome());
        }
    }

    private SubmissionResult observeSubmission(ResearchTask task, String traceId, Long conversationId) {
        if (task.getStatus() == ResearchTask.Status.SUCCEEDED || task.getStatus() == ResearchTask.Status.FAILED) {
            return new SubmissionResult("", researchTaskObservationService.terminalContent(task, task.getUserId()),
                    null, traceId, deepResearchPipeline.taskOutcome(task));
        }
        chatStreamEmitter.emit(traceId, conversationId, "observation", "匹配到已提交的深度研究，正在读取原运行状态。");
        return new SubmissionResult("", reportRenderer.buildTaskAcceptedAnswer(task),
                task.getId(), deepResearchPipeline.taskTraceId(task, traceId));
    }

    /**
     * 将已解析股票身份写入提示词上下文。
     */
    private void appendResolvedStockIdentity(StringBuilder context, String primaryTicker) {
        context.append("## resolvedStockIdentity\n");
        if (primaryTicker == null || primaryTicker.isBlank()) {
            context.append("No supported stock target was resolved. Do not use unrelated company facts.\n\n");
            return;
        }
        String identity = null;
        try {
            identity = marketTools.resolveStock(primaryTicker);
        } catch (Exception ignored) {
            // 身份读取失败只产生空观察，不替代后续证据的标的验收。
        }
        context.append(identity == null || identity.isBlank() ? "(empty result)" : identity)
                .append("\n\n");
    }

    /** 与聊天 Prompt/SSE 协议无关的提交结果；同步执行时也携带生成的上下文。 */
    public record SubmissionResult(String context, String directAnswer, Long submittedTaskId,
                                   String eventTraceId, String taskOutcome) {
        private SubmissionResult(String context, String directAnswer, Long submittedTaskId, String eventTraceId) {
            this(context, directAnswer, submittedTaskId, eventTraceId, null);
        }
    }
}
