package com.stocksage.agent.ordinary;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.runtime.ModelCompletion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.util.JsonText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 普通路线的动态规划器（P&E 的 Plan 阶段）。
 *
 * <p>以固定计划为起点，让 FAST 模型在动作菜单里为本题选择取数步骤和参数；服务端校验动作、参数范围、
 * 步数上限和需求覆盖，并让用户原话里写明的参数优先。任何一项不通过，整份计划作废并退回固定计划，
 * 不做局部修补。标的、分析师、模型档位由服务端决定，模型无法改变。</p>
 */
@Slf4j
@Component
public class OrdinaryPlanner {

    /** 规划器可以选择的取数动作。 */
    static final List<PlanAction> MENU = List.of(
            PlanAction.GET_STOCK_KLINE, PlanAction.GET_FINANCIAL_METRICS, PlanAction.GET_TECHNICAL_INDICATORS,
            PlanAction.GET_FINANCIAL_REPORTS, PlanAction.SEARCH_COMPANY_REPORTS, PlanAction.SEARCH_NEWS,
            PlanAction.WEB_SEARCH);
    private static final int MAX_STEPS_PER_TARGET = 6;
    private static final int MAX_TOTAL_STEPS = 16;

    private final ChatClient plannerClient;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor agentTaskExecutor;

    @Value("${stocksage.agent.ordinary.planner.timeout-ms:20000}")
    private long timeoutMs = 20000;

    public OrdinaryPlanner(@Qualifier("ordinaryPlannerChatClient") ChatClient plannerClient,
                           ObjectMapper objectMapper,
                           AsyncTaskExecutor agentTaskExecutor) {
        this.plannerClient = plannerClient;
        this.objectMapper = objectMapper;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    /**
     * 为本题生成计划；失败时返回来源为 FALLBACK 的固定计划。
     *
     * @param seed 固定计划（含用户原话解析出的参数和确定性需求），也是失败时的回退
     */
    public OrdinaryPlan plan(PlanRoute route, String resolvedQuery, String originalQuery, OrdinaryPlan seed) {
        long started = System.nanoTime();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        String raw = "";
        try {
            raw = call(buildPrompt(route, resolvedQuery, seed), diagnostics);
            diagnostics.put("rawOutput", raw.length() > 800 ? raw.substring(0, 800) : raw);
            List<String> violations = new ArrayList<>();
            OrdinaryPlan plan = validate(raw, originalQuery, seed, violations, diagnostics);
            diagnostics.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (plan == null) {
                diagnostics.put("violations", violations);
                return fallback(seed, "INVALID_PLAN", diagnostics);
            }
            return new OrdinaryPlan(plan.targets(), plan.steps(), plan.requirements(), OrdinaryPlan.Source.DYNAMIC,
                    diagnostics, plan.fallbackSteps());
        } catch (java.util.concurrent.TimeoutException timeout) {
            diagnostics.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return fallback(seed, "TIMEOUT", diagnostics);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("规划已取消。");
        } catch (Exception error) {
            log.warn("Ordinary planner failed, using fixed plan. errorType={}", error.getClass().getSimpleName());
            diagnostics.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            diagnostics.put("errorType", error.getClass().getSimpleName());
            return fallback(seed, "PLANNER_FAILED", diagnostics);
        }
    }

    private String call(String prompt, Map<String, Object> diagnostics) throws Exception {
        var observation = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>(Map.of());
        FutureTask<String> task = new FutureTask<>(() -> {
            Map<String, Object> facts = new LinkedHashMap<>(ModelCompletion.usage(null));
            facts.putAll(Map.of("kind", "model-invocation", "scope", "ordinary-planner",
                    "invocationId", java.util.UUID.randomUUID().toString(), "status", "RUNNING",
                    "callCountSemantics", "CLIENT_INVOCATION"));
            observation.set(Map.copyOf(facts));
            long started = System.nanoTime();
            try {
                var response = plannerClient.prompt().user(prompt).call().chatResponse();
                facts.putAll(ModelCompletion.usage(response));
                facts.put("status", "SUCCEEDED");
                return ModelCompletion.output(response).content();
            } catch (RuntimeException error) {
                facts.put("status", "FAILED");
                facts.put("errorType", error.getClass().getSimpleName());
                throw error;
            } finally {
                facts.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                observation.set(Map.copyOf(facts));
            }
        });
        agentTaskExecutor.execute(task);
        String termination = "";
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | InterruptedException stop) {
            task.cancel(true);
            termination = stop instanceof InterruptedException ? "CANCELLED" : "TIMEOUT";
            throw stop;
        } catch (java.util.concurrent.ExecutionException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        } finally {
            if (!observation.get().isEmpty()) {
                Map<String, Object> facts = new LinkedHashMap<>(observation.get());
                if (!termination.isEmpty()) {
                    facts.put("status", "FAILED");
                    facts.put("termination", termination);
                }
                diagnostics.put("modelObservation", Map.copyOf(facts));
            }
        }
    }

