package com.stocksage.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 港股披露报告期与请求季度标签分开；金额仅保留供应商精度，不推断币种、缩放或单季口径。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HkFinancialsResponse(
        int schemaVersion, Status status, String provider, String market, String symbol,
        String input, String resolvedCode, String source, String routeReason,
        String period, String requestedPeriod, int requestedYears, String timeKind, String fetchedAt, String asOf,
        String currency, String valueScale, String valueEncoding, String numericPrecision, String aggregationBasis,
        int statementCount, Statements statements, Table<IndicatorRow> indicators,
        boolean error, String errorCode, String message, Boolean retryable
) {
    public enum Status { SUCCESS, PARTIAL, EMPTY, ERROR }
    private static final ObjectMapper MAPPER = StrictResponseJson.newDecimalStringMapper();

    public HkFinancialsResponse {
        if (schemaVersion != 1 || status == null || !"akshare".equals(provider) || !"HK".equals(market)
                || !"DATE".equals(timeKind) || !"PROVIDER_RAW".equals(valueScale) || !"DECIMAL_STRING".equals(valueEncoding)
                || !"PROVIDER_VALUE".equals(numericPrecision) || !"UNKNOWN".equals(aggregationBasis) || currency != null
                || requestedPeriod == null || !Set.of("annual", "quarterly").contains(requestedPeriod)
                || !("annual".equals(requestedPeriod) ? "annual" : "report_period").equals(period)
                || requestedYears < 1 || requestedYears > 10 || symbol == null || !symbol.matches("[0-9]{5}")
                || !symbol.equals(routeSymbol(resolvedCode))
                || input == null || source == null || routeReason == null || statements == null || indicators == null) {
            throw new IllegalArgumentException("Invalid HK financials contract");
        }
        Instant.parse(fetchedAt);
        List<Table<? extends FinancialRow>> tables = List.of(statements.balanceSheet(), statements.incomeStatement(), statements.cashFlow(), indicators);
        List<Status> statuses = tables.stream().map(Table::status).toList();
        Status actualStatus = aggregate(statuses);
        if (statementCount != tables.stream().mapToInt(table -> table.data().size()).sum()
                || error != (status == Status.ERROR)
                || (status == Status.ERROR ? errorCode == null || errorCode.isBlank() : errorCode != null || retryable != null)
                || (status != actualStatus && !(status == Status.ERROR && actualStatus == Status.EMPTY))) {
            throw new IllegalArgumentException("Inconsistent HK financial table status/count");
        }
        String latest = null;
        int maxPeriods = requestedYears * ("annual".equals(period) ? 1 : 4);
        for (Table<? extends FinancialRow> table : tables) {
            if (table.data().stream().map(FinancialRow::reportDate).distinct().count() > maxPeriods) {
                throw new IllegalArgumentException("HK financial rows exceed the requested periods");
            }
            for (FinancialRow row : table.data()) {
                if (!symbol.equals(row.securityCode()) || !(symbol + ".HK").equals(row.secuCode())) {
                    throw new IllegalArgumentException("HK financial row identity differs from the resolved symbol");
                }
                if (row.hasValues() && (latest == null || row.reportDate().compareTo(latest) > 0)) latest = row.reportDate();
            }
        }
        if (!Objects.equals(latest, asOf)) throw new IllegalArgumentException("HK asOf must match a usable row's actual report date");
    }

    public static HkFinancialsResponse fromJson(JsonNode root) {
        try {
            if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("statementCount").isIntegralNumber() || !root.path("requestedYears").isIntegralNumber()
                    || !root.path("error").isBoolean() || !root.path("statements").isObject() || !root.path("indicators").isObject()) {
                throw new IllegalArgumentException("Missing HK financials version/count/scope/tables");
            }
            return MAPPER.treeToValue(root, HkFinancialsResponse.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid HK financials v1 response", error);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot serialize HK financials response", error);
        }
    }

    private static Status aggregate(List<Status> statuses) {
        if (statuses.stream().allMatch(status -> status == Status.SUCCESS)) return Status.SUCCESS;
        if (statuses.contains(Status.SUCCESS)) return Status.PARTIAL;
        return statuses.contains(Status.ERROR) ? Status.ERROR : Status.EMPTY;
    }

    private static String routeSymbol(String resolvedCode) {
        if (resolvedCode == null || !resolvedCode.matches("[0-9]{4,5}\\.HK")) return null;
        String code = resolvedCode.substring(0, resolvedCode.length() - 3);
        return "0".repeat(5 - code.length()) + code;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Statements(Table<StatementRow> balanceSheet, Table<StatementRow> incomeStatement, Table<StatementRow> cashFlow) {
        public List<Table<StatementRow>> all() { return List.of(balanceSheet, incomeStatement, cashFlow); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Table<T extends FinancialRow>(Status status, List<T> data, String errorCode, String message, Boolean retryable) {
        public Table {
            data = List.copyOf(data);
            if (status == null || status == Status.PARTIAL
                    || (status == Status.SUCCESS ? data.stream().noneMatch(FinancialRow::hasValues) : !data.isEmpty())
                    || (status == Status.ERROR ? errorCode == null || errorCode.isBlank() : errorCode != null || retryable != null)) {
                throw new IllegalArgumentException("HK financial table data or failure metadata disagrees with its status");
            }
            for (FinancialRow row : data) {
                LocalDate reportDate = businessDate(row.reportDate());
                if (row.startDate() != null && businessDate(row.startDate()).isAfter(reportDate)) {
                    throw new IllegalArgumentException("HK financial row start date is after its report date");
                }
                if (row.stdReportDate() != null) businessDate(row.stdReportDate());
            }
        }
    }

    public interface FinancialRow {
        String secuCode();
        String securityCode();
        String reportDate();
        String startDate();
        String stdReportDate();
        boolean hasValues();
    }

    private static LocalDate businessDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000")) {
            throw new IllegalArgumentException("HK financial dates must be YYYY-MM-DD business dates");
        }
        return LocalDate.parse(value);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatementRow(
            @JsonProperty("SECUCODE") String secuCode, @JsonProperty("SECURITY_CODE") String securityCode,
            @JsonProperty("REPORT_DATE") String reportDate, @JsonProperty("SECURITY_NAME_ABBR") String securityNameAbbr,
            @JsonProperty("ORG_CODE") String orgCode, @JsonProperty("DATE_TYPE_CODE") String dateTypeCode,
            @JsonProperty("FISCAL_YEAR") String fiscalYear, @JsonProperty("START_DATE") String startDate,
            @JsonProperty("STD_REPORT_DATE") String stdReportDate, @JsonProperty("STD_ITEM_CODE") String itemCode,
            @JsonProperty("STD_ITEM_NAME") String itemName, @JsonProperty("AMOUNT") BigDecimal amount, String amountUnit
    ) implements FinancialRow {
        public StatementRow {
            if (itemCode == null || itemCode.isBlank() || itemName == null || itemName.isBlank() || !"UNKNOWN".equals(amountUnit)) {
                throw new IllegalArgumentException("HK statement item identity and unknown amount unit are required");
            }
        }
        public boolean hasValues() { return amount != null; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndicatorRow(
            @JsonProperty("SECUCODE") String secuCode, @JsonProperty("SECURITY_CODE") String securityCode,
            @JsonProperty("REPORT_DATE") String reportDate, @JsonProperty("SECURITY_NAME_ABBR") String securityNameAbbr,
            @JsonProperty("ORG_CODE") String orgCode, @JsonProperty("DATE_TYPE_CODE") String dateTypeCode,
            @JsonProperty("FISCAL_YEAR") String fiscalYear, @JsonProperty("START_DATE") String startDate,
            @JsonProperty("STD_REPORT_DATE") String stdReportDate, @JsonProperty("CURRENCY") String currency,
            @JsonProperty("IS_CNY_CODE") Long isCnyCode,
            @JsonProperty("PER_NETCASH_OPERATE") BigDecimal perNetcashOperate, @JsonProperty("PER_OI") BigDecimal perOi,
            @JsonProperty("BPS") BigDecimal bps, @JsonProperty("BASIC_EPS") BigDecimal basicEps,
            @JsonProperty("DILUTED_EPS") BigDecimal dilutedEps, @JsonProperty("OPERATE_INCOME") BigDecimal operateIncome,
            @JsonProperty("OPERATE_INCOME_YOY") BigDecimal operateIncomeYoy, @JsonProperty("GROSS_PROFIT") BigDecimal grossProfit,
            @JsonProperty("GROSS_PROFIT_YOY") BigDecimal grossProfitYoy, @JsonProperty("HOLDER_PROFIT") BigDecimal holderProfit,
            @JsonProperty("HOLDER_PROFIT_YOY") BigDecimal holderProfitYoy, @JsonProperty("GROSS_PROFIT_RATIO") BigDecimal grossProfitRatio,
            @JsonProperty("EPS_TTM") BigDecimal epsTtm, @JsonProperty("OPERATE_INCOME_QOQ") BigDecimal operateIncomeQoq,
            @JsonProperty("NET_PROFIT_RATIO") BigDecimal netProfitRatio, @JsonProperty("ROE_AVG") BigDecimal roeAvg,
            @JsonProperty("GROSS_PROFIT_QOQ") BigDecimal grossProfitQoq, @JsonProperty("ROA") BigDecimal roa,
            @JsonProperty("HOLDER_PROFIT_QOQ") BigDecimal holderProfitQoq, @JsonProperty("ROE_YEARLY") BigDecimal roeYearly,
            @JsonProperty("ROIC_YEARLY") BigDecimal roicYearly, @JsonProperty("TAX_EBT") BigDecimal taxEbt,
            @JsonProperty("OCF_SALES") BigDecimal ocfSales, @JsonProperty("DEBT_ASSET_RATIO") BigDecimal debtAssetRatio,
            @JsonProperty("CURRENT_RATIO") BigDecimal currentRatio, @JsonProperty("CURRENTDEBT_DEBT") BigDecimal currentdebtDebt
    ) implements FinancialRow {
        public boolean hasValues() {
            return Arrays.stream(new BigDecimal[]{perNetcashOperate, perOi, bps, basicEps, dilutedEps, operateIncome,
                    operateIncomeYoy, grossProfit, grossProfitYoy, holderProfit, holderProfitYoy, grossProfitRatio, epsTtm,
                    operateIncomeQoq, netProfitRatio, roeAvg, grossProfitQoq, roa, holderProfitQoq, roeYearly, roicYearly,
                    taxEbt, ocfSales, debtAssetRatio, currentRatio, currentdebtDebt}).anyMatch(Objects::nonNull);
        }
    }
}
