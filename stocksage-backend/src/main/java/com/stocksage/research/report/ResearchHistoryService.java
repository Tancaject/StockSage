package com.stocksage.research.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.repository.ResearchMemoryEntryRepository;
import com.stocksage.repository.UserProfileRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户自己的研究存档；只比较已保存内容，不重新生成评级或把历史结论升级为事实。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResearchHistoryService {
    private final JdbcTemplate jdbc;
    private final InvestmentReportVersionService reports;
    private final ResearchMemoryEntryRepository memories;
    private final UserProfileRepository profiles;
    private final ObjectMapper json;

    public ObjectNode listReports(String userId, String ticker, Instant asOf, int limit) {
        requireUser(userId);
        int capped = limit(limit);
        var ids = reportIds(userId, ticker, asOf, capped + 1);
        var data = json.createObjectNode().put("hasMore", ids.size() > capped);
        var rows = data.putArray("reports");
        for (Long id : ids.subList(0, Math.min(capped, ids.size()))) {
            LoadedReport report = loadReport(userId, id, asOf);
            rows.add(metadata(report));
        }
        return response(rows.isEmpty() ? "EMPTY" : "SUCCESS", asOf, data, List.of());
    }

    public ObjectNode readReport(String userId, long reportId, Instant asOf) {
        LoadedReport loaded = loadReport(userId, reportId, asOf);
        verifyReportSnapshot(userId, loaded, asOf);
        ObjectNode data = json.createObjectNode();
        data.set("metadata", metadata(loaded));
        ObjectNode report = json.valueToTree(loaded.detail().report());
        // 旧 JSON 损坏时原服务会生成兼容对象；生成时点仍以存档列为准，不能写成读取时刻。
        report.put("generatedAt", timestamp(loaded.detail().summary().generatedAt()));
        data.set("report", report);
        data.set("review", reviewAt(loaded.detail(), asOf));
        data.set("researchMemory", memoryAt(userId, reportId, asOf));
        return response("SUCCESS", asOf, data, loaded.snapshotId().isBlank() ? List.of("SNAPSHOT_NOT_RETAINED") : List.of());
    }

    public ObjectNode readSnapshot(String userId, String snapshotId, Instant asOf) {
        LoadedSnapshot snapshot = loadSnapshot(userId, snapshotId, asOf);
        String issue = snapshotTimeIssue(snapshot.payload(), asOf);
        ObjectNode data = snapshotMetadata(snapshot);
        if (issue.isBlank()) data.set("payload", snapshot.payload());
        else data.put("contentWithheld", true);
        data.put("publicationTimesComplete", publicationTimesComplete(snapshot.payload()));
        return response(issue.isBlank() ? "SUCCESS" : "PARTIAL", asOf, data,
                issue.isBlank() ? List.of() : List.of(issue));
    }

    public ObjectNode compareReports(String userId, long olderId, long newerId, Instant asOf) {
        return response("SUCCESS", asOf, comparison(userId, olderId, newerId, asOf), List.of());
    }

    public ObjectNode reviewThesis(String userId, long reportId, String newSnapshotId, Instant asOf) {
        LoadedReport old = loadReport(userId, reportId, asOf);
        verifyReportSnapshot(userId, old, asOf);
        LoadedSnapshot newer = loadSnapshot(userId, newSnapshotId, asOf);
        if (!old.detail().summary().ticker().equalsIgnoreCase(newer.payload().path("ticker").asText())) {
            throw new HistoryReadException("TARGET_MISMATCH", "旧报告与新快照的标的不同，请选择同一标的。");
        }
        if (!newer.createdAt().isAfter(old.createdAt())) {
            throw new HistoryReadException("SNAPSHOT_NOT_NEWER", "所选快照没有晚于旧报告，请选择后续研究的证据快照。");
        }
        String issue = snapshotTimeIssue(newer.payload(), asOf);
        if (!issue.isBlank()) throw new HistoryReadException(issue, "新快照的时间信息不满足截止时点，不能用于本次复查。");
        var data = json.createObjectNode().put("assessment", "REQUIRES_VERIFICATION")
                .put("interpretation", "旧论点与后续存档证据并列；尚未判断支持、反驳或成立。");
        data.set("oldReport", metadata(old));
        var theses = data.putObject("oldTheses");
        JsonNode report = json.valueToTree(old.detail().report());
        for (String field : List.of("rationale", "bullFactors", "bearFactors", "riskFactors", "unknowns")) theses.set(field, report.path(field));
        data.set("oldReview", reviewAt(old.detail(), asOf));
        data.set("researchMemory", memoryAt(userId, reportId, asOf));
        var evidence = snapshotMetadata(newer);
        evidence.set("payload", newer.payload());
        data.set("newSnapshotEvidence", evidence);
        return response("SUCCESS", asOf, data, List.of());
    }

    public ObjectNode watchlistChanges(String userId, Instant since, Instant asOf, int requestedLimit) {
        requireUser(userId);
        if (!since.isBefore(asOf)) throw new HistoryReadException("INVALID_TIME_RANGE", "since 必须早于 asOf。");
        var profile = profiles.findById(userId);
        var data = json.createObjectNode().put("membershipBasis", "SAVED_WATCHLIST_AT_CUTOFF")
                .put("membershipChangesAvailable", false).put("since", since.toString());
        var rows = data.putArray("changes");
        if (profile.isEmpty()) return response("EMPTY", asOf, data, List.of());
        if (profile.get().getUpdatedAt() == null || instant(profile.get().getUpdatedAt()).isAfter(asOf)) {
            return response("PARTIAL", asOf, data, List.of("WATCHLIST_MEMBERSHIP_NOT_ARCHIVED_AT_CUTOFF"));
        }
        JsonNode saved = parse(profile.get().getWatchList());
        if (!saved.isArray()) throw new HistoryReadException("INVALID_SAVED_WATCHLIST", "已保存的自选名单格式无效，请在工作台检查名单。");
        List<String> savedTickers = new ArrayList<>();
        saved.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) savedTickers.add(normalizeTicker(value.asText())); });
        List<String> tickers = savedTickers.stream().distinct().toList();
        int capped = limit(requestedLimit);
        data.put("watchlistSavedAt", timestamp(profile.get().getUpdatedAt())).put("hasMore", tickers.size() > capped);
        for (String ticker : tickers.subList(0, Math.min(capped, tickers.size()))) {
            var row = rows.addObject().put("ticker", ticker);
            List<Long> latest = reportIds(userId, ticker, asOf, 1);
            List<Long> baseline = reportIds(userId, ticker, since, 1);
            if (latest.isEmpty() || !baseline.isEmpty() && latest.get(0).equals(baseline.get(0))) {
                row.put("status", "NO_NEW_RESEARCH");
            } else if (baseline.isEmpty()) {
                row.put("status", "NEW_RESEARCH");
                row.set("latest", metadata(loadReport(userId, latest.get(0), asOf)));
            } else {
                row.put("status", "RESEARCH_CHANGED");
                row.set("comparison", comparison(userId, baseline.get(0), latest.get(0), asOf));
            }
        }
        return response(rows.isEmpty() ? "EMPTY" : "SUCCESS", asOf, data, List.of());
    }

    private ObjectNode comparison(String userId, long olderId, long newerId, Instant asOf) {
        LoadedReport before = loadReport(userId, olderId, asOf);
        LoadedReport after = loadReport(userId, newerId, asOf);
        if (!before.detail().summary().ticker().equals(after.detail().summary().ticker())) {
            throw new HistoryReadException("TARGET_MISMATCH", "两份报告的标的不同，请选择同一标的的历史版本。");
        }
        if (before.createdAt().isAfter(after.createdAt())) {
            throw new HistoryReadException("REPORT_ORDER_REVERSED", "旧报告晚于新报告，请交换报告 ID。");
        }
        verifyReportSnapshot(userId, before, asOf);
        verifyReportSnapshot(userId, after, asOf);
        var data = json.createObjectNode().put("comparisonBasis", "EXACT_STORED_CONTENT")
                .put("assessment", "REQUIRES_VERIFICATION")
                .put("interpretation", "增删仅表示存档内容变化，不代表事实成立或投资建议正确。");
        data.set("before", metadata(before)); data.set("after", metadata(after));
        data.putObject("rating").put("before", before.detail().report().getRecommendation())
                .put("after", after.detail().report().getRecommendation());
        JsonNode left = json.valueToTree(before.detail().report());
        JsonNode right = json.valueToTree(after.detail().report());
        var claims = data.putObject("claimChanges");
        for (String field : List.of("rationale", "bullFactors", "bearFactors", "riskFactors", "unknowns")) {
            claims.set(field, difference(left.path(field), right.path(field), false));
        }
        data.set("reportedEvidenceChanges", difference(left.path("evidenceItems"), right.path("evidenceItems"), true));
        if (!before.snapshotId().isBlank() && !after.snapshotId().isBlank()) {
            JsonNode oldLedger = loadSnapshot(userId, before.snapshotId(), asOf).payload().path("evidenceLedger").path("evidence");
            JsonNode newLedger = loadSnapshot(userId, after.snapshotId(), asOf).payload().path("evidenceLedger").path("evidence");
            data.set("sourceEvidenceChanges", difference(oldLedger, newLedger, true));
        } else data.put("sourceEvidenceChangesStatus", "SNAPSHOT_NOT_RETAINED");
        return data;
    }

    private List<Long> reportIds(String userId, String ticker, Instant asOf, int limit) {
        String normalized = normalizeTicker(ticker);
        return jdbc.queryForList("""
                SELECT id FROM investment_report_versions
                WHERE user_id = ? AND (? = '' OR ticker = ?) AND created_at <= ?
                  AND (generated_at IS NULL OR generated_at <= ?)
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, Long.class, userId, normalized, normalized, sqlTime(asOf), sqlTime(asOf), limit);
    }

    private LoadedReport loadReport(String userId, long id, Instant asOf) {
        requireUser(userId);
        var stored = jdbc.query("""
                SELECT created_at, producer_evidence_snapshot_id FROM investment_report_versions
                WHERE id = ? AND user_id = ? AND created_at <= ?
                  AND (generated_at IS NULL OR generated_at <= ?)
                """, (rs, row) -> new ReportOrigin(rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getString("producer_evidence_snapshot_id")), id, userId, sqlTime(asOf), sqlTime(asOf));
        if (stored.isEmpty()) throw notFound();
        var origin = stored.get(0);
        return new LoadedReport(reports.getReportDetail(userId, id), instant(origin.createdAt()),
                origin.snapshotId() == null ? "" : origin.snapshotId());
    }

    private LoadedSnapshot loadSnapshot(String userId, String id, Instant asOf) {
        requireUser(userId);
        if (id == null || id.isBlank()) throw notFound();
        return jdbc.query("""
                SELECT s.id, s.created_at, s.payload_hash, s.payload_json
                FROM research_evidence_snapshots s JOIN research_tasks t ON t.id = s.run_id
                WHERE s.id = ? AND t.user_id = ? AND s.created_at <= ?
                """, (rs, row) -> new LoadedSnapshot(rs.getString("id"), instant(rs.getTimestamp("created_at").toLocalDateTime()),
                        rs.getString("payload_hash"), parse(rs.getString("payload_json"))), id, userId, sqlTime(asOf))
                .stream().findFirst().orElseThrow(ResearchHistoryService::notFound);
    }

    private void verifyReportSnapshot(String userId, LoadedReport report, Instant asOf) {
        if (report.snapshotId().isBlank()) return;
        var snapshot = loadSnapshot(userId, report.snapshotId(), asOf);
        if (!report.detail().summary().ticker().equalsIgnoreCase(snapshot.payload().path("ticker").asText())
                || snapshot.createdAt().isAfter(report.createdAt())) {
            throw new HistoryReadException("REPORT_SNAPSHOT_MISMATCH", "报告与原始快照的标的或创建顺序不一致，未提供报告正文。");
        }
        String issue = snapshotTimeIssue(snapshot.payload(), asOf);
        if (!issue.isBlank()) throw new HistoryReadException(issue, "该报告的证据时间不满足截止时点，未提供报告正文。");
    }

    /** 原快照正文混合多个证据段；任一已知时间越界时整份正文不返回，避免只删元数据而泄漏事实。 */
    private String snapshotTimeIssue(JsonNode payload, Instant asOf) {
        if (!payload.isObject() || !payload.path("evidenceLedger").path("evidence").isArray()) return "SNAPSHOT_TIME_UNVERIFIED";
        for (JsonNode evidence : payload.path("evidenceLedger").path("evidence")) {
            if (!evidence.path("observedAt").isTextual()) return "OBSERVATION_TIME_UNKNOWN";
            if (afterInstant(evidence.path("observedAt"), asOf)) return "OBSERVATION_AFTER_AS_OF";
            if (afterInstant(evidence.path("asOf"), asOf)) return "EVIDENCE_AFTER_AS_OF";
            JsonNode timing = evidence.path("timing");
            if (afterInstant(timing.path("fetchedAt"), asOf)) return "FETCH_AFTER_AS_OF";
            JsonNode financial = timing.path("financial");
            ZoneId marketZone = "US".equals(financial.path("market").asText()) ? ZoneId.of("America/New_York") : ZoneId.of("Asia/Shanghai");
            LocalDate localDate = asOf.atZone(marketZone).toLocalDate();
            if (afterDate(financial.path("latestDisclosureDate"), localDate)
                    || afterDate(financial.path("latestFiledReport"), localDate)) return "DISCLOSURE_AFTER_AS_OF";
            JsonNode search = timing.path("search");
            if (afterInstant(search.path("latestPublishedAt"), asOf)
                    || afterDate(search.path("latestPublishedDate"), asOf.atZone(ZoneId.of("UTC")).toLocalDate())) return "PUBLICATION_AFTER_AS_OF";
        }
        return "";
    }

    private boolean publicationTimesComplete(JsonNode payload) {
        for (JsonNode evidence : payload.path("evidenceLedger").path("evidence")) {
            JsonNode timing = evidence.path("timing");
            if (timing.isMissingNode() || timing.isNull()) return false;
            if (timing.path("search").path("unknownCount").asInt() > 0) return false;
            if (timing.path("financial").isObject() && !timing.path("financial").path("latestDisclosureDate").isTextual()) return false;
        }
        return true;
    }

    private ObjectNode difference(JsonNode before, JsonNode after, boolean evidence) {
        Map<String, JsonNode> left = indexed(before, evidence), right = indexed(after, evidence);
        ObjectNode result = json.createObjectNode();
        ArrayNode added = result.putArray("added"), removed = result.putArray("removed");
        right.forEach((key, value) -> { if (!left.containsKey(key)) added.add(value); });
        left.forEach((key, value) -> { if (!right.containsKey(key)) removed.add(value); });
        return result;
    }

    private Map<String, JsonNode> indexed(JsonNode values, boolean evidence) {
        Map<String, JsonNode> index = new LinkedHashMap<>();
        if (!values.isArray()) return index;
        for (JsonNode value : values) {
            String key;
            if (!evidence) key = value.asText().strip().replaceAll("\\s+", " ");
            else {
                var identity = json.createArrayNode();
                // E1 等仅在单轮内稳定；比较来源与原始内容哈希，不把重新编号当成事实变化。
                for (String field : value.has("payloadHash")
                        ? List.of("dimension", "capabilityId", "targetKey", "sourceRef", "provider", "payloadHash", "status")
                        : List.of("dimension", "evidence", "source")) identity.add(value.path(field).asText().strip().replaceAll("\\s+", " "));
                key = identity.toString();
            }
            index.putIfAbsent(key, value);
        }
        return index;
    }

    private ObjectNode reviewAt(InvestmentReportVersionService.ReportDetail detail, Instant asOf) {
        var visible = detail.reviewHistory().stream().filter(review -> review.createdAt() != null && !instant(review.createdAt()).isAfter(asOf))
                .sorted(Comparator.comparing(InvestmentReportReviewSummary::createdAt).thenComparing(InvestmentReportReviewSummary::id)).toList();
        var result = json.createObjectNode().put("status", detail.summary().reviewStatus()
                == com.stocksage.model.entity.InvestmentReportVersion.ReviewStatus.DRAFT ? "DRAFT" : "UNKNOWN_AT_CUTOFF");
        if (!visible.isEmpty()) {
            var last = visible.get(visible.size() - 1);
            result.put("status", last.toStatus().name()).put("comment", last.comment()).put("reviewedAt", timestamp(last.createdAt()));
        }
        var history = result.putArray("history");
        for (var review : visible) history.addObject().put("from", review.fromStatus().name()).put("to", review.toStatus().name())
                .put("comment", review.comment()).put("createdAt", timestamp(review.createdAt()));
        return result;
    }

    private ObjectNode memoryAt(String userId, long reportId, Instant asOf) {
        var result = json.createObjectNode().put("knowledgeKind", "HISTORICAL_GENERATED_CONCLUSION").put("currentEvidence", false);
        var memory = memories.findByUserIdAndSourceTypeAndSourceId(userId, "INVESTMENT_REPORT_VERSION", Long.toString(reportId));
        if (memory.isEmpty() || memory.get().getCreatedAt() == null || instant(memory.get().getCreatedAt()).isAfter(asOf)) return result.put("status", "NOT_AVAILABLE");
        var stored = memory.get();
        if (stored.getUpdatedAt() == null || instant(stored.getUpdatedAt()).isAfter(asOf)) return result.put("status", "STATE_NOT_ARCHIVED_AT_CUTOFF");
        return result.put("status", stored.active() ? String.valueOf(stored.getResolutionStatus()) : "REVOKED")
                .put("sourceReportId", reportId).put("createdAt", timestamp(stored.getCreatedAt()));
    }

    private ObjectNode metadata(LoadedReport loaded) {
        var summary = loaded.detail().summary();
        return json.createObjectNode().put("reportId", summary.id()).put("ticker", summary.ticker())
                .put("version", summary.reportVersion()).put("recommendation", summary.recommendation())
                .put("createdAt", loaded.createdAt().toString()).put("generatedAt", timestamp(summary.generatedAt()))
                .put("snapshotId", loaded.snapshotId()).put("dataSnapshotHash", summary.dataSnapshotHash())
                .put("qualityStatus", String.valueOf(loaded.detail().report().getQualityStatus())).put("query", summary.userQuery());
    }

    private ObjectNode snapshotMetadata(LoadedSnapshot snapshot) {
        return json.createObjectNode().put("snapshotId", snapshot.id()).put("createdAt", snapshot.createdAt().toString())
                .put("payloadHash", snapshot.payloadHash()).put("ticker", snapshot.payload().path("ticker").asText());
    }

    private ObjectNode response(String status, Instant asOf, JsonNode data, List<String> gaps) {
        var result = json.createObjectNode().put("schemaVersion", 1).put("provider", "STOCKSAGE_RESEARCH_HISTORY")
                .put("status", status).put("asOf", asOf.toString()).put("fetchedAt", Instant.now().toString())
                .put("localTimestampZone", ZoneId.systemDefault().getId()).put("currentEvidence", false)
                .put("timestampZoneBasis", "CURRENT_JVM_DEFAULT; legacy DATETIME values do not retain their original offset")
                .put("timeBasis", "ARCHIVE_CREATED_AND_RECORDED_PUBLICATION_TIMES");
        result.set("data", data); result.set("gaps", json.valueToTree(gaps));
        return result;
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (Exception invalid) { throw new HistoryReadException("INVALID_ARCHIVE_JSON", "存档内容无法解析，请检查原报告或快照。"); }
    }
    private static boolean afterInstant(JsonNode value, Instant asOf) { return value.isTextual() && Instant.parse(value.asText()).isAfter(asOf); }
    private static boolean afterDate(JsonNode value, LocalDate asOf) { return value.isTextual() && LocalDate.parse(value.asText()).isAfter(asOf); }
    private static Timestamp sqlTime(Instant value) { return Timestamp.valueOf(LocalDateTime.ofInstant(value, ZoneId.systemDefault())); }
    private static Instant instant(LocalDateTime value) { return value.atZone(ZoneId.systemDefault()).toInstant(); }
    private static String timestamp(LocalDateTime value) { return value == null ? null : instant(value).toString(); }
    private static String normalizeTicker(String ticker) { return ticker == null ? "" : ticker.strip().toUpperCase(Locale.ROOT); }
    private static int limit(int value) { return value <= 0 ? 20 : Math.min(value, 50); }
    private static void requireUser(String userId) { if (userId == null || userId.isBlank()) throw new HistoryReadException("AUTH_REQUIRED", "缺少可信用户身份，请重新登录后读取研究历史。"); }
    private static HistoryReadException notFound() { return new HistoryReadException("NOT_FOUND", "在该截止时点没有可读取的报告或快照，请检查 ID 和时间。"); }
    private record ReportOrigin(LocalDateTime createdAt, String snapshotId) { }
    private record LoadedReport(InvestmentReportVersionService.ReportDetail detail, Instant createdAt, String snapshotId) { }
    private record LoadedSnapshot(String id, Instant createdAt, String payloadHash, JsonNode payload) { }

    public static class HistoryReadException extends IllegalArgumentException {
        private final String code;
        public HistoryReadException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
