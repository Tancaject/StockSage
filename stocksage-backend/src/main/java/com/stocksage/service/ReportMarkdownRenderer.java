package com.stocksage.service;

import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchTask;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 投研报告的 Markdown 渲染器。
 *
 * <p>把结构化 {@link InvestmentReport} 和研究状态转换成面向用户的报告草稿文本。
 * 全部是无副作用的纯文本函数，从 ChatService 拆出以便独立测试。</p>
 */
@Component
public class ReportMarkdownRenderer {

    /** Render the immediate acknowledgement returned after a DEEP task enters the background queue. */
    public String buildTaskAcceptedAnswer(ResearchTask task) {
        String ticker = task == null || task.getTicker() == null || task.getTicker().isBlank()
                ? "UNKNOWN"
                : task.getTicker().trim();
        String taskId = task == null || task.getId() == null ? "unknown" : task.getId().toString();
        String stage = task == null || task.getStage() == null ? "CREATED" : task.getStage().name();
        return """
                ## 深度研究任务已受理

                - 标的：%s
                - 任务号：%s
                - 当前阶段：%s

                研究将在后台继续执行；本页面会实时展示进度，完成后自动返回完整报告。
                """.formatted(ticker, taskId, stage).trim();
    }

    /** Render a deterministic response when one user has exhausted the active-task quota. */
    public String buildQuotaExceededAnswer(int activeCount, int maxActive) {
        return """
                ## 深度研究并发额度已满

                当前已有 %d 个进行中的深度研究任务，账户上限为 %d 个。请等待其中一个任务完成后再提交。
                """.formatted(Math.max(0, activeCount), Math.max(1, maxActive)).trim();
    }

    /**
     * 构造最终回答顶部摘要（含面向用户的输出要求）。
     */
    public String buildFinalAnswerBrief(AnalysisState state) {
        InvestmentReport report = state.getInvestmentReport();
        if (report == null) {
            return "";
        }

        StringBuilder brief = new StringBuilder();
        brief.append("## 投研报告草稿\n");
        brief.append(buildEvidenceFirstInvestmentReport(state)).append("\n");
        brief.append("""

                面向用户输出要求：
                1. 使用简体中文。
                2. 不要展示 Bull/Bear 原文，不要出现内部 agent 名称、Java 方法名或工具函数名。
                3. 保留投研报告草稿的一级结构和证据表，不要改成闲聊式回答。
                4. 必须包含多空权衡、适合/不适合投资的条件、仓位与风险提示。
                5. 不要编造没有出现在预取观察、知识库或裁决中的精确数据。
                6. 未知项和数据缺口必须保留。
                """);
        return brief.toString();
    }

    /**
     * 深度研究关键证据缺失时，构造确定性的“数据不足”回答草稿。
     * 避免在没有财务或行情数据的情况下输出 BUY/OVERWEIGHT/HOLD/UNDERWEIGHT/SELL 评级。
     */
    public String buildInsufficientEvidenceReport(String ticker, boolean tickerResolved,
                                                  boolean fundamentalsOk, boolean marketOk) {
        String target = (ticker == null || ticker.isBlank()) ? "本次询问的标的" : ticker;
        StringBuilder gaps = new StringBuilder();
        if (!tickerResolved) {
            gaps.append("- 未能可靠解析到具体股票标的，无法确定分析对象。\n");
        } else {
            if (!fundamentalsOk) {
                gaps.append("- 财务 / 财报数据源调用失败、超时或返回空结果。\n");
            }
            if (!marketOk) {
                gaps.append("- 行情 / 技术指标数据源调用失败、超时或返回空结果。\n");
            }
        }
        return """
                ## 投资结论
                - 结论：**无法评级（数据不足）**
                - 说明：%s 的关键证据源本轮未取得有效数据。深度投研至少需要财务或行情数据之一作为最低证据，当前均不可用，无法支撑可靠的投资判断。

                ## 当前数据缺口
                %s

                ## 建议
                - 稍后重试；或先用单点查询（行情、财报）确认数据源是否恢复。
                - 如确需判断，请结合自身渠道的最新财报与行情独立复核，不要依赖本轮不完整的数据。

                以上分析仅供参考，不构成投资建议。
                """.formatted(target, gaps.toString().trim());
    }

