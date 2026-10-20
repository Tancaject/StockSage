package com.stocksage.evidence.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.KLineResponse;
import com.stocksage.client.SecFinancialsResponse;
import com.stocksage.ibkr.IbkrHistoricalResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 把结构化工具结果改写成给模型读的紧凑正文。
 *
 * <p>只做确定性计算和排版：行情给出最新优先的摘要和最近 20 个收盘价，SEC 财务给出多年度表。
 * 证据账本的元数据、哈希和时效仍按原始结果计算；辩论引用的逐字摘录对应这里输出的正文。
 * 无法识别或解析失败时返回空，调用方保留原始结果。</p>
 */
@Component
public class EvidencePresenter {

    private static final int RECENT_CLOSES = 20;
    private static final int[][] RETURN_WINDOWS = {{21, 1}, {63, 3}, {126, 6}, {252, 12}};
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    private final ObjectMapper objectMapper;

    public EvidencePresenter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 新闻和网页搜索结果的入口过滤：去掉已在本轮出现过的链接，以及标题和摘要都没提到当前标的的结果。
     * 改写后的结果仍满足搜索契约（count 同步，删空时状态为 EMPTY）；解析失败或不是版本化搜索结果时原样返回。
     *
     * @param mentionForms 标的的代码和公司名写法；为空时不做标的过滤
     * @param seenLinks 本轮已收录的链接，会被更新
     */
    public String filterSearchResults(String payload, List<String> mentionForms, java.util.Set<String> seenLinks) {
        JsonNode root = read(payload);
        if (root == null || !root.isObject() || !root.path("results").isArray() || !root.has("searchType")) return payload;
        var kept = objectMapper.createArrayNode();
        for (JsonNode result : root.path("results")) {
            String link = result.path("link").asText("");
            String text = (result.path("title").asText("") + " " + result.path("snippet").asText(""));
            if (!link.isBlank() && seenLinks.contains(link)) continue;
            if (!mentionForms.isEmpty() && !mentions(text, mentionForms)) continue;
            if (!link.isBlank()) seenLinks.add(link);
            kept.add(result);
        }
        if (kept.size() == root.path("results").size()) return payload;
        var filtered = ((com.fasterxml.jackson.databind.node.ObjectNode) root.deepCopy());
        filtered.set("results", kept);
        filtered.put("count", kept.size());
        if (kept.isEmpty() && "SUCCESS".equals(root.path("status").asText())) filtered.put("status", "EMPTY");
        return filtered.toString();
    }

