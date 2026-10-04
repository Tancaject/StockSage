package com.stocksage.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** BaoStock 六类财务摘要；查询标签不能替代供应商实际返回的统计日期。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AShareFinancialsResponse(
        int schemaVersion, Status status, String provider, String market, String code,
        String input, String resolvedCode, String source, String routeReason,
        String period, String requestedPeriod, int requestedYears, String timeKind, String fetchedAt, String asOf,
        String currency, String valueScale, String valueEncoding, String aggregationBasis, int count, List<Report> reports,
        boolean error, String errorCode, String message, Boolean retryable
) {
    public enum Status { SUCCESS, PARTIAL, EMPTY, ERROR }
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    static {
        for (LogicalType type : List.of(LogicalType.Textual, LogicalType.Integer, LogicalType.Boolean)) {
            var coercions = MAPPER.coercionConfigFor(type);
            if (type != LogicalType.Textual) {
                coercions.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
            }
            if (type != LogicalType.Boolean) coercions.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            if (type == LogicalType.Textual || type == LogicalType.Boolean) {
                coercions.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            }
        }
        SimpleModule decimals = new SimpleModule();
        decimals.addDeserializer(BigDecimal.class, new JsonDeserializer<>() {
            @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (!parser.hasToken(JsonToken.VALUE_STRING) || !parser.getText().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
                    throw context.weirdStringException(parser.getValueAsString(), BigDecimal.class, "Expected a plain decimal string");
                }
                return new BigDecimal(parser.getText());
            }
        });
        decimals.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override public void serialize(BigDecimal value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
                generator.writeString(value.toPlainString());
            }
        });
        MAPPER.registerModule(decimals);
    }

    public AShareFinancialsResponse {
        if (schemaVersion != 1 || status == null || !"baostock".equals(provider) || !"A_SHARE".equals(market)
                || !"DATE".equals(timeKind) || !"PROVIDER_RAW".equals(valueScale)
                || !"DECIMAL_STRING".equals(valueEncoding)
                || !"UNKNOWN".equals(aggregationBasis) || currency != null
                || period == null || !Set.of("annual", "quarterly").contains(period)
                || !period.equals(requestedPeriod) || requestedYears < 1 || requestedYears > 10
                || code == null || code.isBlank() || !code.equals(resolvedCode)) {
            throw new IllegalArgumentException("Invalid A-share financials contract");
        }
        Instant.parse(fetchedAt);
        reports = List.copyOf(reports);
        if (count != reports.size() || count > requestedYears * ("annual".equals(period) ? 1 : 4)
                || error != (status == Status.ERROR)
                || (status != aggregate(reports.stream().map(Report::status).toList())
                && !(status == Status.ERROR && reports.isEmpty()))) {
            throw new IllegalArgumentException("Inconsistent A-share report count/status");
        }
        String latest = null;
        Set<String> seenPeriods = new HashSet<>();
        for (Report report : reports) {
            if (("annual".equals(period) && report.quarter() != 4)
                    || !seenPeriods.add(report.year() + ":" + report.quarter())) {
                throw new IllegalArgumentException("A-share report periods must be unique and match the requested period");
            }
            for (Statement<?> statement : report.statements().all()) {
                if (statement.status() == Status.SUCCESS && !code.equals(statement.data().code())) {
                    throw new IllegalArgumentException("A-share statement target differs from resolved code");
                }
            }
            if (report.asOf() != null && (latest == null || report.asOf().compareTo(latest) > 0)) latest = report.asOf();
        }
        if (!Objects.equals(latest, asOf)) throw new IllegalArgumentException("A-share asOf must match actual statement dates");
    }

    public static AShareFinancialsResponse fromJson(JsonNode root) {
        try {
            if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("count").isIntegralNumber() || !root.path("requestedYears").isIntegralNumber()
                    || !root.path("error").isBoolean() || !root.path("reports").isArray()) {
                throw new IllegalArgumentException("Missing A-share version/count/scope/reports");
            }
            for (JsonNode report : root.get("reports")) {
                if (!report.path("year").isIntegralNumber() || !report.path("quarter").isIntegralNumber()) {
                    throw new IllegalArgumentException("A-share report year and quarter must be integers");
                }
            }
            return MAPPER.treeToValue(root, AShareFinancialsResponse.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid A-share financials v1 response", error);
        }
    }

    /** 此供应商原始值是十进制字符串，局部序列化防止金额经浮点数中转。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot serialize A-share financials response", error);
        }
    }

    private static Status aggregate(List<Status> statuses) {
        boolean usable = statuses.stream().anyMatch(status -> status == Status.SUCCESS || status == Status.PARTIAL);
        if (usable) return statuses.stream().allMatch(status -> status == Status.SUCCESS) ? Status.SUCCESS : Status.PARTIAL;
        return statuses.contains(Status.ERROR) ? Status.ERROR : Status.EMPTY;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Report(int year, int quarter, Status status, String asOf, Statements statements) {
        public Report {
            if (year < 1 || year > 9999 || quarter < 1 || quarter > 4 || statements == null) {
                throw new IllegalArgumentException("Invalid A-share report period");
            }
            LocalDate month = LocalDate.of(year, quarter * 3, 1);
            String expectedDate = month.withDayOfMonth(month.lengthOfMonth()).toString();
            List<Statement<?>> all = statements.all();
            if (status != aggregate(all.stream().map(Statement::status).toList())) {
                throw new IllegalArgumentException("A-share report status differs from its statements");
            }
            String actualDate = null;
            for (Statement<?> statement : all) {
                if (statement.status() == Status.SUCCESS) {
                    if (!expectedDate.equals(statement.data().statDate())) {
                        throw new IllegalArgumentException("A-share statement date differs from queried period");
                    }
                    actualDate = statement.data().statDate();
                }
            }
            if (!Objects.equals(actualDate, asOf)) throw new IllegalArgumentException("A-share report asOf must come from a usable statement");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Statements(Statement<Profit> profit, Statement<Operation> operation, Statement<Growth> growth,
                             Statement<Balance> balance, Statement<CashFlow> cashFlow, Statement<Dupont> dupont) {
        public List<Statement<?>> all() { return List.of(profit, operation, growth, balance, cashFlow, dupont); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Statement<T extends StatementData>(Status status, T data, String errorCode, String message, Boolean retryable) {
        public Statement {
            if (status == null || status == Status.PARTIAL || (status == Status.SUCCESS) != (data != null)) {
                throw new IllegalArgumentException("A-share statement data/status disagree");
            }
            if (status == Status.ERROR ? errorCode == null || errorCode.isBlank() : errorCode != null || retryable != null) {
                throw new IllegalArgumentException("A-share statement failure metadata does not match its status");
            }
            if (data != null) {
                if (data.code() == null || data.code().isBlank() || !data.hasValues()
                        || LocalDate.parse(data.pubDate()).isBefore(LocalDate.parse(data.statDate()))) {
                    throw new IllegalArgumentException("A-share statement requires actual dates and numeric values");
                }
            }
        }
    }

    public interface StatementData {
        String code();
        String pubDate();
        String statDate();
        boolean hasValues();
    }

    private static boolean hasValue(BigDecimal... values) { return Arrays.stream(values).anyMatch(Objects::nonNull); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profit(String code, String pubDate, String statDate, BigDecimal roeAvg, BigDecimal npMargin,
                         BigDecimal gpMargin, BigDecimal netProfit, BigDecimal epsTTM, @JsonProperty("MBRevenue") BigDecimal mbRevenue,
                         BigDecimal totalShare, BigDecimal liqaShare, String netProfitUnit, @JsonProperty("MBRevenueUnit") String mbRevenueUnit) implements StatementData {
        public Profit {
            if (!"YUAN".equals(netProfitUnit) || !"YUAN".equals(mbRevenueUnit)) throw new IllegalArgumentException("Invalid BaoStock profit units");
        }
        public boolean hasValues() { return hasValue(roeAvg, npMargin, gpMargin, netProfit, epsTTM, mbRevenue, totalShare, liqaShare); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Operation(String code, String pubDate, String statDate,
                            @JsonProperty("NRTurnRatio") BigDecimal nrTurnRatio, @JsonProperty("NRTurnDays") BigDecimal nrTurnDays,
                            @JsonProperty("INVTurnRatio") BigDecimal invTurnRatio, @JsonProperty("INVTurnDays") BigDecimal invTurnDays,
                            @JsonProperty("CATurnRatio") BigDecimal caTurnRatio, @JsonProperty("AssetTurnRatio") BigDecimal assetTurnRatio,
                            @JsonProperty("NRTurnDaysUnit") String nrTurnDaysUnit, @JsonProperty("INVTurnDaysUnit") String invTurnDaysUnit) implements StatementData {
        public Operation {
            if (!"DAY".equals(nrTurnDaysUnit) || !"DAY".equals(invTurnDaysUnit)) throw new IllegalArgumentException("Invalid BaoStock operation units");
        }
        public boolean hasValues() { return hasValue(nrTurnRatio, nrTurnDays, invTurnRatio, invTurnDays, caTurnRatio, assetTurnRatio); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Growth(String code, String pubDate, String statDate,
                         @JsonProperty("YOYEquity") BigDecimal yoyEquity, @JsonProperty("YOYAsset") BigDecimal yoyAsset,
                         @JsonProperty("YOYNI") BigDecimal yoyNi, @JsonProperty("YOYEPSBasic") BigDecimal yoyEpsBasic,
                         @JsonProperty("YOYPNI") BigDecimal yoyPni) implements StatementData {
        public boolean hasValues() { return hasValue(yoyEquity, yoyAsset, yoyNi, yoyEpsBasic, yoyPni); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Balance(String code, String pubDate, String statDate, BigDecimal currentRatio, BigDecimal quickRatio,
                          BigDecimal cashRatio, @JsonProperty("YOYLiability") BigDecimal yoyLiability,
                          BigDecimal liabilityToAsset, BigDecimal assetToEquity) implements StatementData {
        public boolean hasValues() { return hasValue(currentRatio, quickRatio, cashRatio, yoyLiability, liabilityToAsset, assetToEquity); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CashFlow(String code, String pubDate, String statDate,
                           @JsonProperty("CAToAsset") BigDecimal caToAsset, @JsonProperty("NCAToAsset") BigDecimal ncaToAsset,
                           BigDecimal tangibleAssetToAsset, BigDecimal ebitToInterest, @JsonProperty("CFOToOR") BigDecimal cfoToOr,
                           @JsonProperty("CFOToNP") BigDecimal cfoToNp, @JsonProperty("CFOToGr") BigDecimal cfoToGr) implements StatementData {
        public boolean hasValues() { return hasValue(caToAsset, ncaToAsset, tangibleAssetToAsset, ebitToInterest, cfoToOr, cfoToNp, cfoToGr); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dupont(String code, String pubDate, String statDate, BigDecimal dupontROE, BigDecimal dupontAssetStoEquity,
                         BigDecimal dupontAssetTurn, BigDecimal dupontPnitoni, BigDecimal dupontNitogr, BigDecimal dupontTaxBurden,
                         BigDecimal dupontIntburden, BigDecimal dupontEbittogr) implements StatementData {
        public boolean hasValues() { return hasValue(dupontROE, dupontAssetStoEquity, dupontAssetTurn, dupontPnitoni, dupontNitogr, dupontTaxBurden, dupontIntburden, dupontEbittogr); }
    }
}
