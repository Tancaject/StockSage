package com.stocksage.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SEC 年度 companyfacts 契约；事实期间与公告财年分开保存，金额不经 double 中转。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecFinancialsResponse(
        int schemaVersion, Status status, String provider, String market, String period, String timeKind,
        String fetchedAt, String asOf, String ticker, @JsonProperty("company_name") String companyName, String cik,
        @JsonProperty("metric_count") int metricCount, Map<String, Metric> metrics,
        boolean error, String errorCode, String message, Boolean retryable,
        String requestedPeriod, Integer requestedYears, String input, String resolvedCode, String source, String routeReason
) {
    public enum Status { SUCCESS, EMPTY, UNSUPPORTED, ERROR }
    private static final Set<String> METRICS = Set.of("Revenue", "NetIncome", "TotalAssets", "TotalLiabilities",
            "StockholdersEquity", "OperatingIncome", "EPS", "OperatingCashFlow", "TotalDebt");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    static {
        MAPPER.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    public SecFinancialsResponse {
        if (schemaVersion != 1 || status == null || !"SEC_EDGAR".equals(provider) || !"US".equals(market)
                || !"annual".equals(period) || !"DATE".equals(timeKind)) {
            throw new IllegalArgumentException("Invalid SEC financials contract");
        }
        Instant.parse(fetchedAt);
        if ((requestedPeriod == null) != (requestedYears == null)
                || (requestedPeriod != null && !Set.of("annual", "quarterly").contains(requestedPeriod))
                || (requestedYears != null && (requestedYears < 1 || requestedYears > 10))) {
            throw new IllegalArgumentException("Invalid SEC financial report request scope");
        }
        if ("quarterly".equals(requestedPeriod) && (status == Status.SUCCESS || status == Status.EMPTY)) {
            throw new IllegalArgumentException("Annual SEC facts cannot satisfy a quarterly request");
        }
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        if (!METRICS.containsAll(metrics.keySet()) || metricCount != metrics.size()
                || error != (status == Status.ERROR || status == Status.UNSUPPORTED)) {
            throw new IllegalArgumentException("Inconsistent SEC financials metrics/status");
        }
        if (status == Status.SUCCESS) {
            if (metrics.isEmpty() || ticker == null || ticker.isBlank() || companyName == null || companyName.isBlank()
                    || cik == null || !cik.matches("\\d{10}")) {
                throw new IllegalArgumentException("SEC financials require a verified company and facts");
            }
            String sourceUrl = "https://data.sec.gov/api/xbrl/companyfacts/CIK" + cik + ".json";
            String latest = null;
            for (Metric metric : metrics.values()) {
                if (requestedYears != null && metric.data().stream().map(Fact::end).distinct().count() > requestedYears) {
                    throw new IllegalArgumentException("SEC facts exceed the requested number of periods");
                }
                for (Fact fact : metric.data()) {
                    if (!sourceUrl.equals(fact.sourceUrl())) throw new IllegalArgumentException("SEC fact source does not match company CIK");
                    if (latest == null || fact.end().compareTo(latest) > 0) latest = fact.end();
                }
            }
            if (!java.util.Objects.equals(latest, asOf)) throw new IllegalArgumentException("SEC asOf must match latest fact end");
        } else if (!metrics.isEmpty() || asOf != null) {
            throw new IllegalArgumentException("Non-success SEC financials cannot carry usable facts");
        }
    }

    /** 数字必须来自 JSON number；不能把字符串、布尔或财年标签当成财务值。 */
    public static SecFinancialsResponse fromJson(JsonNode root) {
        try {
            if (root == null || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("metric_count").isIntegralNumber() || !root.path("error").isBoolean()
                    || !root.path("status").isTextual() || !root.path("metrics").isObject()
                    || (root.hasNonNull("retryable") && !root.get("retryable").isBoolean())) {
                throw new IllegalArgumentException("Missing SEC version/count/status/metrics");
            }
            if (root.hasNonNull("requestedYears") && !root.get("requestedYears").isIntegralNumber()) {
                throw new IllegalArgumentException("SEC requestedYears must be an integer");
            }
            for (JsonNode metric : root.get("metrics")) {
                if (!metric.path("data").isArray()) throw new IllegalArgumentException("SEC metric data must be an array");
                for (JsonNode fact : metric.get("data")) {
                    if (!fact.path("value").isNumber()) throw new IllegalArgumentException("SEC value must be numeric");
                    for (String year : List.of("fiscal_year", "filing_fiscal_year")) {
                        if (fact.hasNonNull(year) && !fact.get(year).isIntegralNumber()) {
                            throw new IllegalArgumentException("SEC year must be an integer");
                        }
                    }
                }
            }
            return MAPPER.treeToValue(root, SecFinancialsResponse.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid SEC financials v1 response", error);
        }
    }

    public static SecFinancialsResponse failure(String ticker, String errorCode, String message, Boolean retryable) {
        return new SecFinancialsResponse(1, Status.ERROR, "SEC_EDGAR", "US", "annual", "DATE", Instant.now().toString(),
                null, ticker, null, null, 0, Map.of(), true, errorCode, message, retryable, null, null, null, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metric(String concept, String unit, List<Fact> data) {
        public Metric {
            if (concept == null || !concept.matches("us-gaap:[A-Za-z][A-Za-z0-9]*")
                    || unit == null || !Set.of("USD", "USD/shares").contains(unit)) {
                throw new IllegalArgumentException("Invalid SEC concept or unit");
            }
            data = List.copyOf(data);
            if (data.isEmpty()) throw new IllegalArgumentException("SEC metric must contain actual facts");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fact(BigDecimal value, String start, String end, @JsonProperty("period_type") String periodType,
                       String filed, String form, String accn, String fp, String frame,
                       @JsonProperty("fiscal_year") Integer fiscalYear,
                       @JsonProperty("filing_fiscal_year") Integer filingFiscalYear,
                       @JsonProperty("source_url") String sourceUrl) {
        public Fact {
            if (value == null || periodType == null || !Set.of("instant", "duration").contains(periodType)
                    || form == null || !Set.of("10-K", "10-K/A").contains(form) || accn == null) {
                throw new IllegalArgumentException("Invalid SEC financial fact");
            }
            LocalDate endDate = LocalDate.parse(end);
            if (LocalDate.parse(filed).isBefore(endDate)) throw new IllegalArgumentException("SEC filing predates fact end");
            if ("instant".equals(periodType)) {
                if (start != null) throw new IllegalArgumentException("Instant SEC fact cannot carry a duration start");
            } else {
                long days = ChronoUnit.DAYS.between(LocalDate.parse(start), endDate);
                if (days < 335 || days > 395) throw new IllegalArgumentException("SEC fact is not an annual duration");
            }
        }
    }
}
