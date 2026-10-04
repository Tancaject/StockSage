package com.stocksage.service;

import com.stocksage.evidence.adapter.EvidenceEnvelopeMapper;

import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import com.stocksage.evidence.EvidenceModels.TargetIdentity;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.agent.ReadRequest;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.client.SecFinancialsResponse;
import com.stocksage.client.AShareFinancialsResponse;
import com.stocksage.client.HkFinancialsResponse;
import com.stocksage.evidence.EvidenceFreshness;
import com.stocksage.evidence.EvidenceLedger;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 单次普通请求的证据与展示预算；实例只属于当前调用，绝不跨请求共享。 */
final class OrdinaryEvidence {
    private final String ticker;
    private final ReadRequest request;
    private final TimeSensitivity timeSensitivity;
    private final ObjectMapper mapper;
    private final EvidenceEnvelopeMapper evidenceEnvelopeMapper;
    private final List<EvidenceEnvelope> evidence = new ArrayList<>();
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private final List<String> sections = new ArrayList<>();
    private int chars;
    private final Set<String> evolutionEvidenceTags = new HashSet<>();

    OrdinaryEvidence(String ticker, ReadRequest request, ObjectMapper mapper, EvidenceEnvelopeMapper evidenceEnvelopeMapper) {
        this(ticker, request, mapper, evidenceEnvelopeMapper, TimeSensitivity.UNSPECIFIED);
    }

    OrdinaryEvidence(String ticker, ReadRequest request, ObjectMapper mapper, EvidenceEnvelopeMapper evidenceEnvelopeMapper,
                     TimeSensitivity timeSensitivity) {
        this.ticker = ticker;
        this.request = request;
        this.timeSensitivity = timeSensitivity == null ? TimeSensitivity.UNSPECIFIED : timeSensitivity;
        this.mapper = mapper;
        this.evidenceEnvelopeMapper = evidenceEnvelopeMapper;
    }

    String call(String name, EvidenceDimension dimension, Supplier<String> call) {
        String raw;
        try { raw = call.get(); }
        catch (Exception error) { raw = "{\"error\":true,\"message\":\"取证调用失败，请稍后重试\"}"; }
        add(name, dimension, raw);
        return raw;
    }

    void add(com.stocksage.capability.CapabilityResult result) {
        String raw = result.content();
        if (result.status() == com.stocksage.capability.CapabilityResult.Status.TRUNCATED) {
            raw = "{\"error\":true,\"message\":\"能力结果已截断，不能作为完整证据\"}";
        } else {
            try {
                JsonNode root = mapper.readTree(raw);
                if (root instanceof ObjectNode object && !object.hasNonNull("provider")) {
                    object.put("provider", result.providerId());
                    raw = object.toString();
                }
            } catch (Exception ignored) { /* 统一由 add 的 JSON 验收记录失败。 */ }
        }
        add(result.capabilityId(), EvidenceDimension.NEWS, raw);
    }