    /**
     * 构造“同一深度研究任务已在运行”的确定性回答。
     */
    public String buildResearchTaskAlreadyRunningAnswer(ResearchTask task) {
        String stage = task == null || task.getStage() == null ? "unknown" : task.getStage().name();
        Integer attempts = task == null ? null : task.getAttempts();
        return """
                ## Research task status
                The same DEEP research task is already running. StockSage skipped a duplicate Bull/Bear debate for this request.

                - status: RUNNING
                - stage: %s
                - attempts: %s

                Please check the report history or retry after the running task finishes. This preserves idempotency and avoids spending the same expensive debate twice.
                """.formatted(stage, attempts == null ? "unknown" : attempts.toString()).trim();
    }

    /**
     * 构造证据优先的投资研究报告正文。
     */
    public String buildEvidenceFirstInvestmentReport(AnalysisState state) {
        InvestmentReport report = state == null ? null : state.getInvestmentReport();
        if (report == null) {
            return "";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("## 投资结论\n");
        answer.append("- 结论：**").append(normalizeRecommendationLabel(report.getRecommendation())).append("**\n");
        if (report.getAnalystSummary() != null && !report.getAnalystSummary().isBlank()) {
            answer.append("- 摘要：").append(sanitizeUserFacingText(report.getAnalystSummary().trim())).append("\n");
        }
        answer.append("- 操作含义：").append(recommendationAction(report.getRecommendation())).append("\n\n");

        appendReportSectionList(answer, "核心依据", report.getRationale(),
                List.of("当前结构化结论不足，需要结合证据表和风险项谨慎判断。"));
        appendEvidenceTable(answer, report);

        answer.append("## 多空权衡\n");
        appendReportSubList(answer, "利多因素",
                listOrFallback(report.getBullFactors(), List.of(firstParagraph(report.getBullCase(), 900))));
        appendReportSubList(answer, "利空因素",
                listOrFallback(report.getBearFactors(), listOrFallback(report.getRiskFactors(),
                        List.of(firstParagraph(report.getBearCase(), 900)))));
        answer.append("\n");

        answer.append("## 适合 / 不适合\n");
        appendReportSubList(answer, "更适合",
                listOrFallback(report.getSuitableFor(),
                        List.of("能接受波动、愿意分批验证投资假设，并持续跟踪财报和行业数据的投资者。")));
        appendReportSubList(answer, "不适合",
                listOrFallback(report.getNotSuitableFor(),
                        List.of("需要短期确定性收益、无法承受回撤，或不愿持续跟踪关键风险的投资者。")));
        answer.append("\n");

        answer.append("## 风险与未知项\n");
        appendReportSubList(answer, "主要风险",
                listOrFallback(report.getRiskFactors(),
                        List.of("模型输出仍需结合实时数据、仓位和个人风险承受能力复核。")));
        appendReportSubList(answer, "未知项 / 需要继续核验",
                listOrFallback(report.getUnknowns(),
                        List.of("部分数据源可能存在延迟、缺失或覆盖不完整，需要结合最新公告和行情复核。")));
        answer.append("\n");

        answer.append("## 数据来源与时间说明\n");
        answer.append("- 本轮生成日期：").append(LocalDate.now()).append("。\n");
        if (report.getReportVersion() != null) {
            answer.append("- 报告版本：v").append(report.getReportVersion());
            if (Boolean.TRUE.equals(report.getReusedFromCache())) {
                answer.append("（同快照复用）");
            }
            answer.append("；dataSnapshotHash=")
                    .append(shortHash(report.getDataSnapshotHash()))
                    .append("；contextHash=")
                    .append(shortHash(report.getContextHash()))
                    .append("。\n");
        }
        if (report.getModelTier() != null || report.getModelName() != null) {
            answer.append("- 生成模型：")
                    .append(blankToDefault(report.getModelTier(), "unknown tier"));
            if (report.getModelName() != null && !report.getModelName().isBlank()) {
                answer.append(" / ").append(report.getModelName());
            }
            answer.append("。\n");
        }
        answer.append("- ").append(sanitizeUserFacingText(blankToDefault(report.getDataFreshness(),
                "本轮数据来自后端预取的财务、行情、新闻和知识库片段；具体时点以各数据源返回为准。"))).append("\n");
        for (String source : listOrFallback(report.getCitations(), evidenceBasis())) {
            answer.append("- ").append(sanitizeUserFacingText(source)).append("\n");
        }
        answer.append("\n以上分析仅供参考，不构成投资建议。");
        return answer.toString();
    }

    /**
     * 清理面向用户的最终文本，去掉内部类名、工具名等实现细节。
     */
    public String sanitizeUserFacingText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("getStructuredFinancials", "结构化财务数据")
                .replace("getFinancialReports", "结构化财报数据")
                .replace("searchCompanyReports", "财报公告搜索")
                .replace("searchStocks", "股票搜索")
                .replace("resolveStock", "股票代码解析")
                .replace("getStockKLine", "行情数据")
                .replace("getFinancialMetrics", "财务指标")
                .replace("getTechnicalIndicators", "技术指标")
                .replace("getIbkrHistoricalBars", "IBKR历史行情")
                .replace("getIbkrRealtimeQuote", "IBKR报价")
                .replace("getSectorPerformance", "板块行情")
                .replace("getStockNews", "股票新闻")
                .replace("searchNews", "新闻搜索")
                .replace("webSearch", "网页搜索")
                .replace("Bull/Bear Debate", "内部多空评估")
                .replace("Bull Researcher", "看多侧")
                .replace("Bear Researcher", "看空侧")
                .replace("Research Manager", "综合裁决");
    }

