package com.stocksage.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.*;

/** 用户提供表的确定性计算；不同币种保持分组，未提供数据保持未知。 */
@Service
public class PortfolioAnalysisService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private final ObjectMapper mapper;

    public PortfolioAnalysisService(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode analyzePortfolio(JsonNode input) {
        LocalDate asOf = date(object(input, "input"), "asOf");
        var positions = array(input, "positions", 200, true);
        Map<String, List<Position>> groups = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        Set<String> symbols = new HashSet<>();
        for (var item : positions) {
            object(item, "positions[]");
            String symbol = symbol(item, "symbol"), currency = currency(item);
            unique(keys, currency + ":" + symbol, "positions");
            String kind = text(item, "kind").toUpperCase(Locale.ROOT);
            require(Set.of("CASH", "SECURITY").contains(kind), "kind 必须为 CASH 或 SECURITY");
            BigDecimal quantity = number(item, "quantity"), price = number(item, "price");
            require(price.signum() > 0, "price 必须大于 0");
            require(!kind.equals("CASH") || price.compareTo(BigDecimal.ONE) == 0,
                    "CASH 的 quantity 表示本币金额，price 必须为 1");
            groups.computeIfAbsent(currency, k -> new ArrayList<>()).add(
                    new Position(symbol, currency, quantity.multiply(price), kind));
            symbols.add(symbol);
        }
        var scenarios = array(input, "scenarios", 20, false);
        Set<String> names = new HashSet<>();
        for (var scenario : scenarios) {
            unique(names, text(scenario, "name"), "scenarios.name");
            var returns = object(scenario.get("returns"), "scenarios[].returns");
            var fields = returns.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                require(symbols.contains(entry.getKey()), "情景代码不在持仓中: " + entry.getKey());
                require(number(returns, entry.getKey()).compareTo(BigDecimal.ONE.negate()) >= 0,
                        "价格变化率不能低于 -1: " + entry.getKey());
                for (var group : groups.values()) for (var position : group)
                    if (position.symbol().equals(entry.getKey()) && position.kind().equals("CASH"))
                        require(number(returns, entry.getKey()).signum() == 0, "本币现金情景变化率必须为 0: " + entry.getKey());
            }
        }
        ObjectNode result = base(asOf);
        result.put("valuationBasis", "按输入价格估值；不同币种不合计；情景不包含汇率、费用和税费变化");
        result.put("formula", "marketValue=quantity*price; grossWeight=abs(value)/sum(abs(value)); netWeight=value/netValue; HHI=sum(grossWeight^2); pnl=value*return");
        ArrayNode output = result.putArray("currencyGroups");
        boolean partial = false;
        for (var entry : groups.entrySet()) {
            BigDecimal net = BigDecimal.ZERO, gross = BigDecimal.ZERO, cash = BigDecimal.ZERO, hhi = BigDecimal.ZERO;
            for (var p : entry.getValue()) {
                net = net.add(p.value()); gross = gross.add(p.value().abs());
                if (p.kind().equals("CASH")) cash = cash.add(p.value());
            }
            ObjectNode group = output.addObject().put("currency", entry.getKey());
            group.put("netValue", net).put("grossValue", gross).put("cashValue", cash);
            var rows = group.putArray("positions");
            for (var p : entry.getValue()) {
                var row = rows.addObject().put("symbol", p.symbol()).put("kind", p.kind()).put("marketValue", p.value());
                ratio(row, "netWeight", p.value(), net);
                ratio(row, "grossWeight", p.value().abs(), gross);
                if (gross.signum() != 0) {
                    BigDecimal weight = p.value().abs().divide(gross, MC);
                    hhi = hhi.add(weight.multiply(weight, MC));
                }
            }
            if (gross.signum() == 0) group.putNull("hhi"); else group.put("hhi", hhi);
            ArrayNode scenarioRows = group.putArray("scenarios");
            for (var scenario : scenarios) {
                var row = scenarioRows.addObject().put("name", scenario.path("name").asText());
                ArrayNode unknown = row.putArray("unknownSymbols");
                BigDecimal pnl = BigDecimal.ZERO;
                for (var p : entry.getValue()) {
                    if (p.kind().equals("CASH") || p.value().signum() == 0) continue;
                    JsonNode shock = scenario.path("returns").get(p.symbol());
                    if (shock == null) unknown.add(p.symbol());
                    else pnl = pnl.add(p.value().multiply(shock.decimalValue()));
                }
                row.put("knownPnl", pnl);
                if (unknown.isEmpty()) row.put("totalPnl", pnl); else { row.putNull("totalPnl"); partial = true; }
            }
        }
        correlations(input, asOf, symbols, result.putArray("correlations"));
        for (var correlation : result.path("correlations")) if (correlation.path("correlation").isNull()) partial = true;
        result.put("status", partial ? "PARTIAL" : "SUCCESS");
        return result;
    }

    private void correlations(JsonNode input, LocalDate asOf, Set<String> symbols, ArrayNode output) {
        if (!input.has("prices")) return;
        var prices = object(input.get("prices"), "prices");
        require(prices.size() <= 20, "prices 最多 20 个证券");
        Map<String, TreeMap<LocalDate, BigDecimal>> series = new LinkedHashMap<>();
        var fields = prices.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            require(symbols.contains(field.getKey()), "价格代码不在持仓中: " + field.getKey());
            TreeMap<LocalDate, BigDecimal> observations = new TreeMap<>();
            for (var point : array(prices, field.getKey(), 1000, true)) {
                LocalDate day = date(point, "date");
                require(!day.isAfter(asOf), "prices.date 不能晚于 asOf");
                BigDecimal close = number(point, "close");
                require(close.signum() > 0, "close 必须大于 0");
                require(observations.putIfAbsent(day, close) == null, "同一证券不能包含重复价格日期");
            }
            series.put(field.getKey(), observations);
        }
        var tickers = new ArrayList<>(series.keySet());
        for (int i = 0; i < tickers.size(); i++) for (int j = i + 1; j < tickers.size(); j++) {
            String a = tickers.get(i), b = tickers.get(j);
            var common = new ArrayList<>(series.get(a).keySet());
            common.retainAll(series.get(b).keySet());
            ObjectNode row = output.addObject().put("symbolA", a).put("symbolB", b);
            row.put("returnCount", Math.max(0, common.size() - 1));
            row.put("basis", "先取共同收盘日期，再计算相同区间的本币价格收益率；不包含分红或汇率调整");
            if (common.size() < 4) {
                row.putNull("correlation").put("reason", "至少需要 4 个共同收盘日期（3 个区间收益率）"); continue;
            }
            row.put("startDate", common.get(0).toString()).put("endDate", common.get(common.size() - 1).toString());
            double[] x = new double[common.size() - 1], y = new double[x.length];
            for (int k = 1; k < common.size(); k++) {
                x[k - 1] = series.get(a).get(common.get(k)).divide(series.get(a).get(common.get(k - 1)), MC).doubleValue() - 1;
                y[k - 1] = series.get(b).get(common.get(k)).divide(series.get(b).get(common.get(k - 1)), MC).doubleValue() - 1;
            }
            double meanX = Arrays.stream(x).average().orElseThrow(), meanY = Arrays.stream(y).average().orElseThrow();
            double cov = 0, xx = 0, yy = 0;
            for (int k = 0; k < x.length; k++) {
                cov += (x[k] - meanX) * (y[k] - meanY);
                xx += Math.pow(x[k] - meanX, 2); yy += Math.pow(y[k] - meanY, 2);
            }
            if (xx == 0 || yy == 0) row.putNull("correlation").put("reason", "至少一条收益率序列无变化");
            else row.put("correlation", Math.max(-1, Math.min(1, cov / Math.sqrt(xx * yy))));
        }
    }

    public ObjectNode lookThroughEtfs(JsonNode input) {
        LocalDate asOf = date(object(input, "input"), "asOf");
        Map<String, Fund> funds = new LinkedHashMap<>();
        for (var fund : array(input, "funds", 50, false)) {
            String symbol = symbol(fund, "symbol"); LocalDate holdingsDate = date(fund, "asOf");
            require(!holdingsDate.isAfter(asOf), "funds.asOf 不能晚于组合 asOf");
            Map<String, BigDecimal> holdings = new LinkedHashMap<>(); BigDecimal coverage = BigDecimal.ZERO;
            for (var item : array(fund, "holdings", 2000, false)) {
                String constituent = symbol(item, "symbol"); BigDecimal weight = number(item, "weight");
                require(weight.signum() >= 0 && weight.compareTo(BigDecimal.ONE) <= 0, "成分 weight 必须在 0 到 1 之间");
                require(holdings.putIfAbsent(constituent, weight) == null, "ETF 成分代码重复: " + constituent);
                coverage = coverage.add(weight);
            }
            require(coverage.compareTo(BigDecimal.ONE) <= 0, "ETF 已披露权重之和不能超过 1: " + symbol);
            require(funds.putIfAbsent(symbol, new Fund(holdingsDate, holdings, coverage)) == null, "funds 代码重复: " + symbol);
        }
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>(); Set<String> keys = new HashSet<>();
        Set<String> heldEtfs = new LinkedHashSet<>();
        for (var position : array(input, "positions", 200, true)) {
            String symbol = symbol(position, "symbol"), currency = currency(position);
            unique(keys, currency + ":" + symbol, "positions");
            require(number(position, "marketValue").signum() >= 0, "ETF 穿透输入 marketValue 必须非负");
            require(position.path("isEtf").isBoolean(), "isEtf 必须为布尔值");
            if (position.path("isEtf").booleanValue()) heldEtfs.add(symbol);
            groups.computeIfAbsent(currency, k -> new ArrayList<>()).add(position);
        }
        ObjectNode result = base(asOf);
        result.put("scope", "单层穿透；成分中的其他 ETF 保留其代码；不同币种不合计；未披露部分保持未知");
        result.put("formula", "exposure=directValue+sum(ETFValue*disclosedWeight); overlap=sum(min(weightA,weightB))");
        ArrayNode output = result.putArray("currencyGroups"); boolean partial = false;
        for (var entry : groups.entrySet()) {
            BigDecimal total = BigDecimal.ZERO, uncovered = BigDecimal.ZERO;
            Map<String, BigDecimal> exposure = new TreeMap<>();
            var group = output.addObject().put("currency", entry.getKey()); var coverageRows = group.putArray("fundCoverage");
            for (var position : entry.getValue()) {
                String symbol = symbol(position, "symbol"); BigDecimal value = number(position, "marketValue"); total = total.add(value);
                if (!position.path("isEtf").asBoolean()) { exposure.merge(symbol, value, BigDecimal::add); continue; }
                Fund fund = funds.get(symbol); var row = coverageRows.addObject().put("symbol", symbol);
                if (fund == null) {
                    row.putNull("asOf").putNull("coverage").put("uncoveredValue", value).put("reason", "未提供成分表");
                    uncovered = uncovered.add(value); partial = true; continue;
                }
                row.put("asOf", fund.asOf().toString()).put("coverage", fund.coverage());
                BigDecimal missing = value.multiply(BigDecimal.ONE.subtract(fund.coverage()));
                row.put("uncoveredValue", missing); uncovered = uncovered.add(missing);
                if (fund.coverage().compareTo(BigDecimal.ONE) < 0) partial = true;
                for (var holding : fund.holdings().entrySet()) exposure.merge(holding.getKey(), value.multiply(holding.getValue()), BigDecimal::add);
            }
            group.put("totalValue", total).put("knownExposureValue", total.subtract(uncovered)).put("uncoveredValue", uncovered);
            ratio(group, "coverage", total.subtract(uncovered), total);
            var rows = group.putArray("exposures");
            for (var item : exposure.entrySet()) {
                var row = rows.addObject().put("symbol", item.getKey()).put("marketValue", item.getValue());
                ratio(row, "portfolioWeight", item.getValue(), total);
            }
        }
        var overlap = result.putArray("fundOverlap"); var names = new ArrayList<>(heldEtfs);
        for (int i = 0; i < names.size(); i++) for (int j = i + 1; j < names.size(); j++) {
            Fund a = funds.get(names.get(i)), b = funds.get(names.get(j));
            var row = overlap.addObject().put("symbolA", names.get(i)).put("symbolB", names.get(j));
            if (a == null || b == null) { row.putNull("disclosedOverlap").put("complete", false).put("reason", "缺少成分表"); continue; }
            BigDecimal shared = BigDecimal.ZERO;
            for (var item : a.holdings().entrySet()) if (b.holdings().containsKey(item.getKey())) shared = shared.add(item.getValue().min(b.holdings().get(item.getKey())));
            boolean complete = a.coverage().compareTo(BigDecimal.ONE) == 0 && b.coverage().compareTo(BigDecimal.ONE) == 0;
            row.put("asOfA", a.asOf().toString()).put("asOfB", b.asOf().toString()).put("disclosedOverlap", shared).put("complete", complete);
            row.put("basis", complete ? "两个给定日期成分表的重叠权重" : "已披露成分的重叠权重下界，未知部分未计入");
        }
        return result.put("status", partial ? "PARTIAL" : "SUCCESS");
    }

    public ObjectNode screenStocks(JsonNode input) {
        LocalDate asOf = date(object(input, "input"), "asOf");
        Set<String> universe = new LinkedHashSet<>();
        for (var value : array(input, "universe", 1000, true)) {
            require(value.isTextual() && !value.asText().isBlank(), "universe 必须为非空代码字符串数组");
            String symbol = normalizedSymbol(value.asText()); unique(universe, symbol, "universe");
        }
        Map<String, JsonNode> data = new HashMap<>();
        for (var row : array(input, "rows", 1000, false)) {
            String symbol = symbol(row, "symbol"); require(universe.contains(symbol), "rows 代码不在 universe 中: " + symbol);
            require(data.putIfAbsent(symbol, row) == null, "rows 代码重复: " + symbol); date(row, "asOf");
            var metrics = object(row.get("metrics"), "rows[].metrics"); var fields = metrics.fields();
            while (fields.hasNext()) { var field = fields.next(); if (!field.getValue().isNull()) number(metrics, field.getKey()); }
        }
        var rules = array(input, "rules", 20, true);
        for (var rule : rules) {
            text(rule, "metric"); String op = text(rule, "operator");
            require(Set.of("GT", "GTE", "LT", "LTE", "EQ", "BETWEEN").contains(op), "operator 必须为 GT/GTE/LT/LTE/EQ/BETWEEN");
            BigDecimal value = number(rule, "value");
            if (op.equals("BETWEEN")) require(number(rule, "maxValue").compareTo(value) >= 0, "BETWEEN 的 maxValue 不能小于 value");
        }
        ObjectNode result = base(asOf); result.put("scope", "仅筛选给定 universe；规则为 AND；缺失或未来日期数据为 UNKNOWN");
        var output = result.putArray("results"); int unknownCount = 0;
        for (String symbol : universe) {
            JsonNode row = data.get(symbol); var item = output.addObject().put("symbol", symbol); var checks = item.putArray("rules");
            boolean unknown = false, failed = false;
            for (var rule : rules) {
                String metric = text(rule, "metric"), op = text(rule, "operator"); var check = checks.addObject();
                check.set("rule", rule.deepCopy());
                JsonNode actual = row == null ? null : row.path("metrics").get(metric);
                String reason = row == null ? "未提供证券指标" : date(row, "asOf").isAfter(asOf) ? "指标日期晚于筛选日期" : actual == null || actual.isNull() ? "缺少指标" : null;
                if (row != null) check.put("dataAsOf", row.path("asOf").asText());
                if (reason != null) { check.put("status", "UNKNOWN").put("reason", reason).putNull("actual"); unknown = true; continue; }
                BigDecimal n = actual.decimalValue(), target = number(rule, "value"); int comparison = n.compareTo(target);
                boolean pass = switch (op) {
                    case "GT" -> comparison > 0; case "GTE" -> comparison >= 0; case "LT" -> comparison < 0;
                    case "LTE" -> comparison <= 0; case "EQ" -> comparison == 0;
                    case "BETWEEN" -> comparison >= 0 && n.compareTo(number(rule, "maxValue")) <= 0;
                    default -> throw new IllegalStateException(op);
                };
                check.put("actual", n).put("status", pass ? "PASS" : "FAIL"); if (!pass) failed = true;
            }
            item.put("status", failed ? "FAIL" : unknown ? "UNKNOWN" : "PASS"); if (unknown) unknownCount++;
        }
        return result.put("status", unknownCount == 0 ? "SUCCESS" : "PARTIAL").put("universeSize", universe.size()).put("rowsWithUnknown", unknownCount);
    }

    private ObjectNode base(LocalDate asOf) { return mapper.createObjectNode().put("error", false).put("source", "USER_PROVIDED").put("asOf", asOf.toString()); }
    private JsonNode object(JsonNode node, String name) { require(node != null && node.isObject(), name + " 必须为 JSON 对象"); return node; }
    private ArrayNode array(JsonNode parent, String key, int max, boolean required) {
        JsonNode node = parent.get(key);
        if (node == null && !required) return mapper.createArrayNode();
        require(node != null && node.isArray() && (!required || !node.isEmpty()) && node.size() <= max, key + " 必须为" + (required ? "非空" : "") + "数组，最多 " + max + " 项");
        return (ArrayNode) node;
    }
    private String text(JsonNode node, String key) { JsonNode value = object(node, key + " 所在记录").get(key); require(value != null && value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 120, key + " 必须为非空文本（最多 120 字符）"); return value.asText().trim(); }
    private String normalizedSymbol(String value) { String symbol = value.trim().toUpperCase(Locale.ROOT); require(symbol.length() <= 40 && symbol.matches("[A-Z0-9.^:_/-]+"), "证券代码格式无效: " + symbol); return symbol; }
    private String symbol(JsonNode node, String key) { return normalizedSymbol(text(node, key)); }
    private String currency(JsonNode node) { String currency = text(node, "currency").toUpperCase(Locale.ROOT); require(currency.matches("[A-Z]{3}"), "currency 必须为三位币种代码"); return currency; }
    private LocalDate date(JsonNode node, String key) { try { return LocalDate.parse(text(node, key)); } catch (java.time.DateTimeException ex) { throw new IllegalArgumentException(key + " 必须为 YYYY-MM-DD 日期"); } }
    private BigDecimal number(JsonNode node, String key) {
        JsonNode value = object(node, key + " 所在记录").get(key);
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()), key + " 必须为有限 JSON 数字");
        BigDecimal number = value.decimalValue(); require(number.abs().compareTo(new BigDecimal("1E30")) <= 0 && number.scale() <= 30, key + " 数值或精度超出范围"); return number;
    }
    private void ratio(ObjectNode node, String key, BigDecimal numerator, BigDecimal denominator) { if (denominator.signum() == 0) node.putNull(key); else node.put(key, numerator.divide(denominator, MC)); }
    private void unique(Set<String> values, String value, String field) { require(values.add(value), field + " 包含重复项: " + value); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message + "，请检查输入表。"); }
    private record Position(String symbol, String currency, BigDecimal value, String kind) { }
    private record Fund(LocalDate asOf, Map<String, BigDecimal> holdings, BigDecimal coverage) { }
}
