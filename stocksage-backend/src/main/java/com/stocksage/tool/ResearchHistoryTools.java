package com.stocksage.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.research.report.ResearchHistoryService;
import com.stocksage.research.report.ResearchHistoryService.HistoryReadException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/** 本人报告、证据存档和研究变化；所有入口只读，不产生新的投资判断。 */
@Component
@RequiredArgsConstructor
public class ResearchHistoryTools {
    private final ResearchHistoryService history;
    private final ToolUserIdentity identity;
    private final ObjectMapper json;

    @Tool(description = "列出当前用户自己的历史研究报告及版本、评级、创建时间和证据快照ID。历史生成结论不是当前证据；asOf按实际存档时间筛选。")
    public String listResearchHistory(@ToolParam(description = "可选证券代码；空值列出本人全部标的") String ticker,
            @ToolParam(description = "截止时点，带时区的ISO时间，例如2026-10-20T12:00:00Z；空值为当前时间") String asOf,
            @ToolParam(description = "条数，默认20，最多50") int limit) {
        return read(asOf, cutoff -> history.listReports(identity.currentUserId(), ticker, cutoff, limit));
    }

    @Tool(description = "读取当前用户自己的历史报告正文、当时可见的人工审核和研究记忆状态。只能引用为历史结论，不能当作当前事实。")
    public String readResearchReport(@ToolParam(description = "listResearchHistory返回的报告ID") long reportId,
            @ToolParam(description = "带时区ISO截止时点；空值为当前时间") String asOf) {
        return read(asOf, cutoff -> history.readReport(identity.currentUserId(), reportId, cutoff));
    }

    @Tool(description = "读取当前用户自己的原始研究证据快照；同时核对存档创建、观察和已记录公告时间。时间越界时隐藏正文，不提供未来资料。")
    public String readResearchSnapshot(@ToolParam(description = "报告元数据返回的snapshotId") String snapshotId,
            @ToolParam(description = "带时区ISO截止时点；空值为当前时间") String asOf) {
        return read(asOf, cutoff -> history.readSnapshot(identity.currentUserId(), snapshotId, cutoff));
    }

    @Tool(description = "比较本人同一标的两份历史报告的评级、论点、风险及证据增删。精确比较存档文本，不推断新论点成立或评级正确。")
    public String compareResearchReports(@ToolParam(description = "较早报告ID") long olderReportId,
            @ToolParam(description = "较新报告ID") long newerReportId,
            @ToolParam(description = "带时区ISO截止时点；空值为当前时间") String asOf) {
        return read(asOf, cutoff -> history.compareReports(identity.currentUserId(), olderReportId, newerReportId, cutoff));
    }

    @Tool(description = "为投资逻辑复查并列展示本人旧报告论点和同一标的后续证据快照，固定标为待核验；不会自动断言论点仍成立或已失效。")
    public String reviewInvestmentThesis(@ToolParam(description = "需要复查的旧报告ID") long reportId,
            @ToolParam(description = "同一标的、创建晚于旧报告的新证据快照ID") String newSnapshotId,
            @ToolParam(description = "带时区ISO截止时点；空值为当前时间") String asOf) {
        return read(asOf, cutoff -> history.reviewThesis(identity.currentUserId(), reportId, newSnapshotId, cutoff));
    }

    @Tool(description = "读取本人已保存自选股在一段时间里的研究变化，包括新报告和评级/论点/证据变化。自选名单无增删历史，不能据此还原历史成员或市场实时变化。")
    public String getWatchlistResearchChanges(@ToolParam(description = "起始时点，必填带时区ISO时间") String since,
            @ToolParam(description = "截止时点，带时区ISO时间；空值为当前时间") String asOf,
            @ToolParam(description = "最多处理的自选标的数，默认20，最多50") int limit) {
        return read(asOf, cutoff -> {
            if (since == null || since.isBlank()) throw new HistoryReadException("SINCE_REQUIRED", "请提供需要比较的起始时点 since。");
            return history.watchlistChanges(identity.currentUserId(), Instant.parse(since), cutoff, limit);
        });
    }

    private String read(String asOf, Function<Instant, ObjectNode> query) {
        try {
            Instant now = Instant.now();
            Instant cutoff = asOf == null || asOf.isBlank() ? now : Instant.parse(asOf);
            if (cutoff.isAfter(now)) throw new HistoryReadException("FUTURE_AS_OF", "截止时点不能晚于当前时间。");
            return query.apply(cutoff).toString();
        } catch (AuthenticationCredentialsNotFoundException unavailable) {
            return error("AUTH_REQUIRED", "缺少一致的可信用户身份，请重新登录或从当前会话读取。");
        } catch (DateTimeParseException invalid) {
            return error("INVALID_TIME", "时间需使用带时区的ISO格式，例如2026-10-20T12:00:00Z。");
        } catch (HistoryReadException unavailable) {
            return error(unavailable.code(), unavailable.getMessage());
        } catch (com.stocksage.exception.ResourceNotFoundException missing) {
            return error("NOT_FOUND", "在该截止时点没有可读取的本人报告或快照。");
        } catch (RuntimeException failed) {
            return error("HISTORY_READ_FAILED", "研究存档读取失败，请稍后重试或检查数据库连接。");
        }
    }

    private String error(String code, String message) {
        return json.createObjectNode().put("schemaVersion", 1).put("provider", "STOCKSAGE_RESEARCH_HISTORY")
                .put("status", "ERROR").put("currentEvidence", false).put("error", true)
                .put("errorCode", code).put("message", message).put("fetchedAt", Instant.now().toString()).toString();
    }
}