    String buildPrompt(PlanRoute route, String query, OrdinaryPlan seed) {
        StringBuilder menu = new StringBuilder();
        menu.append("- GET_STOCK_KLINE：K 线行情（参数 period、bar）\n")
                .append("- GET_FINANCIAL_METRICS：估值与财务指标\n")
                .append("- GET_TECHNICAL_INDICATORS：技术指标 MA/MACD/RSI（只支持日线）\n")
                .append("- GET_FINANCIAL_REPORTS：结构化财务报表（参数 reportPeriod、reportCount）\n")
                .append("- SEARCH_COMPANY_REPORTS：财报原文；知识库已有该公司原文时自动跳过，没有时美股读 SEC 申报、港股和 A 股用网页搜索\n")
                .append("- SEARCH_NEWS：新闻搜索\n")
                .append("- WEB_SEARCH：通用网页搜索\n");
        List<String> seedSteps = seed.steps().stream()
                .filter(step -> MENU.contains(step.action()))
                .map(step -> step.action().name() + " " + step.request().attributes())
                .toList();
        return """
                路由：%s
                用户问题：%s
                标的：%s
                动作菜单：
                %s
                固定计划（起点）：%s
                已确定必须满足的数据需求：%s
                约束：每个标的最多 %d 步；period 不超过 1 年；bar 取 1min/5min/15min/30min/1h/2h/4h/1d/1w/1m；
                reportPeriod 为 annual 时 reportCount 最多 10，quarterly 时最多 40。
                """.formatted(route, query,
                seed.targets().isEmpty() ? "单个标的" : String.join("、", seed.targets()),
                menu, seedSteps, seed.requirements(), MAX_STEPS_PER_TARGET);
    }

