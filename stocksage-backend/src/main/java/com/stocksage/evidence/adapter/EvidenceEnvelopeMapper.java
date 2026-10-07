package com.stocksage.evidence.adapter;

import com.stocksage.service.TickerResolutionService;
import com.stocksage.service.KLinePayloadMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServicePayloads;
import com.stocksage.client.KLineResponse;
import com.stocksage.client.SecFinancialsResponse;
import com.stocksage.client.AShareFinancialsResponse;
import com.stocksage.client.HkFinancialsResponse;
import com.stocksage.client.SearchResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.ibkr.IbkrHistoricalResponse;
import com.stocksage.evidence.EvidenceTiming;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import com.stocksage.evidence.EvidenceModels.TargetIdentity;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 普通取证与 DEEP 共用标的、来源、业务时间和证据 ID 规则。 */
@Component
@RequiredArgsConstructor
public class EvidenceEnvelopeMapper {

    public static final String OMITTED_CONTEXT = "{\"contextReduced\":true,\"evidenceOmitted\":true}";

    /** 读取响应树；版本化契约由各响应类型验收。 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    /** 递归扫描工具 JSON 时视为业务数据时间的字段名白名单。 */
    private static final Set<String> BUSINESS_TIME_FIELDS = Set.of(
            "asof",
            "date",
            "tradedate",
            "filed",
            "filingdate",
            "publishedat",
            "publisheddate",
            "reportdate",
            "statdate",
            "pubdate",
            "end",
            "t"
    );

    private final TickerResolutionService tickerResolutionService;

    public EvidenceEnvelope map(
            EvidenceDimension dimension,
            String ticker,
            String capabilityId,
            String payload,
            EvidenceStatus status,
            Instant observedAt,
            boolean approvedReadOnly
    ) {
        PayloadFacts facts = readFacts(ticker, capabilityId, payload);
        String payloadHash = sha256(payload);
        String evidenceId = sha256(
                dimension.name() + "|" + capabilityId + "|" + facts.target() + "|" + payloadHash);
        EvidenceStatus structuredStatus = status == EvidenceStatus.AVAILABLE ? facts.status() : status;
        if (structuredStatus == EvidenceStatus.NO_RESULTS && dimension != EvidenceDimension.NEWS) {
            structuredStatus = EvidenceStatus.EMPTY;
        }
        EvidenceProvenance provenance = structuredStatus == EvidenceStatus.AVAILABLE || structuredStatus == EvidenceStatus.NO_RESULTS
                ? facts.provenance() : EvidenceProvenance.empty();
        return new EvidenceEnvelope(
                evidenceId, dimension, capabilityId, facts.target(), structuredStatus,
                provenance.sourceRef(), provenance.provider(), observedAt, provenance.asOf(), payloadHash,
                approvedReadOnly, structuredStatus == EvidenceStatus.AVAILABLE || structuredStatus == EvidenceStatus.EMPTY
                        || structuredStatus == EvidenceStatus.NO_RESULTS ? facts.timing() : null);
    }

    /** 来源和业务数据分别验收；计数、日期、股票身份不能替代行情或财务数值。 */
    public EvidenceStatus inspectStatus(String capabilityId, String payload) {
        return readFacts("", capabilityId, payload).status();
    }

