package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServicePayloads;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.TargetIdentity;
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

    /** 仅用于容错解析工具响应来源信息的轻量 JSON 解析器。 */
    private static final ObjectMapper PROVENANCE_MAPPER = new ObjectMapper();
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

    EvidenceEnvelope map(
            EvidenceDimension dimension,
            String ticker,
            String capabilityId,
            String payload,
            EvidenceStatus status,
            Instant observedAt,
            boolean approvedReadOnly
    ) {
        String evidenceTarget = structuredEvidenceTarget(ticker, payload);
        String payloadHash = sha256(payload);
        String evidenceId = sha256(
                dimension.name() + "|" + capabilityId + "|" + evidenceTarget + "|" + payloadHash);
        EvidenceStatus structuredStatus = status == EvidenceStatus.AVAILABLE ? inspectStatus(capabilityId, payload) : status;
        if (structuredStatus == EvidenceStatus.NO_RESULTS && dimension != EvidenceDimension.NEWS) {
            structuredStatus = EvidenceStatus.EMPTY;
        }
        EvidenceProvenance provenance = structuredStatus == EvidenceStatus.AVAILABLE || structuredStatus == EvidenceStatus.NO_RESULTS
                ? extractProvenance(capabilityId, evidenceTarget, payload)
                : EvidenceProvenance.empty();
        return new EvidenceEnvelope(
                evidenceId,
                dimension,
                capabilityId,
                evidenceTarget,
                structuredStatus,
                provenance.sourceRef(),
                provenance.provider(),
                observedAt,
                provenance.asOf(),
                payloadHash,
                approvedReadOnly
        );
    }

    /** 来源和业务数据分别验收；计数、日期、股票身份不能替代行情或财务数值。 */
    EvidenceStatus inspectStatus(String capabilityId, String payload) {
        if (payload == null || payload.isBlank()) return EvidenceStatus.EMPTY;
        try {
            JsonNode root = PROVENANCE_MAPPER.readTree(payload);
            if (DataServicePayloads.hasTopLevelError(root)) return EvidenceStatus.FAILED;
            if (root == null || root.isNull() || root.isEmpty()) return EvidenceStatus.EMPTY;
            String name = capabilityId.substring(capabilityId.lastIndexOf('/') + 1).split("\\(", 2)[0];
            if (KLinePayloadMapper.isKlineTool(name)) {
                var chart = new KLinePayloadMapper(PROVENANCE_MAPPER).toChartPayload(name, root, new Object[0]);
                if (chart.isEmpty()) return EvidenceStatus.EMPTY;
                @SuppressWarnings("unchecked")
                var rows = (List<java.util.Map<String, Object>>) chart.get().get("points");
                return rows.stream().anyMatch(row -> List.of("open", "high", "low", "close").stream()
                        .allMatch(key -> row.get(key) instanceof Number value && Double.isFinite(value.doubleValue())))
                        ? EvidenceStatus.AVAILABLE : EvidenceStatus.EMPTY;
            }
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
        } catch (Exception ignored) {
            return EvidenceStatus.FAILED;
        }
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

    private String structuredEvidenceTarget(String requestedTicker, String payload) {
        String requested = tickerResolutionService.normalizeStructuredTicker(requestedTicker);
        if (requested == null || requested.isBlank()) {
            requested = TargetIdentity.resolved(requestedTicker).canonicalKey();
        }
        if (payload == null || payload.isBlank()) {
            return requested;
        }
        try {
            JsonNode root = PROVENANCE_MAPPER.readTree(payload);
            for (String field : List.of("resolvedCode", "symbol", "ticker")) {
                JsonNode value = root == null ? null : root.get(field);
                String candidate = value == null || !value.isTextual()
                        ? ""
                        : tickerResolutionService.normalizeStructuredTicker(value.asText());
                if (candidate != null && !candidate.isBlank() && !candidate.equals(requested)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 工具正文没有可验证的结构化标的，沿用请求侧已解析身份。
        }
        return requested;
    }

    private EvidenceProvenance extractProvenance(
            String capabilityId,
            String ticker,
            String payload
    ) {
        if (payload == null || payload.isBlank()) {
            return EvidenceProvenance.empty();
        }
        try {
            JsonNode root = PROVENANCE_MAPPER.readTree(payload);
            if (root == null || root.isNull() || root.isMissingNode()
                    || root.path("error").asBoolean(false)) {
                return EvidenceProvenance.empty();
            }

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