    /** 校验模型输出；返回 null 表示整份作废，违规原因写入 violations。 */
    OrdinaryPlan validate(String raw, String originalQuery, OrdinaryPlan seed, List<String> violations,
                          Map<String, Object> diagnostics) throws Exception {
        JsonNode root = objectMapper.readTree(JsonText.extractObject(raw));
        if (root.path("parametersDifferByTarget").asBoolean(false) && seed.multiTarget()) {
            diagnostics.put("parametersDifferByTarget", true);
        }
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            violations.add("NO_STEPS");
            return null;
        }
        int targetCount = Math.max(1, seed.targets().size());
        if (stepsNode.size() > MAX_STEPS_PER_TARGET || stepsNode.size() * targetCount > MAX_TOTAL_STEPS) {
            violations.add("TOO_MANY_STEPS");
            return null;
        }
        ReadRequest base = seed.steps().isEmpty()
                ? ReadRequest.parse(PlanRoute.DIRECT, "", Map.of()) : seed.steps().get(0).request();
        Set<String> explicit = ReadRequest.explicitFields(originalQuery);
        List<PlanStep> dataSteps = new ArrayList<>();
        List<String> overridden = new ArrayList<>();
        for (JsonNode node : stepsNode) {
            PlanAction action;
            try {
                action = PlanAction.valueOf(node.path("action").asText("").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                violations.add("UNKNOWN_ACTION:" + node.path("action").asText(""));
                return null;
            }
            if (!MENU.contains(action)) {
                violations.add("ACTION_NOT_IN_MENU:" + action.name());
                return null;
            }
            // 每类步骤只取、只校验自己的参数：K 线看范围和粒度，财报看口径和期数，其余步骤沿用基础参数。
            ReadRequest request = base;
            if (action == PlanAction.GET_STOCK_KLINE) {
                // 原话里的范围只有本身是合法 K 线范围时才覆盖（"最近三年营收"的三年不能套到 K 线上）。
                Set<String> barFields = ReadRequest.isValidBars(base.period(), base.bar()) ? explicit : Set.of();
                String period = explicitOr(barFields, "period", base.period(), text(node, "period"), overridden);
                String bar = explicitOr(barFields, "bar", base.bar(), text(node, "bar"), overridden);
                request = base.withBars(period, bar);
            } else if (action == PlanAction.GET_FINANCIAL_REPORTS) {
                String reportPeriod = explicitOr(explicit, "reportPeriod", base.reportPeriod(),
                        text(node, "reportPeriod"), overridden);
                Integer count = explicit.contains("reportCount") || !node.path("reportCount").canConvertToInt()
                        ? null : node.path("reportCount").asInt();
                request = base.withReports(reportPeriod, count);
            }
            if (!request.clarification().isBlank()) {
                violations.add("PARAMETER_OUT_OF_RANGE:" + action.name());
                return null;
            }
            dataSteps.add(new PlanStep(action, request));
        }
        Set<DataRequirement> requirements = EnumSet.noneOf(DataRequirement.class);
        requirements.addAll(seed.requirements());
        for (JsonNode item : root.path("requirements")) {
            try {
                requirements.add(DataRequirement.valueOf(item.asText("").trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 未知需求名不影响计划；需求只能增加，不能删除确定性需求。
            }
        }
        List<PlanAction> actions = dataSteps.stream().map(PlanStep::action).toList();
        for (DataRequirement requirement : requirements) {
            if (!requirement.coveredBy(actions)) violations.add("REQUIREMENT_UNCOVERED:" + requirement.name());
        }
        if (!violations.isEmpty()) return null;
        if (!overridden.isEmpty()) diagnostics.put("overriddenByUserConstraints", overridden);
        diagnostics.put("plannerRequirements", requirements.stream().map(Enum::name).sorted().toList());
        // 服务端保留固定计划中的非取数步骤：知识库检索标记、分析师和最终回答。
        List<PlanStep> steps = new ArrayList<>();
        seed.steps().stream().filter(step -> step.action() == PlanAction.KNOWLEDGE_RETRIEVAL).forEach(steps::add);
        steps.addAll(dataSteps);
        seed.steps().stream().filter(step -> step.action().isAgentRole() || step.action() == PlanAction.FINAL_ANSWER)
                .forEach(steps::add);
        // 被删掉的固定计划取数步骤留作兜底：主数据源失败时补跑，避免删掉的其实是备用来源。
        List<PlanStep> fallback = seed.steps().stream()
                .filter(step -> MENU.contains(step.action()) && !actions.contains(step.action()))
                .toList();
        return new OrdinaryPlan(seed.targets(), steps, requirements, OrdinaryPlan.Source.DYNAMIC, diagnostics, fallback);
    }

    private static String explicitOr(Set<String> explicit, String field, String userValue, String modelValue,
                                     List<String> overridden) {
        if (explicit.contains(field)) {
            if (modelValue != null && !modelValue.equals(userValue)) overridden.add(field);
            return userValue;
        }
        return modelValue;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static OrdinaryPlan fallback(OrdinaryPlan seed, String reason, Map<String, Object> diagnostics) {
        diagnostics.put("fallbackReason", reason);
        return new OrdinaryPlan(seed.targets(), seed.steps(), seed.requirements(), OrdinaryPlan.Source.FALLBACK, diagnostics);
    }
}