    /** 同一响应只解析和验收一次，身份、状态、来源和时间使用同一份契约事实。 */
    private PayloadFacts readFacts(String ticker, String capabilityId, String payload) {
        String requested = normalizedTarget(ticker, TargetIdentity.resolved(ticker).canonicalKey());
        if (payload == null || payload.isBlank()) return PayloadFacts.empty(requested, EvidenceStatus.EMPTY);
        String target = requested;
        try {
            JsonNode root = JSON_MAPPER.readTree(payload);
            if (root == null || root.isNull() || root.isEmpty()) return PayloadFacts.empty(requested, EvidenceStatus.EMPTY);
            String name = capabilityName(capabilityId);
            if (root.has("schemaVersion")) {
                if ("getStockKLine".equals(name)) {
                    KLineResponse response = KLineResponse.fromJson(root);
                    target = normalizedTarget(response.resolvedCode(), requested);
                    EvidenceStatus status = contractStatus(response.status());
                    if (status == EvidenceStatus.FAILED) return PayloadFacts.empty(target, status);
                    String bar = switch (response.period()) {
                        case "daily" -> "1d";
                        case "weekly" -> "1w";
                        case "monthly" -> "1m";
                        default -> throw new IllegalArgumentException("Unsupported K-line period");
                    };
                    EvidenceTiming timing = new EvidenceTiming(1, Instant.parse(response.fetchedAt()),
                            new EvidenceTiming.Market(response.market(), bar, "DATE", dateOrNull(response.asOf()), null, null), null, null);
                    EvidenceProvenance provenance = status == EvidenceStatus.AVAILABLE
                            ? new EvidenceProvenance(stableProviderRef(response.provider(), capabilityId, response.resolvedCode()),
                                    response.provider(), parseBusinessInstant(response.asOf())) : EvidenceProvenance.empty();
                    return new PayloadFacts(target, status, provenance, timing);
                }
                if ("getIbkrHistoricalBars".equals(name)) {
                    IbkrHistoricalResponse response = IbkrHistoricalResponse.fromJson(root);
                    String symbol = "HK".equals(response.market()) ? "HK:" + response.symbol() : response.symbol();
                    target = normalizedTarget(symbol, requested);
                    EvidenceStatus status = contractStatus(response.status());
                    if (status == EvidenceStatus.FAILED) return PayloadFacts.empty(target, status);
                    Instant asOf = response.asOf() == null ? null : Instant.parse(response.asOf());
                    EvidenceTiming timing = new EvidenceTiming(1, Instant.parse(response.fetchedAt()),
                            new EvidenceTiming.Market(response.market(), response.bar(), "INSTANT", null, asOf, response.delayed()), null, null);
                    EvidenceProvenance provenance = status == EvidenceStatus.AVAILABLE
                            ? new EvidenceProvenance(ibkrHistoryRef(root, target), response.provider(), asOf) : EvidenceProvenance.empty();
                    return new PayloadFacts(target, status, provenance, timing);
                }
                if ("getStructuredFinancials".equals(name)
                        || ("getFinancialReports".equals(name) && "SEC_EDGAR".equals(root.path("provider").asText()))) {
                    SecFinancialsResponse response = SecFinancialsResponse.fromJson(root);
                    target = normalizedTarget(response.ticker(), requested);
                    EvidenceStatus status = contractStatus(response.status());
                    if (status == EvidenceStatus.FAILED) return PayloadFacts.empty(target, status);
                    var facts = response.metrics().values().stream().flatMap(metric -> metric.data().stream()).toList();
                    LocalDate latestEnd = facts.stream().map(fact -> LocalDate.parse(fact.end())).max(LocalDate::compareTo).orElse(null);
                    LocalDate latestFiled = facts.stream().map(fact -> LocalDate.parse(fact.filed())).max(LocalDate::compareTo).orElse(null);
                    EvidenceTiming timing = new EvidenceTiming(1, Instant.parse(response.fetchedAt()), null,
                            new EvidenceTiming.Financial(response.period(), latestEnd, latestFiled), null);
                    // 申报日期决定事实修订版本，财务事实的业务期间仍取 asOf。
                    EvidenceProvenance provenance = status == EvidenceStatus.AVAILABLE
                            ? new EvidenceProvenance(secCompanyFactsUrl(response.cik()), response.provider(),
                                    parseBusinessInstant(response.asOf())) : EvidenceProvenance.empty();
                    return new PayloadFacts(target, status, provenance, timing);
                }
                if ("getFinancialReports".equals(name) && "baostock".equals(root.path("provider").asText())) {
                    AShareFinancialsResponse response = AShareFinancialsResponse.fromJson(root);
                    target = normalizedTarget(response.code(), requested);
                    EvidenceStatus status = contractStatus(response.status());
                    if (status == EvidenceStatus.FAILED) return PayloadFacts.empty(target, status);
                    var rows = response.reports().stream().flatMap(report -> report.statements().all().stream())
                            .filter(statement -> statement.status() == AShareFinancialsResponse.Status.SUCCESS)
                            .map(statement -> statement.data()).toList();
                    LocalDate latestEnd = rows.stream().map(row -> LocalDate.parse(row.statDate())).max(LocalDate::compareTo).orElse(null);
                    LocalDate latestPublished = rows.stream().map(row -> LocalDate.parse(row.pubDate())).max(LocalDate::compareTo).orElse(null);
                    EvidenceTiming timing = new EvidenceTiming(1, Instant.parse(response.fetchedAt()), null,
                            new EvidenceTiming.Financial(response.period(), latestEnd, latestPublished), null);
                    EvidenceProvenance provenance = status == EvidenceStatus.AVAILABLE
                            ? new EvidenceProvenance(stableProviderRef(response.provider(), capabilityId, response.code()),
                                    response.provider(), parseBusinessInstant(response.asOf())) : EvidenceProvenance.empty();
                    return new PayloadFacts(target, status, provenance, timing);
                }
                if ("getFinancialReports".equals(name) && "HK".equals(root.path("market").asText())) {
                    HkFinancialsResponse response = HkFinancialsResponse.fromJson(root);
                    target = tickerResolutionService.normalizeStructuredTicker(response.resolvedCode());
                    EvidenceStatus status = contractStatus(response.status());
                    if (status == EvidenceStatus.FAILED) return PayloadFacts.empty(target, status);
                    // 港股契约的 asOf 是有数值行的 REPORT_DATE，上游未提供明确披露日期。
                    EvidenceTiming timing = new EvidenceTiming(1, Instant.parse(response.fetchedAt()), null,
                            new EvidenceTiming.Financial(response.period(), dateOrNull(response.asOf()), null), null);
                    EvidenceProvenance provenance = status == EvidenceStatus.AVAILABLE
                            ? new EvidenceProvenance(stableProviderRef(response.provider(), capabilityId, response.resolvedCode()),
                                    response.provider(), parseBusinessInstant(response.asOf())) : EvidenceProvenance.empty();
                    return new PayloadFacts(target, status, provenance, timing);
                }
                if (root.has("searchType") && isSearchEvidence(capabilityId)) {
                    target = structuredEvidenceTarget(requested, root);
                    SearchResponse response = SearchResponse.fromJson(root);
                    EvidenceStatus status = contractStatus(response.status());
                    if (status == EvidenceStatus.FAILED) return PayloadFacts.empty(target, status);
                    if (status == EvidenceStatus.EMPTY) status = EvidenceStatus.NO_RESULTS;
                    var instants = response.results().stream()
                            .filter(row -> row.publishedTimeKind() == SearchResponse.PublicationTimeKind.INSTANT)
                            .map(row -> Instant.parse(row.publishedAt())).toList();
                    var dates = response.results().stream()
                            .filter(row -> row.publishedTimeKind() == SearchResponse.PublicationTimeKind.DATE)
                            .map(row -> LocalDate.parse(row.publishedDate())).toList();
                    EvidenceTiming timing = new EvidenceTiming(1, Instant.parse(response.fetchedAt()), null, null,
                            new EvidenceTiming.Search(response.requestedTimelimit(), response.effectiveTimelimit(), response.requestedDays(),
                                    instants.stream().min(Instant::compareTo).orElse(null), instants.stream().max(Instant::compareTo).orElse(null),
                                    dates.stream().min(LocalDate::compareTo).orElse(null), dates.stream().max(LocalDate::compareTo).orElse(null),
                                    instants.size(), dates.size(), response.results().size() - instants.size() - dates.size()));
                    EvidenceProvenance provenance;
                    if (response.results().isEmpty()) {
                        provenance = new EvidenceProvenance(stableProviderRef(response.provider(), capabilityId, target), response.provider(), null);
                    } else {
                        var first = response.results().get(0);
                        // 来源和时间必须属于同一篇文章。
                        provenance = new EvidenceProvenance(first.link(), response.provider(),
                                first.publishedAt() == null ? null : Instant.parse(first.publishedAt()));
                    }
                    return new PayloadFacts(target, status, provenance, timing);
                }
            }
            target = structuredEvidenceTarget(requested, root);
            EvidenceStatus status = inspectUnversionedStatus(name, root);
            EvidenceProvenance provenance = extractProvenance(capabilityId, target, root);
            return new PayloadFacts(target, status, provenance, null);
        } catch (Exception invalid) {
            return PayloadFacts.empty(target, EvidenceStatus.FAILED);
        }
    }

