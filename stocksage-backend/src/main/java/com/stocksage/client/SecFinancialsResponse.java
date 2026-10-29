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

/** SEC 年度及单季 companyfacts 契约；事实期间与公告财年分开保存，金额不经 double 中转。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecFinancialsResponse(
        int schemaVersion, Status status, String provider, String market, String period, String timeKind,
        String fetchedAt, String asOf, String ticker, @JsonProperty("company_name") String companyName, String cik,
        @JsonProperty("metric_count") int metricCount, Map<String, Metric> metrics,
        boolean error, String errorCode, String message, Boolean retryable,
        String requestedPeriod, Integer requestedYears, String input, String resolvedCode, String source, String routeReason,
        BalanceSheet balanceSheet,
        RevenueBreakdown revenueBreakdown,
        Map<String, String> latestFilings,
        String requestedAsOf
) {
    public enum Status { SUCCESS, EMPTY, UNSUPPORTED, ERROR }
    private static final Set<String> METRICS = Set.of("Revenue", "NetIncome", "ConsolidatedNetIncome", "TotalAssets", "TotalLiabilities",
            "StockholdersEquity", "OperatingIncome", "EPS", "OperatingCashFlow", "TotalDebt",
            "CapitalExpenditure", "LeasedEquipmentExpenditure", "PretaxIncome", "SalesRevenue",
            "ContinuingOperationsNetIncome", "ContinuingOperationsCashFlow", "GainOnDispositions",
            "CurrentAssets", "CurrentLiabilities", "ShortTermBorrowings", "CommercialPaper", "DebtCurrent",
            "LongTermDebtCurrent", "LongTermDebtNoncurrent", "LongTermDebtAndFinanceLeases", "Cash", "DilutedShares", "NetInterestIncome", "CreditLossProvision",
            "Inventory", "CostOfRevenue", "CommonSharesOutstanding");
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
                || !Set.of("annual", "quarterly").contains(period) || !"DATE".equals(timeKind)) {
            throw new IllegalArgumentException("Invalid SEC financials contract");
        }
        Instant.parse(fetchedAt);
        LocalDate cutoff = requestedAsOf == null ? null : LocalDate.parse(requestedAsOf);
        if ((requestedPeriod == null) != (requestedYears == null)
                || (requestedPeriod != null && !Set.of("annual", "quarterly").contains(requestedPeriod))
                || (requestedYears != null && (requestedYears < 1 || requestedYears > 10))) {
            throw new IllegalArgumentException("Invalid SEC financial report request scope");
        }
        if (requestedPeriod != null && !requestedPeriod.equals(period)
                && (status == Status.SUCCESS || status == Status.EMPTY)) {
            throw new IllegalArgumentException("SEC facts must match the requested annual or quarterly period");
        }
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        if (latestFilings != null) {
            // SEC 申报索引中最新 10-K/10-Q 的申报日期，只接受这两类表单和合法日期。
            if (!Set.of("10-K", "10-Q").containsAll(latestFilings.keySet())) {
                throw new IllegalArgumentException("Invalid SEC latest filing forms");
            }
            latestFilings.values().forEach(LocalDate::parse);
            if (cutoff != null && latestFilings.values().stream().anyMatch(value -> LocalDate.parse(value).isAfter(cutoff))) {
                throw new IllegalArgumentException("SEC latest filing metadata exceeds the requested cutoff");
            }
            latestFilings = Map.copyOf(latestFilings);
        }
        if (cutoff != null && (revenueBreakdown != null
                || balanceSheet != null && LocalDate.parse(balanceSheet.filed()).isAfter(cutoff))) {
            throw new IllegalArgumentException("SEC historical response contains unverified current attachments");
        }
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
            boolean quarterly = "quarterly".equals(period);
            for (Metric metric : metrics.values()) {
                long limit = requestedYears == null ? Long.MAX_VALUE : (long) requestedYears * (quarterly ? 4 : 1);
                if (metric.data().stream().map(Fact::end).distinct().count() > limit) {
                    throw new IllegalArgumentException("SEC facts exceed the requested number of periods");
                }
                for (Fact fact : metric.data()) {
                    if (fact.quarterly() != quarterly) throw new IllegalArgumentException("SEC fact form does not match period");
                    if (!sourceUrl.equals(fact.sourceUrl())) throw new IllegalArgumentException("SEC fact source does not match company CIK");
                    if (cutoff != null && LocalDate.parse(fact.filed()).isAfter(cutoff)) {
                        throw new IllegalArgumentException("SEC fact was filed after the requested cutoff");
                    }
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
                    if (fact.hasNonNull("derivation")) {
                        for (String input : List.of("current", "previous")) {
                            if (!fact.path("derivation").path(input).path("value").isNumber()) {
                                throw new IllegalArgumentException("SEC cumulative source value must be numeric");
                            }
                        }
                    }
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
                null, ticker, null, null, 0, Map.of(), true, errorCode, message, retryable, null, null, null, null, null, null, null, null, null, null);
    }

    /** 最新 10-K 资产负债表渲染页原表行，补充 companyfacts 缺的自定义科目和分部列示。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BalanceSheet(String accession, String filed, @JsonProperty("source_url") String sourceUrl, List<String> lines) {
        public BalanceSheet {
            if (accession == null || !accession.matches("[0-9]{10}-[0-9]{2}-[0-9]{6}") || lines == null || lines.isEmpty()
                    || lines.size() > 200 || sourceUrl == null || !sourceUrl.startsWith("https://www.sec.gov/Archives/edgar/data/")) {
                throw new IllegalArgumentException("Invalid SEC balance sheet");
            }
            lines = List.copyOf(lines);
        }
    }

    /**
     * 最新一期申报 XBRL 实例中的单一维度拆分（营收、分部营业利润）；UNAVAILABLE 时只有原因。
     * 各项加总须与当期合并数一致，边界上再核一次，避免把重叠或缺项的拆分当成完整口径。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RevenueBreakdown(String accession, String form, @JsonProperty("source_url") String sourceUrl,
                                   String status, String reason, List<BreakdownTable> tables) {
        public RevenueBreakdown {
            if (accession == null || !accession.matches("[0-9]{10}-[0-9]{2}-[0-9]{6}")
                    || form == null || !Set.of("10-K", "10-K/A", "10-Q", "10-Q/A").contains(form)
                    || sourceUrl == null || !sourceUrl.startsWith("https://www.sec.gov/Archives/edgar/data/")
                    || !Set.of("AVAILABLE", "UNAVAILABLE").contains(status) || tables == null
                    || "AVAILABLE".equals(status) == tables.isEmpty()
                    || "UNAVAILABLE".equals(status) == (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("Invalid SEC revenue breakdown");
            }
            tables = List.copyOf(tables);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BreakdownTable(String metric, String axis, List<BreakdownPeriod> periods) {
        public BreakdownTable {
            if (!Set.of("Revenue", "OperatingIncome").contains(metric)
                    || !Set.of("segment", "geography", "product").contains(axis) || periods == null || periods.isEmpty()) {
                throw new IllegalArgumentException("Invalid SEC breakdown table");
            }
            periods = List.copyOf(periods);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BreakdownPeriod(String start, String end, BigDecimal total, List<BreakdownMember> members) {
        public BreakdownPeriod {
            LocalDate.parse(start);
            LocalDate.parse(end);
            if (total == null || total.signum() == 0 || members == null || members.size() < 2) {
                throw new IllegalArgumentException("Invalid SEC breakdown period");
            }
            BigDecimal sum = members.stream().map(BreakdownMember::value).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.subtract(total).abs().compareTo(total.abs().multiply(new BigDecimal("0.005"))) > 0) {
                throw new IllegalArgumentException("SEC breakdown members do not reconcile to the total");
            }
            members = List.copyOf(members);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BreakdownMember(String name, BigDecimal value) {
        public BreakdownMember {
            if (name == null || name.isBlank() || value == null) throw new IllegalArgumentException("Invalid SEC breakdown member");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metric(String concept, String unit, List<Fact> data) {
        public Metric {
            if (concept == null || !concept.matches("us-gaap:[A-Za-z][A-Za-z0-9]*")
                    || unit == null || !Set.of("USD", "USD/shares", "shares").contains(unit)) {
                throw new IllegalArgumentException("Invalid SEC concept or unit");
            }
            data = List.copyOf(data);
            if (data.isEmpty()) throw new IllegalArgumentException("SEC metric must contain actual facts");
            for (Fact fact : data) {
                if (fact.derivation() != null && (!concept.equals(fact.derivation().current().concept())
                        || !unit.equals(fact.derivation().current().unit()))) {
                    throw new IllegalArgumentException("SEC derived sources must match the metric concept and unit");
                }
            }
        }
    }

    public record CumulativeSource(String concept, String unit, BigDecimal value, String start, String end,
                                   String filed, String form, String accn, @JsonProperty("source_url") String sourceUrl) {
        public CumulativeSource {
            if (concept == null || !concept.matches("us-gaap:[A-Za-z][A-Za-z0-9]*") || !"USD".equals(unit)
                    || value == null || !Set.of("10-Q", "10-Q/A").contains(form) || accn == null || accn.isBlank()
                    || sourceUrl == null) throw new IllegalArgumentException("Invalid SEC cumulative source");
            long days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end));
            if (LocalDate.parse(filed).isBefore(LocalDate.parse(end))
                    || !(days >= 80 && days <= 100 || days >= 160 && days <= 200 || days >= 240 && days <= 300)) {
                throw new IllegalArgumentException("SEC cumulative source requires a Q1, half-year or nine-month flow");
            }
        }
    }

    public record Derivation(String method, CumulativeSource current, CumulativeSource previous) {
        public Derivation {
            if (!"YTD_DIFFERENCE".equals(method) || current == null || previous == null) {
                throw new IllegalArgumentException("SEC quarter derivation requires two cumulative sources");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fact(BigDecimal value, String start, String end, @JsonProperty("period_type") String periodType,
                       String filed, String form, String accn, String fp, String frame,
                       @JsonProperty("fiscal_year") Integer fiscalYear,
                       @JsonProperty("filing_fiscal_year") Integer filingFiscalYear,
                       @JsonProperty("source_url") String sourceUrl, Derivation derivation) {
        public Fact {
            if (value == null || periodType == null || !Set.of("instant", "duration").contains(periodType)
                    || form == null || !Set.of("10-K", "10-K/A", "10-Q", "10-Q/A").contains(form) || accn == null) {
                throw new IllegalArgumentException("Invalid SEC financial fact");
            }
            LocalDate endDate = LocalDate.parse(end);
            if (LocalDate.parse(filed).isBefore(endDate)) throw new IllegalArgumentException("SEC filing predates fact end");
            if ("instant".equals(periodType)) {
                if (start != null) throw new IllegalArgumentException("Instant SEC fact cannot carry a duration start");
            } else {
                long days = ChronoUnit.DAYS.between(LocalDate.parse(start), endDate);
                boolean quarterlyForm = form.startsWith("10-Q");
                if (quarterlyForm ? days < 80 || days > 100 : days < 335 || days > 395) {
                    throw new IllegalArgumentException("SEC fact duration does not match its 10-K/10-Q form");
                }
            }
            if (derivation != null) {
                CumulativeSource current = derivation.current(), previous = derivation.previous();
                long currentDays = ChronoUnit.DAYS.between(LocalDate.parse(current.start()), LocalDate.parse(current.end()));
                long previousDays = ChronoUnit.DAYS.between(LocalDate.parse(previous.start()), LocalDate.parse(previous.end()));
                boolean adjacent = currentDays >= 160 && currentDays <= 200 && previousDays >= 80 && previousDays <= 100
                        || currentDays >= 240 && currentDays <= 300 && previousDays >= 160 && previousDays <= 200;
                if (!"duration".equals(periodType) || !adjacent || !current.start().equals(previous.start())
                        || previous.filed().compareTo(current.filed()) > 0
                        || !LocalDate.parse(previous.end()).plusDays(1).equals(LocalDate.parse(start))
                        || !end.equals(current.end()) || !filed.equals(current.filed()) || !accn.equals(current.accn())
                        || !form.equals(current.form()) || !current.concept().equals(previous.concept())
                        || !current.unit().equals(previous.unit()) || !current.sourceUrl().equals(sourceUrl)
                        || !previous.sourceUrl().equals(sourceUrl) || frame != null
                        || value.compareTo(current.value().subtract(previous.value())) != 0) {
                    throw new IllegalArgumentException("SEC derived quarter does not match cumulative sources");
                }
            }
        }

        /** 10-Q 单季事实；10-K 为年度事实。 */
        public boolean quarterly() {
            return form.startsWith("10-Q");
        }
    }
}