    /**
     * 追加报告章节列表，列表为空时使用 fallback。
     */
    private void appendReportSectionList(StringBuilder builder, String title, List<String> items, List<String> fallback) {
        builder.append("## ").append(title).append("\n");
        List<String> values = listOrFallback(items, fallback);
        for (String item : values) {
            if (item != null && !item.isBlank()) {
                builder.append("- ").append(sanitizeUserFacingText(item.trim())).append("\n");
            }
        }
        builder.append("\n");
    }

    /**
     * 追加报告子列表。
     */
    private void appendReportSubList(StringBuilder builder, String title, List<String> items) {
        builder.append("**").append(title).append("**\n");
        for (String item : listOrFallback(items, List.of("当前证据不足，暂不展开。"))) {
            if (item != null && !item.isBlank()) {
                builder.append("- ").append(sanitizeUserFacingText(item.trim())).append("\n");
            }
        }
        builder.append("\n");
    }

    /**
     * 追加证据表格。
     */
    private void appendEvidenceTable(StringBuilder builder, InvestmentReport report) {
        builder.append("## 证据表\n");
        builder.append("| 维度 | 关键证据 | 对判断的含义 | 来源 |\n");
        builder.append("| --- | --- | --- | --- |\n");
        for (InvestmentReport.EvidenceItem item : evidenceItemsOrFallback(report)) {
            builder.append("| ")
                    .append(markdownCell(item.getDimension(), "综合"))
                    .append(" | ")
                    .append(markdownCell(item.getEvidence(), "当前未生成逐条证据"))
                    .append(" | ")
                    .append(markdownCell(item.getImplication(), "需要结合其他证据复核"))
                    .append(" | ")
                    .append(markdownCell(item.getSource(), "未说明"))
                    .append(" |\n");
        }
        builder.append("\n");
    }