    private static EvidenceStatus contractStatus(Enum<?> status) {
        return switch (status.name()) {
            case "SUCCESS", "PARTIAL" -> EvidenceStatus.AVAILABLE;
            case "EMPTY" -> EvidenceStatus.EMPTY;
            case "UNSUPPORTED", "ERROR" -> EvidenceStatus.FAILED;
            default -> throw new IllegalArgumentException("Unsupported evidence status: " + status);
        };
    }

    private static LocalDate dateOrNull(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static String capabilityName(String capabilityId) {
        return capabilityId == null ? "" : capabilityId.substring(capabilityId.lastIndexOf('/') + 1).split("\\(", 2)[0];
    }

    private EvidenceStatus inspectUnversionedStatus(String name, JsonNode root) {
        if (DataServicePayloads.hasTopLevelError(root)) return EvidenceStatus.FAILED;
        if (KLinePayloadMapper.isKlineTool(name)) return EvidenceStatus.EMPTY;
        if (name.equals("getStructuredFinancials") || name.equals("getFinancialReports")) {
            boolean financials = hasSecFinancialValue(root.path("metrics"))
                    || hasBusinessNumber(root.path("reports")) || hasBusinessNumber(root.path("statements"))
                    || hasBusinessNumber(root.path("indicators"));
            return financials ? EvidenceStatus.AVAILABLE : EvidenceStatus.EMPTY;
        }
        if (name.equals("getFinancialMetrics")) {
            return hasBusinessNumber(root.path("metrics")) || List.of("price", "pe", "pb", "marketCap", "roe")
                    .stream().anyMatch(key -> hasBusinessNumber(root.path(key))) ? EvidenceStatus.AVAILABLE : EvidenceStatus.EMPTY;
        }
        if (name.equals("getTechnicalIndicators")) {
            return hasBusinessNumber(root.path("indicators")) ? EvidenceStatus.AVAILABLE : EvidenceStatus.EMPTY;
        }
        for (String field : List.of("results", "news", "items", "data")) {
            JsonNode rows = root.path(field);
            if (rows.isArray()) return rows.isEmpty() ? EvidenceStatus.NO_RESULTS : EvidenceStatus.AVAILABLE;
        }
        // 未知能力不能凭提供方标签声明成功；仍保留有实质正文的既有结果。
        return hasBusinessNumber(root) ? EvidenceStatus.AVAILABLE : EvidenceStatus.EMPTY;
    }

    /** SEC 的公告财年、期间和计数只是元数据；财务事实只能来自每个 concept 的 value。 */
    private boolean hasSecFinancialValue(JsonNode metrics) {
        if (!metrics.isObject()) return false;
        for (JsonNode metric : metrics) {
            for (JsonNode point : metric.path("data")) {
                if (isFiniteNumber(point.path("value"))) return true;
            }
        }
        return false;
    }

    private boolean isFiniteNumber(JsonNode node) {
        if (node == null) return false;
        if (node.isNumber()) return Double.isFinite(node.asDouble());
        if (node.isTextual()) {
            try { return Double.isFinite(Double.parseDouble(node.asText().replace(",", "").trim())); }
            catch (NumberFormatException ignored) { return false; }
        }
        return false;
    }

    private boolean hasBusinessNumber(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isBoolean()) return false;
        if (isFiniteNumber(node)) return true;
        if (node.isArray()) {
            for (JsonNode item : node) if (hasBusinessNumber(item)) return true;
        } else if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
                if (key.contains("date") || key.contains("日期") || key.contains("时间")
                        || Set.of("year", "quarter", "fiscalyear", "fy", "fp", "cik", "code", "symbol", "ticker",
                        "resolvedcode", "count", "metriccount", "statementcount", "asof", "timestamp", "t", "filed", "start", "end").contains(key)) continue;
                if (hasBusinessNumber(field.getValue())) return true;
            }
        }
        return false;
    }

    private String normalizedTarget(String candidate, String fallback) {
        String normalized = tickerResolutionService.normalizeStructuredTicker(candidate);
        return normalized.isBlank() ? fallback : normalized;
    }

    private String structuredEvidenceTarget(String requested, JsonNode root) {
        for (String field : List.of("resolvedCode", "symbol", "ticker")) {
            JsonNode value = root.get(field);
            String candidate = value == null || !value.isTextual()
                    ? "" : tickerResolutionService.normalizeStructuredTicker(value.asText());
            if (!candidate.isBlank() && !candidate.equals(requested)) return candidate;
        }
        return requested;
    }

    private EvidenceProvenance extractProvenance(String capabilityId, String ticker, JsonNode root) {
        if (root.path("error").asBoolean(false)) return EvidenceProvenance.empty();
        try {
            String provider = firstDirectText(root, "provider");
            if (provider.isBlank()) {
                provider = firstDirectText(root, "source");
            }

            String cik = firstDirectText(root, "cik");
            if (isSecEvidence(capabilityId, cik)) {
                provider = "SEC EDGAR XBRL";
            } else if (isIbkrEvidence(capabilityId)) {
                provider = provider.isBlank() ? "IBKR_WEB_API" : provider;
            }

            JsonNode selectedSearchResult = null;
            String sourceRef = allowlistedSourceRef(
                    firstDirectText(
                            root,
                            "sourceRef",
                            "source_ref",
                            "documentUrl",
                            "document_url",
                            "url"
                    )
            );
            if (sourceRef.isBlank() && isSearchEvidence(capabilityId)) {
                selectedSearchResult = firstSearchResult(root);
                sourceRef = selectedSearchResult == null
                        ? ""
                        : firstDirectText(
                                selectedSearchResult,
                                "url",
                                "link",
                                "sourceRef",
                                "source_ref",
                                "documentUrl",
                                "document_url"
                        );
            }
            if (sourceRef.isBlank() && isSecEvidence(capabilityId, cik)) {
                sourceRef = secCompanyFactsUrl(cik);
            }
            if (sourceRef.isBlank() && isIbkrEvidence(capabilityId)) {
                sourceRef = ibkrHistoryRef(root, ticker);
            }
            if (sourceRef.isBlank()) {
                sourceRef = allowlistedSourceRef(firstDirectText(root, "endpoint"));
            }
            if (sourceRef.isBlank() && !provider.isBlank()) {
                String providerTarget = firstDirectText(
                        root,
                        "resolvedCode",
                        "symbol",
                        "code",
                        "ticker"
                );
                sourceRef = stableProviderRef(
                        provider,
                        capabilityId,
                        providerTarget.isBlank() ? ticker : providerTarget
                );
            }

            Instant asOf = parseBusinessInstant(
                    firstDirectText(root, "asOf", "as_of")
            );
            if (asOf == null) {
                asOf = extractBusinessAsOf(
                        selectedSearchResult == null ? root : selectedSearchResult
                );
            }
            return new EvidenceProvenance(
                    sourceRef,
                    provider,
                    asOf
            );
        } catch (Exception ignored) {
            return EvidenceProvenance.empty();
        }
    }

    private boolean isVersionedHkFinancials(String capabilityId, JsonNode root) {
        if (root == null || !root.has("schemaVersion") || capabilityId == null) return false;
        String name = capabilityName(capabilityId);
        return "getFinancialReports".equals(name) && "HK".equals(root.path("market").asText());
    }

    private boolean isVersionedSearch(String capabilityId, JsonNode root) {
        return root != null && root.has("schemaVersion") && root.has("searchType") && isSearchEvidence(capabilityId);
    }

    /** 普通回答与 DEEP 共用有界视图，财务项目及新闻来源、时间均保持关联。 */
    public String compactStructuredContext(String capabilityId, String payload, int maxChars) {
        try {
            JsonNode root = JSON_MAPPER.readTree(payload);
            if (isVersionedSearch(capabilityId, root) && payload.length() > maxChars) {
                SearchResponse.fromJson(root);
                ObjectNode selected = JSON_MAPPER.createObjectNode();
                for (String key : List.of("schemaVersion", "status", "searchType", "provider", "fetchedAt",
                        "requestedTimelimit", "effectiveTimelimit", "requestedDepth", "effectiveDepth",
                        "requestedDays", "fallbackFrom", "fallbackReason")) selected.set(key, root.get(key));
                selected.put("contextReduced", true);
                var rows = selected.putArray("results");
                for (JsonNode row : root.path("results")) {
                    rows.add(row);
                    if (selected.toString().length() > maxChars) {
                        if (rows.size() == 1) {
                            ObjectNode excerpt = row.deepCopy();
                            excerpt.put("snippetTruncated", true);
                            rows.set(0, excerpt);
                            String snippet = excerpt.path("snippet").asText();
                            while (selected.toString().length() > maxChars && !snippet.isEmpty()) {
                                snippet = snippet.substring(0, snippet.offsetByCodePoints(0, snippet.codePointCount(0, snippet.length()) / 2));
                                excerpt.put("snippet", snippet);
                            }
                            if (selected.toString().length() <= maxChars) break;
                        }
                        rows.remove(rows.size() - 1);
                        // 不跳到别篇文章，否则证据头的来源和时间会与实际可见内容错配。
                        break;
                    }
                }
                return !rows.isEmpty() && selected.toString().length() <= maxChars ? selected.toString() : OMITTED_CONTEXT;
            }
            if (!isVersionedHkFinancials(capabilityId, root) || payload.length() <= maxChars) return payload;
            HkFinancialsResponse response = HkFinancialsResponse.fromJson(root);
            ObjectNode selected = JSON_MAPPER.createObjectNode();
            for (String key : List.of("schemaVersion", "status", "provider", "market", "symbol", "resolvedCode",
                    "period", "requestedPeriod", "requestedYears", "timeKind", "fetchedAt", "asOf", "currency",
                    "valueScale", "valueEncoding", "numericPrecision", "aggregationBasis")) {
                if (root.has(key)) selected.set(key, root.get(key));
            }
            selected.put("contextReduced", true);
            ObjectNode statements = selected.putObject("statements");
            for (String name : List.of("balanceSheet", "incomeStatement", "cashFlow", "indicators")) {
                JsonNode table = name.equals("indicators") ? root.path(name) : root.path("statements").path(name);
                ObjectNode summary = JSON_MAPPER.createObjectNode();
                for (String key : List.of("status", "errorCode", "retryable")) summary.set(key, table.get(key));
                if (name.equals("indicators")) selected.set(name, summary);
                else statements.set(name, summary);
                summary.putArray("data");
            }
            if (selected.toString().length() > maxChars) return "{\"contextReduced\":true}";
            for (String name : List.of("balanceSheet", "incomeStatement", "cashFlow", "indicators")) {
                JsonNode source = name.equals("indicators") ? root.path(name) : root.path("statements").path(name);
                ObjectNode target = (ObjectNode) (name.equals("indicators") ? selected.path(name) : statements.path(name));
                var rows = (com.fasterxml.jackson.databind.node.ArrayNode) target.path("data");
                List<JsonNode> ordered = new ArrayList<>();
                List<? extends HkFinancialsResponse.FinancialRow> typedRows = switch (name) {
                    case "balanceSheet" -> response.statements().balanceSheet().data();
                    case "incomeStatement" -> response.statements().incomeStatement().data();
                    case "cashFlow" -> response.statements().cashFlow().data();
                    default -> response.indicators().data();
                };
                for (int index = 0; index < typedRows.size(); index++) {
                    if (typedRows.get(index).hasValues()) ordered.add(source.path("data").get(index));
                }
                ordered.sort(java.util.Comparator.comparing((JsonNode row) -> row.path("REPORT_DATE").asText()).reversed());
                for (JsonNode row : ordered) {
                    rows.add(row);
                    if (selected.toString().length() > maxChars) rows.remove(rows.size() - 1);
                }
            }
            return selected.toString();
        } catch (Exception error) {
            // 校验失败的工具正文沿用原有不可引用状态，不将它改写成成功财务数据。
            return payload;
        }
    }

    private boolean isSecEvidence(String capabilityId, String cik) {
        String normalized = capabilityId == null ? "" : capabilityId;
        return normalized.startsWith("getStructuredFinancials")
                || normalized.startsWith("ingestCompanyFilings")
                || (normalized.startsWith("getFinancialReports") && !cik.isBlank());
    }

    private boolean isIbkrEvidence(String capabilityId) {
        return capabilityId != null
                && capabilityId.toLowerCase().contains("ibkr");
    }

    private boolean isSearchEvidence(String capabilityId) {
        if (capabilityId == null) {
            return false;
        }
        String normalized = capabilityId.toLowerCase();
        return normalized.contains("search") || "getstocknews".equals(normalized);
    }

    private String firstDirectText(JsonNode root, String... fieldNames) {
        if (root == null || !root.isObject()) {
            return "";
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.get(fieldName);
            if (value != null && value.isValueNode()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private JsonNode firstSearchResult(JsonNode root) {
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return null;
        }
        for (JsonNode result : results) {
            String url = firstDirectText(
                    result,
                    "url",
                    "link",
                    "sourceRef",
                    "source_ref",
                    "documentUrl",
                    "document_url"
            );
            if (url.startsWith("https://") || url.startsWith("http://")) {
                return result;
            }
        }
        return null;
    }

    private String secCompanyFactsUrl(String cik) {
        String digits = cik == null ? "" : cik.replaceAll("\\D", "");
        if (digits.isBlank()) {
            return "";
        }
        if (digits.length() > 10) {
            return "";
        }
        return "https://data.sec.gov/api/xbrl/companyfacts/CIK"
                + "0".repeat(10 - digits.length())
                + digits
                + ".json";
    }

    private String ibkrHistoryRef(JsonNode root, String ticker) {
        String conid = firstDirectText(root, "conid");
        String period = firstDirectText(root, "period");
        String bar = firstDirectText(root, "bar");
        StringBuilder ref = new StringBuilder(
                "ibkr://client-portal/iserver/marketdata/history");
        appendQuery(ref, "conid", conid);
        if (conid.isBlank()) {
            appendQuery(ref, "symbol", ticker);
        }
        appendQuery(ref, "period", period);
        appendQuery(ref, "bar", bar);
        return ref.toString();
    }

    private String stableProviderRef(
            String provider,
            String capabilityId,
            String ticker
    ) {
        String normalizedProvider = provider.toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalizedProvider.isBlank()) {
            return "";
        }
        String normalizedCapability = capabilityId == null
                ? "data"
                : capabilityId.replaceAll("[^A-Za-z0-9._-]+", "-")
                        .replaceAll("(^-+|-+$)", "");
        StringBuilder ref = new StringBuilder()
                .append("provider://")
                .append(normalizedProvider)
                .append('/')
                .append(normalizedCapability);
        appendQuery(ref, "target", ticker);
        return ref.toString();
    }

    private String allowlistedSourceRef(String sourceRef) {
        if (sourceRef == null) {
            return "";
        }
        String normalized = sourceRef.trim();
        if (normalized.startsWith("https://")
                || normalized.startsWith("http://")
                || normalized.startsWith("ibkr://")
                || normalized.startsWith("provider://")) {
            return normalized;
        }
        return "";
    }

    private void appendQuery(StringBuilder ref, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        ref.append(ref.indexOf("?") >= 0 ? '&' : '?')
                .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private Instant extractBusinessAsOf(JsonNode root) {
        Instant explicit = parseBusinessInstant(firstDirectText(root, "asOf", "as_of"));
        if (explicit != null) {
            return explicit;
        }
        List<Instant> candidates = new ArrayList<>();
        collectBusinessTimes(root, candidates);
        return candidates.stream().max(Instant::compareTo).orElse(null);
    }

    private void collectBusinessTimes(JsonNode node, List<Instant> candidates) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String normalizedName = field.getKey()
                        .toLowerCase()
                        .replace("_", "")
                        .replace("-", "");
                if (BUSINESS_TIME_FIELDS.contains(normalizedName)) {
                    Instant parsed = parseBusinessInstant(field.getValue());
                    if (parsed != null) {
                        candidates.add(parsed);
                    }
                }
                collectBusinessTimes(field.getValue(), candidates);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectBusinessTimes(item, candidates);
            }
        }
    }

    private Instant parseBusinessInstant(JsonNode value) {
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            long epoch = value.asLong();
            if (epoch >= 1_000_000_000_000L) {
                return Instant.ofEpochMilli(epoch);
            }
            if (epoch >= 1_000_000_000L) {
                return Instant.ofEpochSecond(epoch);
            }
            return null;
        }
        return parseBusinessInstant(value.asText(""));
    }

    private Instant parseBusinessInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // Try the bounded business date formats below.
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue.
        }
        try {
            return ZonedDateTime.parse(
                    normalized,
                    DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue.
        }
        try {
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Continue.
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record PayloadFacts(String target, EvidenceStatus status, EvidenceProvenance provenance, EvidenceTiming timing) {
        private static PayloadFacts empty(String target, EvidenceStatus status) {
            return new PayloadFacts(target, status, EvidenceProvenance.empty(), null);
        }
    }

    private record EvidenceProvenance(
            String sourceRef,
            String provider,
            Instant asOf
    ) {
        private static EvidenceProvenance empty() {
            return new EvidenceProvenance("", "", null);
        }
    }

}
