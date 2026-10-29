package com.stocksage.research.finance;

import com.stocksage.client.SecFinancialsResponse;
import com.stocksage.client.SecFinancialsResponse.Fact;
import com.stocksage.client.SecFinancialsResponse.Metric;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 固定财务公式；只有实际期间、单位及概念符合所列条件才输出数值。 */
public final class FinancialCalculations {
    private FinancialCalculations() {}
    private static final MathContext MC = MathContext.DECIMAL64;

    public static Map<String, Object> diagnostics(SecFinancialsResponse source) {
        var result = base("financial_diagnostics", source);
        if (source.error()) return result;
        var calculations = new ArrayList<Map<String, Object>>();
        calculations.add(cagr(source, "Revenue", "revenue_cagr"));
        calculations.add(cagr(source, "NetIncome", "net_income_cagr"));
        calculations.add(cagr(source, "OperatingCashFlow", "operating_cash_flow_cagr"));
        calculations.add(netDebt(source));
        finish(result, "calculations", calculations);
        return result;
    }

    private static Map<String, Object> cagr(SecFinancialsResponse source, String name, String id) {
        Metric metric = source.metrics().get(name);
        if (!"annual".equals(source.period()) || metric == null || !"USD".equals(metric.unit())) {
            return gap(id, "CAGR 需要至少两个连续完整年度，不能把单季值年化。");
        }
        List<Fact> facts = metric.data().stream().sorted(Comparator.comparing(Fact::end)).toList();
        if (facts.size() < 2 || !facts.get(facts.size() - 1).end().equals(source.asOf())
                || facts.stream().anyMatch(fact -> fact.start() == null)) {
            return gap(id, "缺少覆盖当前期末的至少两个完整年度。");
        }
        for (int index = 1; index < facts.size(); index++) {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(facts.get(index - 1).end()), LocalDate.parse(facts.get(index).end()));
            if (days < 335 || days > 395 || !LocalDate.parse(facts.get(index).start())
                    .equals(LocalDate.parse(facts.get(index - 1).end()).plusDays(1))) {
                return gap(id, "年度不连续、期间有缺口或同一期末有多个期间，不能用行数代替年数。");
            }
        }
        Fact first = facts.get(0), last = facts.get(facts.size() - 1);
        if (first.value().signum() <= 0 || last.value().signum() <= 0) return gap(id, "CAGR 的起点和终点必须为正；负数或零基数不适用。");
        long elapsedDays = ChronoUnit.DAYS.between(LocalDate.parse(first.end()), LocalDate.parse(last.end()));
        BigDecimal years = BigDecimal.valueOf(elapsedDays).divide(new BigDecimal("365.2425"), 8, RoundingMode.HALF_UP);
        double growth = (Math.pow(last.value().divide(first.value(), MC).doubleValue(), 1.0 / years.doubleValue()) - 1) * 100;
        if (!Double.isFinite(growth)) return gap(id, "数值范围无法产生有限 CAGR。");
        var row = value(id, BigDecimal.valueOf(growth).setScale(4, RoundingMode.HALF_UP), "percent",
                "((ending / beginning) ^ (365.2425 / elapsedDays) - 1) * 100", List.of(input(name, metric, first), input(name, metric, last)));
        row.put("elapsedDays", elapsedDays); row.put("yearFraction", years); row.put("yearBasis", "ACT/365.2425");
        row.put("start", first.end());
        row.put("end", last.end());
        return row;
    }

    private static Map<String, Object> netDebt(SecFinancialsResponse source) {
        Metric current = source.metrics().get("DebtCurrent");
        Metric noncurrent = source.metrics().get("LongTermDebtNoncurrent");
        if (noncurrent == null) {
            Metric candidate = source.metrics().get("TotalDebt");
            if (candidate != null && candidate.concept().equals("us-gaap:LongTermDebtNoncurrent")) noncurrent = candidate;
        }
        Metric cash = source.metrics().get("Cash");
        if (current == null || noncurrent == null || cash == null
                || !"us-gaap:DebtCurrent".equals(current.concept())
                || !"us-gaap:LongTermDebtNoncurrent".equals(noncurrent.concept())
                || !"us-gaap:CashAndCashEquivalentsAtCarryingValue".equals(cash.concept())) {
            return gap("net_debt", "需明确的流动有息债务、非流动长期债务和不含受限现金的现金科目；长期债务不能替代总债务，缺项不补零。");
        }
        Fact a = at(current, null, source.asOf()), b = at(noncurrent, null, source.asOf()), c = at(cash, null, source.asOf());
        if (!sameUnit(current, noncurrent, cash) || a == null || b == null || c == null
                || a.value().signum() < 0 || b.value().signum() < 0 || c.value().signum() < 0) {
            return gap("net_debt", "净债务输入缺失、冲突、币种不一致或不在同一最新期末。");
        }
        var row = value("net_debt", a.value().add(b.value()).subtract(c.value()), current.unit(),
                "DebtCurrent + LongTermDebtNoncurrent - unrestrictedCash",
                List.of(input("DebtCurrent", current, a), input("LongTermDebtNoncurrent", noncurrent, b), input("Cash", cash, c)));
        row.put("end", a.end());
        row.put("basis", "仅覆盖这两个债务科目，不另加可能重复的商业票据或租赁；负数表示净现金。");
        return row;
    }

    public static Map<String, Object> compare(SecFinancialsResponse left, SecFinancialsResponse right) {
        var result = base("financial_comparison", left);
        result.put("targets", List.of(left.ticker(), right.ticker()));
        result.put("sources", List.of(source(left), source(right)));
        if (left.error() || right.error()) {
            result.put("status", "ERROR"); result.put("error", true);
            result.put("message", "至少一家公司的 SEC 财务读取失败；不能用另一家公司替代。");
            return result;
        }
        var comparisons = new ArrayList<Map<String, Object>>();
        for (String name : List.of("Revenue", "NetIncome", "OperatingIncome", "OperatingCashFlow", "CapitalExpenditure",
                "EPS", "TotalAssets", "TotalLiabilities", "Cash")) {
            Metric a = left.metrics().get(name), b = right.metrics().get(name);
            var row = gap(name, "两侧缺少可对齐的事实。");
            row.put("metric", name);
            if (!left.period().equals(right.period()) || !Objects.equals(left.requestedAsOf(), right.requestedAsOf())) {
                row.put("reason", "请求期间或申报信息截止日不一致。");
            } else if (a != null && b != null) {
                if (!a.concept().equals(b.concept()) || !a.unit().equals(b.unit())
                        || !("EPS".equals(name) ? "USD/shares" : "USD").equals(a.unit())) {
                    row.put("reason", "CONCEPT_OR_CURRENCY_MISMATCH：概念或单位不一致，不做数值比较。");
                    row.put("leftConcept", a.concept()); row.put("rightConcept", b.concept());
                    row.put("leftUnit", a.unit()); row.put("rightUnit", b.unit());
                } else {
                    var common = a.data().stream().filter(fact -> b.data().stream().anyMatch(other -> samePeriod(fact, other)))
                            .max(Comparator.comparing(Fact::end).thenComparing(fact -> fact.start() == null ? "" : fact.start())).orElse(null);
                    if (common != null) {
                        Fact av = at(a, common.start(), common.end()), bv = at(b, common.start(), common.end());
                        if (av != null && bv != null) {
                            row = value(name, av.value().subtract(bv.value()), a.unit(), "left - right",
                                    List.of(input(name, a, av), input(name, b, bv)));
                            row.put("metric", name); row.put("leftTicker", left.ticker()); row.put("rightTicker", right.ticker());
                            row.put("leftValue", av.value()); row.put("rightValue", bv.value());
                            row.put("start", av.start()); row.put("end", av.end());
                            row.put("basis", "两侧严格相同概念、币种和实际期间；这是最近共同期间，不一定是各自最新财年。");
                        } else row.put("reason", "同一实际期间存在冲突或多份事实，不能任选。");
                    } else row.put("reason", "PERIOD_MISMATCH：没有相同 start/end/period_type，未按财年标签强行对齐。");
                }
            }
            comparisons.add(row);
        }
        finish(result, "comparisons", comparisons);
        return result;
    }

    public static Map<String, Object> semiconductor(SecFinancialsResponse source) {
        var result = base("semiconductor_operating_metrics", source);
        result.put("scope", "库存驱动经营分析；不自动认定公司属于半导体行业，不推断产能、销量或库存数量。");
        if (source.error()) return result;
        var calculations = new ArrayList<Map<String, Object>>();
        Metric inventory = source.metrics().get("Inventory"), revenue = source.metrics().get("Revenue"), cost = source.metrics().get("CostOfRevenue");
        Fact closing = at(inventory, null, source.asOf());
        Fact sales = latestDuration(revenue, source.asOf());
        if (closing != null && sales != null && sameUnit(inventory, revenue) && closing.value().signum() >= 0 && sales.value().signum() > 0) {
            var row = value("inventory_to_revenue", divide(closing.value().multiply(new BigDecimal("100")), sales.value()), "percent",
                    "endingInventory / periodRevenue * 100", List.of(input("Inventory", inventory, closing), input("Revenue", revenue, sales)));
            row.put("start", sales.start()); row.put("end", sales.end());
            calculations.add(row);
        } else calculations.add(gap("inventory_to_revenue", "需同一期末库存和同币种期间营收，营收必须为正。"));
        Fact expense = latestDuration(cost, source.asOf());
        Fact opening = expense == null ? null : at(inventory, null, LocalDate.parse(expense.start()).minusDays(1).toString());
        if (opening != null && closing != null && expense != null && sameUnit(inventory, cost)
                && opening.value().signum() >= 0 && closing.value().signum() >= 0 && expense.value().signum() > 0
                && opening.value().add(closing.value()).signum() > 0) {
            BigDecimal average = opening.value().add(closing.value()).divide(new BigDecimal("2"));
            long days = ChronoUnit.DAYS.between(LocalDate.parse(expense.start()), LocalDate.parse(expense.end())) + 1;
            var inputs = List.of(input("InventoryOpening", inventory, opening), input("InventoryClosing", inventory, closing), input("CostOfRevenue", cost, expense));
            var turns = value("inventory_turnover", divide(expense.value(), average), "times_per_period", "periodCost / ((openingInventory + endingInventory) / 2)", inputs);
            var dayRow = value("inventory_days", divide(average.multiply(BigDecimal.valueOf(days)), expense.value()), "days",
                    "((openingInventory + endingInventory) / 2) / periodCost * actualPeriodDays", inputs);
            for (var row : List.of(turns, dayRow)) { row.put("start", expense.start()); row.put("end", expense.end()); row.put("actualPeriodDays", days); }
            calculations.add(turns); calculations.add(dayRow);
        } else {
            calculations.add(gap("inventory_turnover", "缺少成本期间开始前一天的库存、期末库存或同期间成本；不以期末值冒充平均库存。"));
            calculations.add(gap("inventory_days", "平均库存和成本需同币种且分母为正，期间按实际天数计算。"));
        }
        calculations.add(cagr(source, "Revenue", "revenue_cagr"));
        finish(result, "calculations", calculations);
        return result;
    }

    private static Fact latestDuration(Metric metric, String end) {
        if (metric == null) return null;
        var matches = metric.data().stream().filter(fact -> fact.start() != null && fact.end().equals(end)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private static Fact at(Metric metric, String start, String end) {
        if (metric == null || end == null) return null;
        var matches = metric.data().stream().filter(fact -> Objects.equals(fact.start(), start) && end.equals(fact.end())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private static boolean samePeriod(Fact left, Fact right) {
        return Objects.equals(left.start(), right.start()) && left.end().equals(right.end()) && left.periodType().equals(right.periodType());
    }
    private static boolean sameUnit(Metric... metrics) {
        if (metrics.length == 0 || metrics[0] == null || !"USD".equals(metrics[0].unit())) return false;
        for (Metric metric : metrics) if (metric == null || !metrics[0].unit().equals(metric.unit())) return false;
        return true;
    }
    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }
    private static Map<String, Object> input(String name, Metric metric, Fact fact) {
        return Map.of("metric", name, "concept", metric.concept(), "unit", metric.unit(), "fact", fact);
    }
    private static Map<String, Object> value(String id, BigDecimal value, String unit, String formula, List<Map<String, Object>> inputs) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id); row.put("status", "AVAILABLE"); row.put("value", value); row.put("unit", unit);
        row.put("formula", formula); row.put("inputs", inputs);
        return row;
    }
    private static Map<String, Object> gap(String id, String reason) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id); row.put("status", "UNAVAILABLE"); row.put("value", null); row.put("reason", reason);
        return row;
    }
    private static Map<String, Object> source(SecFinancialsResponse source) {
        var result = new LinkedHashMap<String, Object>();
        result.put("ticker", source.ticker()); result.put("cik", source.cik()); result.put("period", source.period());
        result.put("requestedAsOf", source.requestedAsOf()); result.put("fetchedAt", source.fetchedAt()); result.put("asOf", source.asOf());
        result.put("status", source.status()); result.put("message", source.message());
        return result;
    }
    private static Map<String, Object> base(String kind, SecFinancialsResponse source) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 1); result.put("kind", kind); result.put("provider", "SEC_EDGAR"); result.put("calculationProvider", "STOCKSAGE");
        result.putAll(source(source)); result.put("source", source(source)); result.put("timeKind", "DATE");
        result.put("error", source.error()); result.put("status", source.error() ? "ERROR" : "EMPTY");
        if (source.cik() != null) result.put("sourceRef", "https://data.sec.gov/api/xbrl/companyfacts/CIK" + source.cik() + ".json");
        result.put("informationBoundary", source.requestedAsOf() == null ? "当前可取得的 SEC 事实。"
                : "仅用截止日前已申报且今天仍可取得的事实；不宣称完整重建当年的数据档案。");
        return result;
    }
    private static void finish(Map<String, Object> result, String key, List<Map<String, Object>> rows) {
        long available = rows.stream().filter(row -> "AVAILABLE".equals(row.get("status"))).count();
        result.put(key, rows); result.put("status", available == 0 ? "EMPTY" : available == rows.size() ? "SUCCESS" : "PARTIAL");
        result.put("availableCount", available); result.put("unavailableCount", rows.size() - available);
    }
}