    void add(String name, EvidenceDimension dimension, String raw) {
        EvidenceStatus status = evidenceEnvelopeMapper.inspectStatus(name, raw);
        String gap = "";
        JsonNode root = null;
        try {
            root = raw == null ? null : mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(raw);
            if (root != null && root.isObject() && !root.has("symbol") && root.has("code")) {
                // 数据服务的 A/H 股响应使用 code；共享证据解析器使用 symbol。
                ((ObjectNode) root).set("symbol", root.get("code"));
                raw = root.toString();
            }
            if (status == EvidenceStatus.AVAILABLE && name.equals("getIbkrHistoricalBars")
                    && (!request.period().equals(root.path("period").asText()) || !request.bar().equals(root.path("bar").asText()))) {
                gap = "PERIOD_MISMATCH";
            }
            if (status == EvidenceStatus.AVAILABLE && name.equals("getStockKLine")
                    && !request.klinePeriod().equals(root.path("period").asText())) gap = "PERIOD_MISMATCH";
            if (status == EvidenceStatus.AVAILABLE && KLinePayloadMapper.isKlineTool(name)
                    && new KLinePayloadMapper(mapper).toChartPayload(name, raw, new Object[0]).isEmpty()) gap = "BARS_MISSING";
            if (status == EvidenceStatus.AVAILABLE && name.equals("getTechnicalIndicators") && !request.bar().equals("1d")) {
                gap = "INDICATOR_GRANULARITY_UNSUPPORTED";
            }
            if (status == EvidenceStatus.AVAILABLE && name.equals("getFinancialReports")) {
                String responsePeriod = root.has("schemaVersion") && "HK".equals(root.path("market").asText())
                        ? root.path("requestedPeriod").asText() : root.path("period").asText();
                if (!request.reportPeriod().equals(responsePeriod)) gap = "REPORT_PERIOD_MISMATCH";
                if ("SEC_EDGAR".equals(root.path("provider").asText()) && root.has("schemaVersion")
                        && (!request.reportPeriod().equals(root.path("requestedPeriod").asText())
                        || request.reportYears() != root.path("requestedYears").asInt())) gap = "REPORT_REQUEST_MISMATCH";
                if (root.has("schemaVersion") && ("baostock".equals(root.path("provider").asText())
                        || "HK".equals(root.path("market").asText()))) {
                    if (request.reportYears() != root.path("requestedYears").asInt()
                            || !request.reportPeriod().equals(root.path("requestedPeriod").asText())) gap = "REPORT_REQUEST_MISMATCH";
                } else if (root.path("reports").isArray() && root.path("reports").size() < request.reportCount()) gap = "REPORT_COUNT_INCOMPLETE";
            }
        } catch (Exception error) { status = EvidenceStatus.FAILED; gap = "INVALID_TOOL_JSON"; }
        if (!gap.isBlank()) status = EvidenceStatus.EMPTY;
        if (status == EvidenceStatus.AVAILABLE && name.equals("getFinancialReports")
                && root.has("schemaVersion") && "baostock".equals(root.path("provider").asText())) {
            AShareFinancialsResponse response = AShareFinancialsResponse.fromJson(root);
            long completePeriods = response.reports().stream()
                    .filter(report -> report.status() == AShareFinancialsResponse.Status.SUCCESS)
                    .map(AShareFinancialsResponse.Report::asOf).distinct().count();
            if (response.status() == AShareFinancialsResponse.Status.PARTIAL) gap = "REPORT_TABLES_INCOMPLETE";
            else if (completePeriods < request.reportCount()) gap = "REPORT_COUNT_INCOMPLETE";
        }
        if (status == EvidenceStatus.AVAILABLE && name.equals("getFinancialReports") && !root.path("reports").isArray()) {
            if (root.has("schemaVersion") && "HK".equals(root.path("market").asText())) {
                gap = hkCoverageGap(HkFinancialsResponse.fromJson(root));
            } else {
                gap = "SEC_EDGAR".equals(root.path("provider").asText()) && root.has("schemaVersion")
                        ? secCoverageGap(SecFinancialsResponse.fromJson(root)) : "REPORT_COUNT_UNVERIFIED";
            }
        }
        EvidenceEnvelope item = evidenceEnvelopeMapper.map(dimension, ticker, name, raw == null ? "" : raw,
                status, Instant.now(), true);
        evidence.add(item);
        var freshness = EvidenceFreshness.assess(item, timeSensitivity, item.observedAt());
        if (item.hasUsableData() && gap.isBlank() && (freshness.status() == EvidenceFreshness.Status.UNKNOWN
                || freshness.status() == EvidenceFreshness.Status.STALE)) gap = freshness.reason();
        String id = "E" + evidence.size();
        boolean usable = item.hasProvenance() && (ticker == null || ticker.isBlank()
                || TargetIdentity.resolved(ticker).canonicalKey().equals(item.targetKey()));
        String data = usable && root != null ? compact(root) : "本项未取得符合请求的可引用数据。";
        if (EvidenceEnvelopeMapper.OMITTED_CONTEXT.equals(data)) {
            usable = false;
            gap = "CONTEXT_BUDGET";
            data = "搜索条目的来源与时间信息超出上下文预算，本项未提供可引用内容。";
        }
        boolean reduced = usable && root != null && root.toString().length() > 2200;
        String header = "[" + id + "] " + name + "\nstatus=" + item.status() + "; gap=" + gap
                + "\nsource=" + item.sourceRef() + "; provider=" + item.provider() + "; asOf=" + item.asOf()
                + "\ntimeSensitivity=" + timeSensitivity + "; timing=" + (item.timing() == null ? "UNKNOWN" : item.timing())
                + "\nfreshness=" + freshness.status() + "; reason=" + freshness.reason() + "\n";
        String section = header + data + "\n";
        boolean included = chars + section.length() <= 11000;
        if (included) { sections.add(section); chars += section.length(); }
        if (name.equals("getFinancialReports") && item.status() == EvidenceStatus.AVAILABLE
                && gap.isBlank() && usable && included && !reduced) {
            evolutionEvidenceTags.add("financial-evidence");
            // Only the typed report contracts with coverage checks establish comparable dated values.
            if (root.has("schemaVersion") && ("SEC_EDGAR".equals(root.path("provider").asText())
                    || "baostock".equals(root.path("provider").asText()) || "HK".equals(root.path("market").asText()))) {
                evolutionEvidenceTags.add("dated-values");
                evolutionEvidenceTags.add("structured-financials");
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("tool", name); row.put("status", item.status().name());
        row.put("target", item.targetKey()); row.put("source", item.sourceRef());
        row.put("provider", item.provider()); row.put("asOf", item.asOf() == null ? "" : item.asOf().toString());
        row.put("observedAt", item.observedAt() == null ? "" : item.observedAt().toString());
        row.put("timeSensitivity", timeSensitivity.name());
        row.put("timing", item.timing() == null ? "UNKNOWN" : item.timing());
        row.put("freshnessStatus", freshness.status().name());
        row.put("freshnessReason", freshness.reason());
        row.put("gap", gap); row.put("included", included); row.put("chars", included ? section.length() : 0);
        row.put("reduced", reduced);
        row.put("citable", usable && included); row.put("droppedReason", included ? "" : "CONTEXT_BUDGET");
        observations.add(Map.copyOf(row));
    }

    /** 不同指标数量和同期间重复事实均不能冒充多年度覆盖。 */
    private String secCoverageGap(SecFinancialsResponse response) {
        Set<String> commonEnds = null;
        for (var metric : response.metrics().values()) {
            Set<String> ends = new HashSet<>();
            metric.data().forEach(fact -> ends.add(fact.end()));
            if (ends.size() < request.reportCount()) return "REPORT_COUNT_INCOMPLETE";
            if (commonEnds == null) commonEnds = new HashSet<>(ends);
            else commonEnds.retainAll(ends);
        }
        return commonEnds == null || commonEnds.size() < request.reportCount() ? "REPORT_PERIODS_UNALIGNED" : "";
    }

    private String hkCoverageGap(HkFinancialsResponse response) {
        if (response.status() == HkFinancialsResponse.Status.PARTIAL) return "REPORT_TABLES_INCOMPLETE";
        // 港股报告期可能是中期或累计值，不能以足够条数冒充用户要求的独立季度。
        if (request.reportPeriod().equals("quarterly")) return "REPORT_QUARTER_BASIS_UNVERIFIED";
        Set<String> common = null;
        for (var table : response.statements().all()) {
            Set<String> dates = new HashSet<>();
            table.data().stream().filter(row -> row.hasValues()).forEach(row -> dates.add(row.reportDate()));
            if (dates.size() < request.reportCount()) return "REPORT_COUNT_INCOMPLETE";
            if (common == null) common = dates;
            else common.retainAll(dates);
        }
        Set<String> indicatorDates = new HashSet<>();
        response.indicators().data().stream().filter(row -> row.hasValues())
                .forEach(row -> indicatorDates.add(row.reportDate()));
        if (indicatorDates.size() < request.reportCount()) return "REPORT_COUNT_INCOMPLETE";
        if (common != null) common.retainAll(indicatorDates);
        return common == null || common.size() < request.reportCount() ? "REPORT_PERIODS_UNALIGNED" : "";
    }

    /** JSON 按完整字段/记录缩减；K 线点集由已有图表事件单独提供。 */
    private String compact(JsonNode root) {
        if (root.toString().length() <= 2200) return root.toString();
        if (!root.isObject()) return "数据较长，本次正文未纳入完整结果，不能据此断言已经完整回答。";
        if (root.has("schemaVersion") && root.has("searchType")) {
            return evidenceEnvelopeMapper.compactStructuredContext("searchNews", root.toString(), 2200);
        }
        if (root.has("schemaVersion") && "HK".equals(root.path("market").asText())
                && "akshare".equals(root.path("provider").asText()) && root.has("statements")) {
            return evidenceEnvelopeMapper.compactStructuredContext("getFinancialReports", root.toString(), 2200);
        }
        ObjectNode selected = mapper.createObjectNode();
        for (String key : List.of("schemaVersion", "status", "symbol", "ticker", "cik", "code", "resolvedCode", "market",
                "period", "requestedPeriod", "requestedYears", "bar", "currency", "volumeUnit", "adjustment", "valueScale", "valueEncoding", "aggregationBasis", "timeKind", "asOf", "as_of",
                "fetchedAt", "provider", "source", "conid", "requestedOutsideRth", "delayed",
                "count", "errorCode", "retryable", "message")) {
            if (root.has(key) && root.get(key).toString().length() <= 350) selected.set(key, root.get(key));
        }
        if (root.path("historyMetadata").isObject()) {
            ObjectNode metadata = mapper.createObjectNode();
            for (String key : List.of("priceFactor", "volumeFactor", "mdAvailability", "mktDataDelay",
                    "outsideRth", "barLength", "timePeriod")) {
                JsonNode value = root.path("historyMetadata").get(key);
                if (value != null && value.toString().length() <= 100) metadata.set(key, value);
            }
            selected.set("historyMetadata", metadata);
        }
        if (root.has("schemaVersion") && "SEC_EDGAR".equals(root.path("provider").asText())) {
            // 一条财务数值必须连同单位、概念、期间和来源保留；不能只留下指标计数。
            ObjectNode metrics = selected.putObject("metrics");
            var sourceMetrics = root.path("metrics").fields();
            while (sourceMetrics.hasNext()) {
                var entry = sourceMetrics.next();
                JsonNode latest = null;
                for (JsonNode fact : entry.getValue().path("data")) {
                    if (latest == null || fact.path("end").asText().compareTo(latest.path("end").asText()) > 0) latest = fact;
                }
                if (latest == null) continue;
                ObjectNode metric = mapper.createObjectNode();
                metric.set("concept", entry.getValue().get("concept"));
                metric.set("unit", entry.getValue().get("unit"));
                metric.putArray("data").add(latest);
                metrics.set(entry.getKey(), metric);
                if (selected.toString().length() > 2150) metrics.remove(entry.getKey());
            }
            selected.put("contextReduced", true);
            return selected.toString();
        }
        if (root.has("schemaVersion") && "baostock".equals(root.path("provider").asText()) && root.path("reports").isArray()) {
            var reports = selected.putArray("reports");
            List<JsonNode> ordered = new ArrayList<>();
            root.path("reports").forEach(ordered::add);
            ordered.sort(Comparator.comparing((JsonNode report) -> report.path("asOf").asText("")).reversed());
            for (JsonNode sourceReport : ordered) {
                ObjectNode report = mapper.createObjectNode();
                for (String field : List.of("year", "quarter", "status", "asOf")) report.set(field, sourceReport.get(field));
                ObjectNode statements = report.putObject("statements");
                reports.add(report);
                var fields = sourceReport.path("statements").fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    // 保留整张摘要及其来源、日期和单位，不能切断某个数值的解释边界。
                    statements.set(field.getKey(), field.getValue());
                    if (selected.toString().length() > 2150) statements.remove(field.getKey());
                }
                if (statements.isEmpty()) reports.remove(reports.size() - 1);
            }
            selected.put("contextReduced", true);
            return selected.toString();
        }
        var fields = root.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (selected.has(field.getKey())) continue;
            JsonNode value = field.getValue();
            if (value.isArray()) {
                var rows = mapper.createArrayNode();
                for (int i = 0; i < value.size(); i++) {
                    if ((i < 2 || i >= value.size() - 2) && rows.toString().length() + value.get(i).toString().length() < 1400) rows.add(value.get(i));
                }
                if (!rows.isEmpty() && selected.toString().length() + rows.toString().length() < 2200) selected.set(field.getKey(), rows);
            } else if (selected.toString().length() + value.toString().length() < 2200) selected.set(field.getKey(), value);
        }
        selected.put("contextReduced", true);
        return selected.toString();
    }

    EvidenceLedger ledger() { return new EvidenceLedger(TargetIdentity.resolved(ticker), evidence); }
    Set<String> evolutionEvidenceTags() { return Set.copyOf(evolutionEvidenceTags); }
    Set<String> evolutionTaskTags() {
        Set<String> tags = new HashSet<>(Set.of("fundamentals", request.reportPeriod()));
        if (request.reportCount() > 1 && evolutionEvidenceTags.contains("dated-values")) tags.add("period-comparison");
        return Set.copyOf(tags);
    }
    List<Map<String, Object>> observations() { return List.copyOf(observations); }
    List<String> citationIds() { return observations.stream().filter(row -> Boolean.TRUE.equals(row.get("citable"))).map(row -> row.get("id").toString()).toList(); }
    boolean allIncluded() { return observations.stream().allMatch(row -> Boolean.TRUE.equals(row.get("included"))
            && !Boolean.TRUE.equals(row.get("reduced")) && "".equals(row.get("gap"))); }
    boolean hasUsefulResult() { return evidence.stream().anyMatch(item -> item.hasProvenance() || (item.status() == EvidenceStatus.NO_RESULTS && !item.provider().isBlank())); }
    String context() { return String.join("\n", sections); }
}