    private static boolean mentions(String text, List<String> forms) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (String form : forms) {
            String target = form.toUpperCase(Locale.ROOT);
            if (target.matches(".*[A-Z0-9].*")) {
                if (java.util.regex.Pattern.compile("(?<![A-Z0-9])" + java.util.regex.Pattern.quote(target) + "(?![A-Z0-9])")
                        .matcher(upper).find()) return true;
            } else if (upper.contains(target)) {
                return true;
            }
        }
        return false;
    }

    /** SEC 公司全称里可用于匹配新闻的主名称（如 "NVIDIA CORP" 取 NVIDIA）；取不到返回空。 */
    public Optional<String> secCompanyShortName(String secPayload) {
        JsonNode root = read(secPayload);
        String name = root == null ? "" : root.path("company_name").asText("");
        for (String token : name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9&\\- ]", " ").split("\\s+")) {
            if (token.length() >= 3 && !java.util.Set.of("THE", "INC", "CORP", "CO", "LTD", "PLC", "HOLDINGS",
                    "GROUP", "FIRST", "AMERICAN", "UNITED", "GENERAL", "INTERNATIONAL", "NATIONAL").contains(token)) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    /** 按能力名选择改写方式；名称允许带参数或"Evidence Loop/"前缀。 */
    public Optional<String> present(String capabilityId, String payload) {
        if (capabilityId == null || payload == null || payload.isBlank()) return Optional.empty();
        try {
            if (capabilityId.contains("getIbkrHistoricalBars") || capabilityId.contains("getStockKLine")) {
                return marketSummary(payload);
            }
            if (capabilityId.contains("getFinancialReports")) {
                return secFinancialTable(payload);
            }
            if (capabilityId.startsWith("getStockNews")) {
                return headlines(payload);
            }
            if (capabilityId.contains("local.filing.searchText")) {
                return filingPassages(payload);
            }
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** 行情摘要：最新收盘、各期涨跌、区间高低、波动率、均线，以及最近 20 个收盘价。 */
    public Optional<String> marketSummary(String payload) {
        JsonNode root = read(payload);
        if (root == null) return Optional.empty();
        List<Bar> bars = new ArrayList<>();
        String symbol;
        String currency;
        String adjustment;
        if (root.has("conid") || "IBKR_WEB_API".equals(root.path("provider").asText())) {
            IbkrHistoricalResponse response = IbkrHistoricalResponse.fromJson(root);
            // 摘要按交易日口径计算，小时线、周线保持原样。
            if (response.status() != IbkrHistoricalResponse.Status.SUCCESS || !"1d".equals(response.bar())) return Optional.empty();
            for (IbkrHistoricalResponse.Bar bar : response.data()) {
                if (bar.t() == null || bar.c() == null) continue;
                bars.add(new Bar(Instant.ofEpochMilli(bar.t()).atZone(ZoneOffset.UTC).toLocalDate(),
                        bar.h(), bar.l(), bar.c()));
            }
            symbol = response.symbol();
            currency = response.currency();
            adjustment = response.adjustment();
        } else {
            KLineResponse response = KLineResponse.fromJson(root);
            if (response.status() != KLineResponse.Status.SUCCESS || !"daily".equals(response.period())) return Optional.empty();
            for (KLineResponse.Bar bar : response.data()) {
                if (!bar.hasOhlc()) continue;
                bars.add(new Bar(LocalDate.parse(bar.date()), bar.high(), bar.low(), bar.close()));
            }
            symbol = response.resolvedCode();
            currency = response.currency();
            adjustment = response.adjustment();
        }
        bars.sort(Comparator.comparing(Bar::date));
        if (bars.size() < 2) return Optional.empty();

        Bar last = bars.get(bars.size() - 1);
        StringBuilder text = new StringBuilder();
        text.append("行情摘要（").append(symbol).append("，日线，").append(bars.get(0).date()).append("～")
                .append(last.date()).append("，共 ").append(bars.size()).append(" 个交易日，币种 ")
                .append(currency == null ? "unknown" : currency).append("，复权 ")
                .append(adjustment == null ? "unknown" : adjustment).append("）\n");
        text.append("最新收盘：").append(price(last.close())).append("（").append(last.date()).append("）\n");

        List<String> changes = new ArrayList<>();
        for (int[] window : RETURN_WINDOWS) {
            int days = window[0];
            if (bars.size() > days) {
                double base = bars.get(bars.size() - 1 - days).close();
                changes.add("近" + window[1] + "个月 " + percent(last.close() / base - 1));
            }
        }
        if (!changes.isEmpty()) text.append("涨跌：").append(String.join("，", changes)).append('\n');

        Bar high = bars.stream().filter(bar -> bar.high() != null).max(Comparator.comparing(Bar::high)).orElse(last);
        Bar low = bars.stream().filter(bar -> bar.low() != null).min(Comparator.comparing(Bar::low)).orElse(last);
        double highValue = high.high() == null ? high.close() : high.high();
        double lowValue = low.low() == null ? low.close() : low.low();
        text.append("区间最高 ").append(price(highValue)).append("（").append(high.date()).append("），最低 ")
                .append(price(lowValue)).append("（").append(low.date()).append("），最新收盘距最高 ")
                .append(percent(last.close() / highValue - 1)).append('\n');

        if (bars.size() > 20) {
            double sum = 0;
            double sumSquares = 0;
            for (int index = bars.size() - 20; index < bars.size(); index++) {
                double change = Math.log(bars.get(index).close() / bars.get(index - 1).close());
                sum += change;
                sumSquares += change * change;
            }
            double variance = Math.max(0, (sumSquares - sum * sum / 20) / 19);
            text.append("近20个交易日年化波动率：").append(String.format(Locale.ROOT, "%.1f%%", Math.sqrt(variance * 252) * 100)).append('\n');
        }

        List<String> averages = new ArrayList<>();
        for (int period : new int[]{20, 50, 200}) {
            if (bars.size() >= period) {
                double average = bars.subList(bars.size() - period, bars.size()).stream()
                        .mapToDouble(Bar::close).average().orElse(Double.NaN);
                averages.add("MA" + period + " " + price(average) + (last.close() >= average ? "（收盘在上方）" : "（收盘在下方）"));
            }
        }
        if (!averages.isEmpty()) text.append("均线：").append(String.join("，", averages)).append('\n');

        text.append("最近").append(Math.min(RECENT_CLOSES, bars.size())).append("个交易日收盘：");
        List<String> closes = new ArrayList<>();
        for (Bar bar : bars.subList(Math.max(0, bars.size() - RECENT_CLOSES), bars.size())) {
            closes.add(bar.date() + " " + price(bar.close()));
        }
        text.append(String.join("；", closes)).append('\n');
        return Optional.of(text.toString().strip());
    }

    /** 10-K/10-Q 检索结果：每段标出章节和申报日期，按段截短，使全部段落都能放进预算。 */
    public Optional<String> filingPassages(String payload) {
        JsonNode root = read(payload);
        if (root == null || !root.path("results").isArray() || root.path("results").isEmpty()) return Optional.empty();
        int count = root.path("results").size();
        int perPassage = Math.max(300, 3200 / count);
        StringBuilder text = new StringBuilder();
        text.append(root.path("ticker").asText("")).append(" ").append(root.path("form").asText(""))
                .append(" 原文检索（主题：").append(root.path("topic").asText("")).append("）\n");
        int index = 1;
        for (JsonNode result : root.path("results")) {
            String body = result.path("text").asText("").replaceAll("\\s+", " ").strip();
            text.append('[').append(index++).append("] ").append(result.path("section").asText("未知章节"))
                    .append("（").append(result.path("filingType").asText("")).append("，")
                    .append(result.path("filingDate").asText("")).append("）\n")
                    .append(body.length() > perPassage ? body.substring(0, perPassage) + "…" : body).append('\n');
        }
        return Optional.of(text.toString().strip());
    }

    /** 个股新闻流只列标题、日期和来源；正文由按问题检索的新闻提供。 */
    public Optional<String> headlines(String payload) {
        JsonNode root = read(payload);
        if (root == null || !root.path("results").isArray() || root.path("results").isEmpty()) return Optional.empty();
        StringBuilder text = new StringBuilder("近期个股新闻标题：\n");
        for (JsonNode result : root.path("results")) {
            String date = result.path("publishedAt").asText(result.path("publishedDate").asText(result.path("date").asText("")));
            text.append("- ").append(date.length() >= 10 ? date.substring(0, 10) : "日期未知").append(" ")
                    .append(result.path("title").asText("")).append("（").append(result.path("source").asText(""))
                    .append("）").append('\n');
        }
        return Optional.of(text.toString().strip());
    }

    /** SEC 年度财务：每项指标一行，按财年列出，派生利润率和自由现金流；来源只写一次。 */
    public Optional<String> secFinancialTable(String payload) {
        JsonNode root = read(payload);
        if (root == null || !"SEC_EDGAR".equals(root.path("provider").asText())) return Optional.empty();
        SecFinancialsResponse response = SecFinancialsResponse.fromJson(root);
        if (response.metrics() == null || response.metrics().isEmpty()) return Optional.empty();

        // 每项指标按期末日期去重，同一期末保留最晚申报的值。
        Map<String, Map<String, SecFinancialsResponse.Fact>> byMetric = new LinkedHashMap<>();
        TreeMap<String, Boolean> periodEnds = new TreeMap<>();
        response.metrics().forEach((name, metric) -> {
            Map<String, SecFinancialsResponse.Fact> facts = new TreeMap<>();
            for (SecFinancialsResponse.Fact fact : metric.data()) {
                SecFinancialsResponse.Fact existing = facts.get(fact.end());
                if (existing == null || fact.filed().compareTo(existing.filed()) > 0) facts.put(fact.end(), fact);
            }
            byMetric.put(name, facts);
            facts.keySet().forEach(end -> periodEnds.put(end, true));
        });
        List<String> ends = new ArrayList<>(periodEnds.descendingKeySet());
        if (ends.size() > 5) ends = ends.subList(0, 5);
        ends = new ArrayList<>(ends);
        java.util.Collections.reverse(ends);

        StringBuilder text = new StringBuilder();
        boolean quarterly = "quarterly".equals(response.period());
        text.append(response.companyName() == null ? response.ticker() : response.companyName())
                .append(quarterly
                        ? " 单季财务（SEC 10-Q XBRL，只含第一至第三财季，经营现金流等累计口径不在内；金额单位：百万美元，股数单位：百万股；列为季度期末）\n"
                        : " 年度财务（SEC 10-K XBRL，金额单位：百万美元，股数单位：百万股，EPS 单位：美元/股；列为财年期末）\n");
        text.append("指标 | ").append(String.join(" | ", ends)).append('\n');
        for (Map.Entry<String, Map<String, SecFinancialsResponse.Fact>> entry : byMetric.entrySet()) {
            String unit = response.metrics().get(entry.getKey()).unit();
            List<String> cells = new ArrayList<>();
            for (String end : ends) {
                SecFinancialsResponse.Fact fact = entry.getValue().get(end);
                cells.add(fact == null ? "—" : amount(fact.value(), unit));
            }
            text.append(label(entry.getKey())).append(" | ").append(String.join(" | ", cells)).append('\n');
        }
        appendRatio(text, "营业利润率", byMetric.get("OperatingIncome"), byMetric.get("Revenue"), ends);
        appendRatio(text, "净利率", byMetric.get("NetIncome"), byMetric.get("Revenue"), ends);
        appendFreeCashFlow(text, byMetric.get("OperatingCashFlow"), byMetric.get("CapitalExpenditure"), ends);

        appendSplitCaveat(text, byMetric.get("DilutedShares"), ends);
        List<String> accessions = byMetric.values().stream().flatMap(facts -> facts.values().stream())
                .map(SecFinancialsResponse.Fact::accn).distinct().sorted(Comparator.reverseOrder()).toList();
        text.append("来源：SEC companyfacts，CIK ").append(response.cik()).append("；共 ").append(accessions.size())
                .append(" 份申报，最近：").append(String.join(", ", accessions.subList(0, Math.min(3, accessions.size()))));
        return Optional.of(text.toString().strip());
    }

    private static void appendRatio(StringBuilder text, String name, Map<String, SecFinancialsResponse.Fact> numerator,
                                    Map<String, SecFinancialsResponse.Fact> denominator, List<String> ends) {
        if (numerator == null || denominator == null) return;
        List<String> cells = new ArrayList<>();
        boolean any = false;
        for (String end : ends) {
            SecFinancialsResponse.Fact top = numerator.get(end);
            SecFinancialsResponse.Fact bottom = denominator.get(end);
            if (top == null || bottom == null || bottom.value().signum() == 0) {
                cells.add("—");
            } else {
                cells.add(String.format(Locale.ROOT, "%.1f%%",
                        top.value().divide(bottom.value(), 6, RoundingMode.HALF_UP).doubleValue() * 100));
                any = true;
            }
        }
        if (any) text.append(name).append("（计算） | ").append(String.join(" | ", cells)).append('\n');
    }

    private static void appendFreeCashFlow(StringBuilder text, Map<String, SecFinancialsResponse.Fact> operating,
                                           Map<String, SecFinancialsResponse.Fact> capex, List<String> ends) {
        if (operating == null || capex == null) return;
        List<String> cells = new ArrayList<>();
        boolean any = false;
        for (String end : ends) {
            SecFinancialsResponse.Fact cash = operating.get(end);
            SecFinancialsResponse.Fact spend = capex.get(end);
            if (cash == null || spend == null) {
                cells.add("—");
            } else {
                // XBRL 资本开支是正数的支出金额。
                cells.add(amount(cash.value().subtract(spend.value().abs()), "USD"));
                any = true;
            }
        }
        if (any) text.append("自由现金流（经营现金流−资本开支，计算） | ").append(String.join(" | ", cells)).append('\n');
    }

    /** 相邻财年股数变化超过 3 倍时多半是拆股；XBRL 是申报原值，早期每股数据未必复权。 */
    private static void appendSplitCaveat(StringBuilder text, Map<String, SecFinancialsResponse.Fact> shares,
                                          List<String> ends) {
        if (shares == null) return;
        for (int index = 1; index < ends.size(); index++) {
            SecFinancialsResponse.Fact before = shares.get(ends.get(index - 1));
            SecFinancialsResponse.Fact after = shares.get(ends.get(index));
            if (before == null || after == null || before.value().signum() <= 0) continue;
            double ratio = after.value().doubleValue() / before.value().doubleValue();
            if (ratio > 3 || ratio < 1 / 3.0) {
                text.append("注意：股数在 ").append(ends.get(index - 1)).append(" 到 ").append(ends.get(index))
                        .append(" 之间变化 ").append(String.format(Locale.ROOT, "%.1f", ratio))
                        .append(" 倍，可能有拆股或合股；该期之前的 EPS 和股数为申报原值，与之后不可直接比较。\n");
            }
        }
    }

    /**
     * 估值计算说明：最新收盘配合最近财年数据。只是派生计算，不是独立来源，因此以不可引用说明的形式给模型，
     * 论证时应分别引用行情证据和财务证据。
     */
    public Optional<String> valuationNote(String secPayload, String marketPayload) {
        try {
            JsonNode secRoot = read(secPayload);
            JsonNode marketRoot = read(marketPayload);
            if (secRoot == null || marketRoot == null) return Optional.empty();
            SecFinancialsResponse sec = SecFinancialsResponse.fromJson(secRoot);
            IbkrHistoricalResponse market = IbkrHistoricalResponse.fromJson(marketRoot);
            if (sec.status() != SecFinancialsResponse.Status.SUCCESS
                    || market.status() != IbkrHistoricalResponse.Status.SUCCESS || market.data().isEmpty()) {
                return Optional.empty();
            }
            List<IbkrHistoricalResponse.Bar> bars = market.data().stream()
                    .filter(bar -> bar.t() != null && bar.c() != null)
                    .sorted(Comparator.comparing(IbkrHistoricalResponse.Bar::t)).toList();
            if (bars.isEmpty()) return Optional.empty();
            double close = bars.get(bars.size() - 1).c();
            LocalDate closeDate = Instant.ofEpochMilli(bars.get(bars.size() - 1).t()).atZone(ZoneOffset.UTC).toLocalDate();
            double highClose = bars.stream().mapToDouble(IbkrHistoricalResponse.Bar::c).max().orElse(close);
            double lowClose = bars.stream().mapToDouble(IbkrHistoricalResponse.Bar::c).min().orElse(close);

            String end = sec.asOf();
            Double eps = latest(sec, "EPS", end);
            Double shares = latest(sec, "DilutedShares", end);
            Double revenue = latest(sec, "Revenue", end);
            Double operatingCash = latest(sec, "OperatingCashFlow", end);
            Double capex = latest(sec, "CapitalExpenditure", end);

            StringBuilder text = new StringBuilder();
            text.append("估值计算（口径：").append(closeDate).append(" 收盘 ").append(price(close))
                    .append(" × 最近财年 ").append(end).append(" 的 SEC 数据；非实时 TTM，不是独立数据源）\n");
            if (eps != null && eps > 0) {
                text.append("市盈率（收盘/稀释 EPS ").append(price(eps)).append("）：").append(multiple(close / eps))
                        .append("；一年内按最低/最高收盘为 ").append(multiple(lowClose / eps)).append("～")
                        .append(multiple(highClose / eps)).append('\n');
            } else if (eps != null) {
                text.append("最近财年稀释 EPS 为 ").append(price(eps)).append("，市盈率不适用\n");
            }
            if (shares != null && shares > 0) {
                double cap = close * shares;
                text.append("市值（收盘 × 财年稀释加权股数，近似）：").append(String.format(Locale.ROOT, "%.1f", cap / 1e9))
                        .append(" 十亿美元");
                if (revenue != null && revenue > 0) text.append("；市销率 ").append(multiple(cap / revenue));
                if (operatingCash != null && capex != null) {
                    text.append("；自由现金流收益率 ").append(String.format(Locale.ROOT, "%.1f%%",
                            (operatingCash - Math.abs(capex)) / cap * 100));
                }
                text.append('\n');
            }
            String result = text.toString().strip();
            return result.contains("\n") ? Optional.of(result) : Optional.empty();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Double latest(SecFinancialsResponse sec, String metric, String end) {
        SecFinancialsResponse.Metric values = sec.metrics().get(metric);
        if (values == null) return null;
        return values.data().stream().filter(fact -> fact.end().equals(end))
                .max(Comparator.comparing(SecFinancialsResponse.Fact::filed))
                .map(fact -> fact.value().doubleValue()).orElse(null);
    }

    private static String multiple(double value) {
        return String.format(Locale.ROOT, "%.1f 倍", value);
    }

    private static String label(String metric) {
        return switch (metric) {
            case "Revenue" -> "营收";
            case "NetIncome" -> "净利润（归母）";
            case "ConsolidatedNetIncome" -> "合并净利润（含少数股东，现金流量表口径）";
            case "OperatingIncome" -> "营业利润";
            case "OperatingCashFlow" -> "经营现金流";
            case "CapitalExpenditure" -> "资本开支";
            case "LeasedEquipmentExpenditure" -> "出租设备支出";
            case "PretaxIncome" -> "税前利润";
            case "SalesRevenue" -> "销售收入（客户合同，不含其他收益）";
            case "ContinuingOperationsNetIncome" -> "持续经营净利润（含少数股东）";
            case "ContinuingOperationsCashFlow" -> "持续经营业务经营现金流";
            case "GainOnDispositions" -> "资产处置净收益";
            case "TotalAssets" -> "总资产";
            case "TotalLiabilities" -> "总负债";
            case "StockholdersEquity" -> "股东权益";
            case "TotalDebt" -> "长期债务";
            case "Cash" -> "现金及等价物";
            case "EPS" -> "稀释每股收益";
            case "DilutedShares" -> "稀释加权股数";
            case "NetInterestIncome" -> "净利息收入";
            case "CreditLossProvision" -> "信贷损失拨备";
            default -> metric;
        };
    }

    private static String amount(BigDecimal value, String unit) {
        if ("USD/shares".equals(unit)) return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        // 与财报原表一致用百万为单位、取整，模型复述的数字可以和原表逐位核对。
        return String.format(Locale.ROOT, "%,d", value.divide(MILLION, 0, RoundingMode.HALF_UP).longValueExact());
    }

    private static String price(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%+.1f%%", ratio * 100);
    }

    private JsonNode read(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private record Bar(LocalDate date, Double high, Double low, double close) {
    }
}