    private List<InvestmentReport.EvidenceItem> evidenceItemsOrFallback(InvestmentReport report) {
        if (report.getEvidenceItems() != null && !report.getEvidenceItems().isEmpty()) {
            return report.getEvidenceItems();
        }
        List<InvestmentReport.EvidenceItem> fallback = new ArrayList<>();
        for (String source : listOrFallback(report.getCitations(), evidenceBasis())) {
            fallback.add(InvestmentReport.EvidenceItem.builder()
                    .dimension("来源")
                    .evidence("本轮已参考该来源，但未生成逐条证据。")
                    .implication("结论需要结合上文工具观察和原始材料复核。")
                    .source(source)
                    .build());
        }
        return fallback;
    }

    /**
     * 返回非空列表或兜底列表。
     */
    private List<String> listOrFallback(List<String> items, List<String> fallback) {
        if (items == null || items.stream().noneMatch(item -> item != null && !item.isBlank())) {
            return fallback;
        }
        return items;
    }

    /**
     * 清理 Markdown 表格单元格文本。
     */
    String markdownCell(String value, String fallback) {
        return sanitizeUserFacingText(blankToDefault(value, fallback))
                .replace("\n", " ")
                .replace("|", "\\|")
                .trim();
    }

    /**
     * 归一化 BUY/OVERWEIGHT/HOLD/UNDERWEIGHT/SELL 五档评级标签。
     *
     * <p>评级从 3 档扩展到 5 档后，OVERWEIGHT 表达"偏多但有保留"，UNDERWEIGHT
     * 表达"偏空但不必离场"，HOLD 回归到真正中性的语义，避免被当成模糊回答的避风港。</p>
     */
    String normalizeRecommendationLabel(String recommendation) {
        return switch (blankToDefault(recommendation, "HOLD").toUpperCase(Locale.ROOT)) {
            case "BUY" -> "积极买入（BUY）";
            case "OVERWEIGHT" -> "偏积极（OVERWEIGHT）";
            case "UNDERWEIGHT" -> "偏谨慎（UNDERWEIGHT）";
            case "SELL" -> "明确卖出（SELL）";
            default -> "中性观察（HOLD）";
        };
    }

    /**
     * 将五档推荐标签转换成中文动作说明。
     */
    String recommendationAction(String recommendation) {
        return switch (blankToDefault(recommendation, "HOLD").toUpperCase(Locale.ROOT)) {
            case "BUY" -> "看多论据明显占优，可在风控范围内主动建仓或加仓，但仍需设定回撤止损。";
            case "OVERWEIGHT" -> "看多倾向略大于看空，可以小仓位或分批方式参与，等待关键风险逐步出清。";
            case "UNDERWEIGHT" -> "看空风险略大于看多，建议降低仓位或不加仓，已持有可分批减持锁定收益。";
            case "SELL" -> "看空风险明显占优，应优先回避或离场，等待估值、盈利或行业周期出现更清晰的安全边际。";
            default -> "多空证据基本平衡，先观察关键财报、指引和价格回撤，等证据更充分后再决定是否加仓。";
        };
    }

    /**
     * 返回默认证据来源说明。
     */
    private List<String> evidenceBasis() {
        return List.of(
                "结构化财务数据：收入、净利润、资产负债、现金流等年度指标",
                "行情与技术指标：近 60 日价格、成交量、均线、RSI、MACD",
                "知识库召回片段：可用财报、研报或已入库文档",
                "新闻与网页搜索：近期公司新闻、公告、行业事件和市场情绪"
        );
    }

    /**
     * 提取首段并限制长度。
     */
    private String firstParagraph(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "未生成有效论点。";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        int paragraphEnd = normalized.indexOf("。");
        if (paragraphEnd > 80) {
            normalized = normalized.substring(0, paragraphEnd + 1);
        }
        return PromptText.truncate(normalized, maxLength).replace("\n", " ");
    }

    /**
     * 空白字符串兜底。
     */
    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String shortHash(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
    }
}
