package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.agent.ReadRequest;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 单次普通请求的证据与展示预算；实例只属于当前调用，绝不跨请求共享。 */
final class OrdinaryEvidence {
    private final String ticker;
    private final ReadRequest request;
    private final ObjectMapper mapper;
    private final EvidenceEnvelopeMapper evidenceEnvelopeMapper;
    private final List<EvidenceEnvelope> evidence = new ArrayList<>();
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private final List<String> sections = new ArrayList<>();
    private int chars;

    OrdinaryEvidence(String ticker, ReadRequest request, ObjectMapper mapper, EvidenceEnvelopeMapper evidenceEnvelopeMapper) {
        this.ticker = ticker;
        this.request = request;
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
            root = raw == null ? null : mapper.readTree(raw);
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
                if (!request.reportPeriod().equals(root.path("period").asText())) gap = "REPORT_PERIOD_MISMATCH";
                if (root.path("reports").isArray() && root.path("reports").size() < request.reportCount()) gap = "REPORT_COUNT_INCOMPLETE";
            }
        } catch (Exception error) { status = EvidenceStatus.FAILED; gap = "INVALID_TOOL_JSON"; }
        if (!gap.isBlank()) status = EvidenceStatus.EMPTY;
        if (status == EvidenceStatus.AVAILABLE && name.equals("getFinancialReports") && !root.path("reports").isArray()) {
            gap = "REPORT_COUNT_UNVERIFIED";
        }
        EvidenceEnvelope item = evidenceEnvelopeMapper.map(dimension, ticker, name, raw == null ? "" : raw,
                status, Instant.now(), true);
        evidence.add(item);
        String id = "E" + evidence.size();
        boolean usable = item.hasProvenance() && (ticker == null || ticker.isBlank()
                || TargetIdentity.resolved(ticker).canonicalKey().equals(item.targetKey()));
        String data = usable && root != null ? compact(root) : "本项未取得符合请求的可引用数据。";
        boolean reduced = usable && root != null && root.toString().length() > 2200;
        String header = "[" + id + "] " + name + "\nstatus=" + item.status() + "; gap=" + gap
                + "\nsource=" + item.sourceRef() + "; provider=" + item.provider() + "; asOf=" + item.asOf() + "\n";
        String section = header + data + "\n";
        boolean included = chars + section.length() <= 11000;
        if (included) { sections.add(section); chars += section.length(); }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("tool", name); row.put("status", item.status().name());
        row.put("target", item.targetKey()); row.put("source", item.sourceRef());
        row.put("provider", item.provider()); row.put("asOf", item.asOf() == null ? "" : item.asOf().toString());
        row.put("observedAt", item.observedAt() == null ? "" : item.observedAt().toString());
        row.put("gap", gap); row.put("included", included); row.put("chars", included ? section.length() : 0);
        row.put("reduced", reduced);
        row.put("citable", usable && included); row.put("droppedReason", included ? "" : "CONTEXT_BUDGET");
        observations.add(Map.copyOf(row));
    }

    /** JSON 按完整字段/记录缩减；K 线点集由已有图表事件单独提供。 */
    private String compact(JsonNode root) {
        if (root.toString().length() <= 2200) return root.toString();
        if (!root.isObject()) return "数据较长，本次正文未纳入完整结果，不能据此断言已经完整回答。";
        ObjectNode selected = mapper.createObjectNode();
        for (String key : List.of("symbol", "code", "period", "bar", "currency", "asOf", "as_of", "provider", "source", "count", "message")) {
            if (root.has(key) && root.get(key).toString().length() <= 350) selected.set(key, root.get(key));
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
    List<Map<String, Object>> observations() { return List.copyOf(observations); }
    List<String> citationIds() { return observations.stream().filter(row -> Boolean.TRUE.equals(row.get("citable"))).map(row -> row.get("id").toString()).toList(); }
    boolean allIncluded() { return observations.stream().allMatch(row -> Boolean.TRUE.equals(row.get("included"))
            && !Boolean.TRUE.equals(row.get("reduced")) && "".equals(row.get("gap"))); }
    boolean hasUsefulResult() { return evidence.stream().anyMatch(item -> item.hasProvenance() || (item.status() == EvidenceStatus.NO_RESULTS && !item.provider().isBlank())); }
    String context() { return String.join("\n", sections); }
}
