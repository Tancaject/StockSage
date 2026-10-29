package com.stocksage.eval;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.RoutingDecisionMetadata.SignalSnapshot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** 规划器回归评测的请求、样例断言与结果载荷。 */
public final class PlannerEvalModels {

    private PlannerEvalModels() {
    }

    /**
     * 规划器回归评测的执行模式。
     *
     * <p>该枚举由评测 API 传入，用来选择纯规则基线或真实协调器路径。</p>
     */
    public enum PlannerEvalMode {
        /** 仅运行确定性规则规划，不发起大模型调用，适合稳定回归。 */
        DETERMINISTIC,

        /** 运行生产 {@code Coordinator} 规划路径，结果可能受模型输出影响。 */
        LIVE_COORDINATOR
    }

    /**
     * 规划器批量回归评测接口的请求体。
     *
     * @param mode 要评测的规划实现，不能为空
     * @param cases 评测样例列表，不能为空且至少一项；构造后复制为不可变列表
     */
    public record PlannerEvalRequest(
            @NotNull PlannerEvalMode mode,
            @NotEmpty List<@Valid PlannerEvalCase> cases
    ) {
        /**
         * 规范化评测样例，避免调用方后续修改原列表影响本次评测。
         *
         * @param mode 要评测的规划实现
         * @param cases 评测样例；null 会先转为空列表，再由 Bean Validation 判定为非法请求
         */
        public PlannerEvalRequest {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    /**
     * 单条强类型规划器断言。
     *
     * <p>路由和动作直接使用枚举比较，避免展示文案变化导致回归结果漂移。</p>
     *
     * @param id 样例稳定标识，不能为空
     * @param query 送入协调器的用户问题，不能为空
     * @param ragHitCount 模拟已命中的 RAG 文档数，不能为负数
     * @param expectedRoute 期望选择的规划路由，不能为空
     * @param requiredActions 执行计划必须包含的动作；null 规范化为空列表
     * @param forbiddenActions 执行计划不得包含的动作；null 规范化为空列表
     * @param critical 失败时是否计入关键失败数
     * @param recentTurns 当前问题之前、按时间正序排列的最近对话消息；每项包含 user/assistant 角色前缀
     * @param expectedFineIntent 可选的细粒度意图枚举名；为空时不参与单例通过判定
     * @param expectedDecisionSource 可选的路由来源枚举名；为空时不参与单例通过判定
     * @param requireNoFallback 是否要求本例不得使用确定性回退
     * @param expectedResolvedQueryContains 可选的消歧结果片段；提供时要求执行计划的 resolvedQuery 包含它
     * @param expectedClarification 可选的路由元数据澄清标签（含 Coordinator 安全覆盖），不含 ReadRequest 参数澄清
     * @param expectedTargets 可选的多标的扇出标的（顺序无关）；为空时不参与判定，空列表表示期望不扇出
     */
    public record PlannerEvalCase(
            @NotBlank String id,
            @NotBlank String query,
            @Min(0) int ragHitCount,
            @NotNull PlanRoute expectedRoute,
            List<PlanAction> requiredActions,
            List<PlanAction> forbiddenActions,
            boolean critical,
            @Size(max = 6) List<@NotBlank @Size(max = 600) String> recentTurns,
            String expectedFineIntent,
            String expectedDecisionSource,
            boolean requireNoFallback,
            String expectedResolvedQueryContains,
            Boolean expectedClarification,
            List<String> expectedTargets
    ) {
        /**
         * 将动作断言复制为不可变列表，防止评测期间被外部修改。
         *
         * @param id 样例标识
         * @param query 用户问题
         * @param ragHitCount RAG 命中数
         * @param expectedRoute 期望路由
         * @param requiredActions 必需动作列表
         * @param forbiddenActions 禁止动作列表
         * @param critical 是否为关键样例
         * @param recentTurns 最近对话消息
         * @param expectedFineIntent 期望细粒度意图
         * @param expectedDecisionSource 期望路由来源
         * @param requireNoFallback 是否禁止回退
         * @param expectedResolvedQueryContains 期望消歧结果包含的片段
         * @param expectedClarification 期望是否请求澄清
         */
        public PlannerEvalCase {
            requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
            forbiddenActions = forbiddenActions == null ? List.of() : List.copyOf(forbiddenActions);
            recentTurns = recentTurns == null ? List.of() : recentTurns.stream()
                    .map(value -> value == null ? "" : value.trim())
                    .toList();
            expectedFineIntent = normalizeOptionalExpectation(expectedFineIntent);
            expectedDecisionSource = normalizeOptionalExpectation(expectedDecisionSource);
            expectedResolvedQueryContains = normalizeOptionalExpectation(expectedResolvedQueryContains);
        }

        /** 保留十三字段构造方式；旧样例没有标的断言。 */
        public PlannerEvalCase(
                String id, String query, int ragHitCount, PlanRoute expectedRoute,
                List<PlanAction> requiredActions, List<PlanAction> forbiddenActions, boolean critical,
                List<String> recentTurns, String expectedFineIntent, String expectedDecisionSource,
                boolean requireNoFallback, String expectedResolvedQueryContains, Boolean expectedClarification
        ) {
            this(id, query, ragHitCount, expectedRoute, requiredActions, forbiddenActions, critical,
                    recentTurns, expectedFineIntent, expectedDecisionSource, requireNoFallback,
                    expectedResolvedQueryContains, expectedClarification, null);
        }

        /** 保留十二字段构造方式；旧样例没有澄清标签。 */
        public PlannerEvalCase(
                String id, String query, int ragHitCount, PlanRoute expectedRoute,
                List<PlanAction> requiredActions, List<PlanAction> forbiddenActions, boolean critical,
                List<String> recentTurns, String expectedFineIntent, String expectedDecisionSource,
                boolean requireNoFallback, String expectedResolvedQueryContains
        ) {
            this(id, query, ragHitCount, expectedRoute, requiredActions, forbiddenActions, critical,
                    recentTurns, expectedFineIntent, expectedDecisionSource, requireNoFallback,
                    expectedResolvedQueryContains, null);
        }

        /** 保留最初 V2 十一字段构造方式，未声明消歧断言的调用保持原语义。 */
        public PlannerEvalCase(
                String id,
                String query,
                int ragHitCount,
                PlanRoute expectedRoute,
                List<PlanAction> requiredActions,
                List<PlanAction> forbiddenActions,
                boolean critical,
                List<String> recentTurns,
                String expectedFineIntent,
                String expectedDecisionSource,
                boolean requireNoFallback
        ) {
            this(id, query, ragHitCount, expectedRoute, requiredActions, forbiddenActions, critical,
                    recentTurns, expectedFineIntent, expectedDecisionSource, requireNoFallback, null);
        }

        /** 保留 V1 七字段构造方式，旧回归集合不需要显式补齐 V2 可选断言。 */
        public PlannerEvalCase(
                String id,
                String query,
                int ragHitCount,
                PlanRoute expectedRoute,
                List<PlanAction> requiredActions,
                List<PlanAction> forbiddenActions,
                boolean critical
        ) {
            this(id, query, ragHitCount, expectedRoute, requiredActions, forbiddenActions, critical,
                    List.of(), null, null, false, null);
        }

        /** @return 本例是否依赖仅 LIVE_COORDINATOR 能提供的语义或来源信息。 */
        public boolean liveOnly() {
            return !recentTurns.isEmpty()
                    || expectedFineIntent != null
                    || expectedDecisionSource != null
                    || requireNoFallback
                    || expectedResolvedQueryContains != null
                    || expectedClarification != null;
        }

        private static String normalizeOptionalExpectation(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
    }

    /**
     * 单条规划器样例的实际执行和断言结果。
     *
     * @param id 对应 {@link PlannerEvalCase#id()} 的样例标识
     * @param expectedRoute 样例期望路由
     * @param actualRoute 实际路由；规划器执行异常时为 null
     * @param plannedActions 实际执行计划中的动作，保持规划顺序
     * @param missingRequiredActions 未出现在计划中的必需动作
     * @param matchedForbiddenActions 错误出现在计划中的禁止动作
     * @param critical 样例是否属于关键门禁
     * @param passed 路由、必需动作和禁止动作是否全部符合预期
     * @param executable 是否成功生成了执行计划
     * @param durationMs 单条样例墙钟耗时，单位毫秒
     * @param errorCode 失败分类；执行成功时为空字符串
     * @param decisionSource 路由决定来源，例如规则、模型或回退路径
     * @param rawRoute 模型返回或路由器解析前保留的原始路由文本
     * @param intentSummary 协调器提取的用户意图摘要
     * @param rationale 路由决定理由
     * @param confidence 路由置信度，通常范围 0～1；无元数据时为 0
     * @param fallbackReason 使用回退路由时的原因；未回退时为空字符串
     * @param expectedFineIntent 可选的期望细粒度意图
     * @param actualFineIntent 实际细粒度意图；元数据缺失时为空字符串
     * @param fineIntentMatched 未提供期望值或实际值匹配时为 true
     * @param expectedDecisionSource 可选的期望路由来源
     * @param decisionSourceMatched 未提供期望值或实际值匹配时为 true
     * @param requireNoFallback 本例是否要求不得回退
     * @param noFallbackMatched 未要求禁止回退或确实未回退时为 true
     * @param routeMatched 最终执行路由是否命中期望
     * @param contextCase 是否携带最近对话上下文
     * @param fallback 是否实际使用确定性回退
     * @param executionGuarded 最终执行路由是否被服务器安全闸门覆盖；此时不拿原始语义路由与执行路由比较
     * @param rawRouteValid 原始路由是否可严格解析为后端五选一路由
     * @param rawRouteMatched 合法原始路由是否命中期望路由
     * @param expectedResolvedQueryContains 可选的期望消歧片段
     * @param actualResolvedQuery 执行计划实际使用的有界消歧问题
     * @param contextResolutionMatched 未提供期望片段或实际消歧问题包含该片段时为 true
     * @param expectedClarification 可选的澄清标签
     * @param actualClarification 路由元数据 needsClarification（含 Coordinator 安全覆盖，不含 ReadRequest 参数澄清）；缺失或执行失败时为 null
     * @param clarificationMatched 已声明标签且观测存在时的匹配结果；否则为 null
     * @param signalDiagnostics 各来源实际候选的安全快照；没有观测时为空
     * @param reasonCodes 实际路由理由代码；没有观测时为空
     * @param expectedTargets 可选的期望扇出标的
     * @param actualTargets 计划实际扇出的标的；单标的为空
     * @param targetsMatched 已声明期望时的匹配结果；否则为 null
     */
    public record PlannerEvalResult(
            String id,
            PlanRoute expectedRoute,
            PlanRoute actualRoute,
            List<PlanAction> plannedActions,
            List<PlanAction> missingRequiredActions,
            List<PlanAction> matchedForbiddenActions,
            boolean critical,
            boolean passed,
            boolean executable,
            long durationMs,
            String errorCode,
            String decisionSource,
            String rawRoute,
            String intentSummary,
            String rationale,
            double confidence,
            String fallbackReason,
            String expectedFineIntent,
            String actualFineIntent,
            boolean fineIntentMatched,
            String expectedDecisionSource,
            boolean decisionSourceMatched,
            boolean requireNoFallback,
            boolean noFallbackMatched,
            boolean routeMatched,
            boolean contextCase,
            boolean fallback,
            boolean executionGuarded,
            boolean rawRouteValid,
            boolean rawRouteMatched,
            String expectedResolvedQueryContains,
            String actualResolvedQuery,
            boolean contextResolutionMatched,
            Boolean expectedClarification,
            Boolean actualClarification,
            Boolean clarificationMatched,
            List<SignalSnapshot> signalDiagnostics,
            List<String> reasonCodes,
            List<String> expectedTargets,
            List<String> actualTargets,
            Boolean targetsMatched
    ) {
        public PlannerEvalResult {
            actualTargets = actualTargets == null ? List.of() : List.copyOf(actualTargets);
            signalDiagnostics = signalDiagnostics == null ? List.of() : List.copyOf(signalDiagnostics);
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    /**
     * 单个规划路由的分类指标，供规划器回归报告按路由定位误判。
     *
     * @param precision 精确率，范围 0～1；表示预测为该路由的样本中实际命中的比例
     * @param recall 召回率，范围 0～1；表示期望该路由的样本中被正确识别的比例
     * @param f1 精确率与召回率的调和平均值，范围 0～1
     * @param expectedCount 评测集中期望路由为该值的样本数
     * @param predictedCount 规划器实际预测为该路由的样本数
     */
    public record RouteEvalMetrics(
            double precision,
            double recall,
            double f1,
            int expectedCount,
            int predictedCount
    ) {
    }

    /**
     * 一批规划器评测样例的汇总与逐例结果。
     *
     * @param schemaVersion 响应结构版本，便于离线脚本兼容字段演进
     * @param mode 本次实际运行的规划器模式
     * @param status 全部样例通过时为 passed，否则为 failed
     * @param requestedCases 请求提交的样例总数，包括 DETERMINISTIC 无法评估而跳过的 live-only 样例
     * @param totalCases 实际评估的样例总数
     * @param skippedLiveOnlyCases DETERMINISTIC 模式跳过的上下文或严格来源样例数
     * @param passedCases 满足路由、动作及已提供 V2 可选断言的样例数
     * @param criticalFailures 标记为关键且未通过的样例数
     * @param routeAccuracy 路由命中数占全部样例的比例，范围 0～1
     * @param macroF1 各路由 F1 的算术平均值，范围 0～1
     * @param perRoute 以路由枚举名为键的分类指标
     * @param requiredActionRecall 必需动作命中率；没有必需动作时为 1
     * @param forbiddenActionRate 禁止动作被错误规划的比例；没有禁止动作时为 0
     * @param executableRate 成功生成执行计划的样例比例，范围 0～1
     * @param intentEvaluatedCases 提供 expectedFineIntent 的已评估样例数
     * @param intentAccuracy 细粒度意图准确率；没有可评估样例时为 0
     * @param contextCases 携带 recentTurns 的已评估样例数
     * @param contextCaseAccuracy 上下文样例最终路由准确率；没有上下文样例时为 0
     * @param contextResolutionCases 提供 expectedResolvedQueryContains 的已评估样例数
     * @param contextResolutionAccuracy 上下文消歧片段命中率；没有可评估样例时为 0
     * @param nonFallbackCases 未使用确定性回退的已评估样例数
     * @param nonFallbackRouteAccuracy 非回退样例最终路由准确率；没有此类样例时为 0
     * @param llmSignalCases 具有合法原始 LLM 路由候选、且未被服务器执行闸门覆盖的非回退样例数
     * @param llmSignalAccuracy 原始 LLM 路由候选准确率；没有此类样例时为 0
     * @param fallbackCases 使用确定性回退的样例数
     * @param fallbackRate 回退样例占全部已评估样例的比例
     * @param invalidRawRouteCases 原始路由为空或无法严格解析的样例数
     * @param invalidRawRouteRate 非法原始路由占全部已评估样例的比例
     * @param durationMs 整批评测墙钟耗时，单位毫秒
     * @param checkedAt 评测完成时间的 ISO 本地日期时间字符串
     * @param results 与实际评估样例一一对应的详细结果；跳过样例只计入 skippedLiveOnlyCases
     */
    public record PlannerEvalResponse(
            String schemaVersion,
            PlannerEvalMode mode,
            String status,
            int requestedCases,
            int totalCases,
            int skippedLiveOnlyCases,
            int passedCases,
            int criticalFailures,
            double routeAccuracy,
            double macroF1,
            Map<String, RouteEvalMetrics> perRoute,
            double requiredActionRecall,
            double forbiddenActionRate,
            double executableRate,
            int intentEvaluatedCases,
            double intentAccuracy,
            int contextCases,
            double contextCaseAccuracy,
            int contextResolutionCases,
            double contextResolutionAccuracy,
            int nonFallbackCases,
            double nonFallbackRouteAccuracy,
            int llmSignalCases,
            double llmSignalAccuracy,
            int fallbackCases,
            double fallbackRate,
            int invalidRawRouteCases,
            double invalidRawRouteRate,
            long durationMs,
            String checkedAt,
            List<PlannerEvalResult> results
    ) {
        /** 保留 V1 汇总构造方式，迁移期的控制器和测试夹具无需一次性补齐新指标。 */
        public PlannerEvalResponse(
                String schemaVersion,
                PlannerEvalMode mode,
                String status,
                int totalCases,
                int passedCases,
                int criticalFailures,
                double routeAccuracy,
                double macroF1,
                Map<String, RouteEvalMetrics> perRoute,
                double requiredActionRecall,
                double forbiddenActionRate,
                double executableRate,
                long durationMs,
                String checkedAt,
                List<PlannerEvalResult> results
        ) {
            this(schemaVersion, mode, status, totalCases, totalCases, 0, passedCases, criticalFailures,
                    routeAccuracy, macroF1, perRoute, requiredActionRecall, forbiddenActionRate,
                    executableRate, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0,
                    0, 0.0, 0, 0.0, durationMs, checkedAt, results);
        }
    }
}
