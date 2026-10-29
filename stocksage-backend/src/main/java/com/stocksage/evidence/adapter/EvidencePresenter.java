package com.stocksage.evidence.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.HkFinancialsResponse;
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
 * <p>只做确定性计算和排版：行情给出最新优先的摘要和最近 20 个收盘价，SEC 和港股财务给出多期表。
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
            if (capabilityId.contains("getFinancialReports") || capabilityId.contains("getHistoricalFinancials")) {
                return secFinancialTable(payload).or(() -> hkFinancialTable(payload));
            }
            if (capabilityId.startsWith("getStockNews")) {
                return headlines(payload);
            }
            if (capabilityId.contains("local.filing.searchText")) {
                return filingPassages(payload);
            }
            if (capabilityId.contains("readDisclosure")) {
                return disclosureBlocks(payload);
            }
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public Optional<String> disclosureBlocks(String payload) {
        JsonNode root = read(payload);
        if (root == null || !"OFFICIAL_DISCLOSURE".equals(root.path("provider").asText())) return Optional.empty();
        String source = root.path("selectedSource").path("documentUrl").asText(root.path("documentUrl").asText(""));
        int other = root.path("otherSourceCount").asInt(-1);
        StringBuilder text = new StringBuilder("公告展开；otherSourceCount: ").append(other < 0 ? "未知" : other)
                .append("；selectedSource: ").append(source).append('\n')
                .append("状态：").append(root.path("status").asText()).append("；仅展开上述来源，其他来源未读取。\n");
        if (root.has("message")) text.append(root.path("message").asText()).append('\n');
        text.append("documentHash: ").append(root.path("documentHash").asText("未知"))
                .append("；offset: ").append(root.path("offset").asInt()).append("；nextOffset: ")
                .append(root.path("nextOffset").isNumber() ? root.path("nextOffset").asText() : "无")
                .append("；truncated: ").append(root.path("truncated").asBoolean()).append('\n');
        for (JsonNode block : root.path("blocks")) {
            text.append('[').append(block.path("id").asText()).append("] ").append(block.path("section").asText())
                    .append(" / ").append(block.path("kind").asText());
            if (block.hasNonNull("pageStart")) text.append(" / 页 ").append(block.path("pageStart").asInt());
            if (block.path("partial").asBoolean()) text.append("（本块仅部分原文）");
            text.append('\n').append(block.path("text").asText()).append("\n\n");
        }
        return Optional.of(text.toString().strip());
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

    /** 10-K/10-Q 检索结果：窗口已在检索适配器按命中位置截取，这里只补章节和申报日期。 */
    public Optional<String> filingPassages(String payload) {
        JsonNode root = read(payload);
        if (root == null || !root.path("results").isArray() || root.path("results").isEmpty()) return Optional.empty();
        StringBuilder text = new StringBuilder();
        text.append(root.path("ticker").asText("")).append(" ").append(root.path("form").asText(""))
                .append(" 原文检索（主题：").append(root.path("topic").asText("")).append("）\n");
        int index = 1;
        for (JsonNode result : root.path("results")) {
            String body = result.path("text").asText("").replaceAll("\\s+", " ").strip();
            text.append('[').append(index++).append("] ").append(result.path("section").asText("未知章节"))
                    .append("（").append(result.path("filingType").asText("")).append("，")
                    .append(result.path("filingDate").asText("")).append("）\n")
                    .append(body).append('\n');
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

    /** SEC 财务：每项指标一行，按实际期末列出；累计差额就近标明两份来源，供截断后的证据核对。 */
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
                        ? " 单季财务（SEC 10-Q XBRL，只含第一至第三财季；现金流优先用披露单季值，标注累计差额的数值由同财年累计数相减；金额单位：百万美元，股数单位：百万股；列为季度期末）\n"
                        : " 年度财务（SEC 10-K XBRL，金额单位：百万美元，股数单位：百万股，EPS 单位：美元/股；列为财年期末）\n");
        if (response.requestedAsOf() != null) {
            text.append("申报信息截止日：").append(response.requestedAsOf())
                    .append("；仅含当日及此前已申报且今天仍可取得的事实，非完整历史档案。\n");
        }
        text.append("指标 | ").append(String.join(" | ", ends)).append('\n');
        appendRatio(text, "营业利润率", byMetric.get("OperatingIncome"), byMetric.get("Revenue"), ends);
        appendRatio(text, "净利率", byMetric.get("NetIncome"), byMetric.get("Revenue"), ends);
        appendCashMetrics(text, response, ends);
        for (Map.Entry<String, Map<String, SecFinancialsResponse.Fact>> entry : byMetric.entrySet()) {
            String unit = response.metrics().get(entry.getKey()).unit();
            List<String> cells = new ArrayList<>();
            for (String end : ends) {
                SecFinancialsResponse.Fact fact = entry.getValue().get(end);
                if (fact == null) {
                    cells.add("—");
                } else if (fact.derivation() == null) {
                    cells.add(amount(fact.value(), unit));
                } else {
                    var current = fact.derivation().current();
                    var previous = fact.derivation().previous();
                    cells.add(amount(fact.value(), unit) + "（累计差额 "
                            + current.value().divide(MILLION).stripTrailingZeros().toPlainString()
                            + "−" + previous.value().divide(MILLION).stripTrailingZeros().toPlainString() + "；本期 " + current.accn()
                            + "；前期 " + previous.accn() + "）");
                }
            }
            text.append(label(entry.getKey())).append(" | ").append(String.join(" | ", cells)).append('\n');
        }

        appendSplitCaveat(text, byMetric.get("DilutedShares"), ends);
        List<String> accessions = byMetric.values().stream().flatMap(facts -> facts.values().stream())
                .map(SecFinancialsResponse.Fact::accn).distinct().sorted(Comparator.reverseOrder()).toList();
        text.append("来源：SEC companyfacts，CIK ").append(response.cik()).append("；共 ").append(accessions.size())
                .append(" 份申报，最近：").append(String.join(", ", accessions.subList(0, Math.min(3, accessions.size()))));
        if (response.requestedAsOf() == null) {
            appendRevenueBreakdown(text, response.revenueBreakdown());
            appendBalanceSheet(text, payload);
        }
        return Optional.of(text.toString().strip());
    }

    /** 港股紧凑表中资产负债表和现金流量表列出的报告期数；请求更多期时调用方须标为删减。 */
    public static final int HK_SNAPSHOT_STATEMENT_PERIODS = 2;

    /**
     * 港股三张表：每个科目一行，按报告期列出（利润表最近 5 期，资产负债表和现金流量表最近 2 期），金额换算为百万。
     * 原样逐行 JSON 太长，截断后会只剩几行资产负债表、利润表和现金流被清空。不合契约时返回空，由调用方退回原压缩。
     */
    public Optional<String> hkFinancialTable(String payload) {
        JsonNode root = read(payload);
        if (root == null || !"HK".equals(root.path("market").asText()) || !root.has("statements")) return Optional.empty();
        HkFinancialsResponse response;
        try {
            response = HkFinancialsResponse.fromJson(root);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        String name = response.statements().all().stream().flatMap(table -> table.data().stream())
                .map(HkFinancialsResponse.StatementRow::securityNameAbbr).filter(n -> n != null && !n.isBlank())
                .findFirst().orElse(response.resolvedCode());
        // 供应商的 CURRENCY 不可靠（报表币种为人民币的公司也标 HKD），不写币种。
        text.append(name).append(" 港股").append("annual".equals(response.period()) ? "年度" : "报告期")
                .append("财务（AKShare 东方财富口径；金额单位：百万，币种以公司报告为准；列为报告期末；资产负债表和现金流量表只列最近 ")
                .append(HK_SNAPSHOT_STATEMENT_PERIODS).append(" 期）");
        appendHkStatement(text, "利润表", response.statements().incomeStatement().data(), 5);
        appendHkStatement(text, "资产负债表", response.statements().balanceSheet().data(), HK_SNAPSHOT_STATEMENT_PERIODS);
        appendHkStatement(text, "现金流量表", response.statements().cashFlow().data(), HK_SNAPSHOT_STATEMENT_PERIODS);
        return Optional.of(text.toString().strip());
    }

    private static void appendHkStatement(StringBuilder text, String title, List<HkFinancialsResponse.StatementRow> rows,
                                          int periods) {
        List<String> dates = rows.stream().map(HkFinancialsResponse.StatementRow::reportDate).distinct()
                .sorted(Comparator.reverseOrder()).limit(periods).sorted().toList();
        if (dates.isEmpty()) {
            text.append("\n\n").append(title).append("：无数据");
            return;
        }
        Map<String, Map<String, BigDecimal>> items = new LinkedHashMap<>();
        for (HkFinancialsResponse.StatementRow row : rows) {
            if (row.amount() != null && dates.contains(row.reportDate())) {
                items.computeIfAbsent(row.itemName(), key -> new TreeMap<>()).put(row.reportDate(), row.amount());
            }
        }
        text.append("\n\n").append(title).append("\n科目 | ").append(String.join(" | ", dates)).append('\n');
        // 每股科目是原值，不按百万换算。
        items.forEach((item, values) -> text.append(item).append(" | ").append(String.join(" | ", dates.stream()
                .map(date -> !values.containsKey(date) ? "—"
                        : item.startsWith("每股") ? values.get(date).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        : amount(values.get(date), "")).toList())).append('\n'));
    }

    private static final int BREAKDOWN_MEMBERS = 8;
    private static final Map<String, String> BREAKDOWN_UNAVAILABLE = Map.of(
            "NO_XBRL_INSTANCE", "该申报没有 XBRL 实例文件",
            "FETCH_FAILED", "读取 SEC 申报文件失败",
            "NO_DIMENSIONAL_FACTS", "申报中没有按分部、地区或产品标注的营收事实",
            "NO_RECONCILED_BREAKDOWN", "有拆分事实，但各项加总与合并数对不上，未展示");

    /**
     * 最新一期申报的分部、地区、产品拆分：每项一行、按期末列出金额和占当期合计的比例。
     * 比例是确定性派生值，辩论引用规则不允许模型自己补算数字，所以直接写进证据。
     * 取不到时写明原因，避免模型把"未取得"当成"公司未披露"。
     */
    private static void appendRevenueBreakdown(StringBuilder text, SecFinancialsResponse.RevenueBreakdown breakdown) {
        if (breakdown == null) {
            text.append("\n\n营收拆分：未取得（读取失败）");
            return;
        }
        if (!"AVAILABLE".equals(breakdown.status())) {
            text.append("\n\n营收拆分：未取得（").append(BREAKDOWN_UNAVAILABLE.getOrDefault(breakdown.reason(), breakdown.reason()))
                    .append("；").append(breakdown.form()).append(" ").append(breakdown.accession()).append("）");
            return;
        }
        text.append("\n\n营收拆分（SEC ").append(breakdown.form()).append(" XBRL 实例，").append(breakdown.accession())
                .append("；金额单位：百万美元，括号内为占当期合计比例；地区口径以财报附注为准）");
        for (SecFinancialsResponse.BreakdownTable table : breakdown.tables()) {
            List<SecFinancialsResponse.BreakdownPeriod> periods = table.periods();
            SecFinancialsResponse.BreakdownPeriod latest = periods.get(periods.size() - 1);
            List<String> names = latest.members().stream().map(SecFinancialsResponse.BreakdownMember::name).toList();
            List<String> shown = names.subList(0, Math.min(BREAKDOWN_MEMBERS, names.size()));
            text.append("\n按").append(switch (table.axis()) {
                case "segment" -> "业务分部";
                case "geography" -> "地区";
                default -> "产品/服务";
            }).append("·").append("Revenue".equals(table.metric()) ? "营收" : "营业利润").append(" | ")
                    .append(String.join(" | ", periods.stream().map(SecFinancialsResponse.BreakdownPeriod::end).toList()));
            for (String name : shown) {
                text.append('\n').append(name).append(" | ").append(String.join(" | ", periods.stream()
                        .map(period -> period.members().stream().filter(member -> member.name().equals(name)).findFirst()
                                .map(member -> breakdownCell(member.value(), period.total())).orElse("—"))
                        .toList()));
            }
            if (names.size() > shown.size()) {
                text.append("\n其余 ").append(names.size() - shown.size()).append(" 项合计 | ").append(String.join(" | ",
                        periods.stream().map(period -> breakdownCell(period.members().stream()
                                .filter(member -> !shown.contains(member.name()))
                                .map(SecFinancialsResponse.BreakdownMember::value)
                                .reduce(BigDecimal.ZERO, BigDecimal::add), period.total())).toList()));
            }
            text.append("\n合计 | ").append(String.join(" | ", periods.stream()
                    .map(period -> amount(period.total(), "USD")).toList()));
        }
        text.append("\n来源：").append(breakdown.sourceUrl());
    }

    private static String breakdownCell(BigDecimal value, BigDecimal total) {
        return amount(value, "USD") + "（" + String.format(Locale.ROOT, "%.1f%%",
                value.doubleValue() / total.doubleValue() * 100) + "）";
    }

    /** 最新 10-K 资产负债表原表：保留 companyfacts 没有的公司自定义科目和分部列示，最多 80 行。 */
    private void appendBalanceSheet(StringBuilder text, String payload) {
        try {
            JsonNode sheet = objectMapper.readTree(payload).path("balanceSheet");
            JsonNode lines = sheet.path("lines");
            if (!lines.isArray() || lines.isEmpty()) return;
            text.append("\n\n资产负债表原表（10-K ").append(sheet.path("accession").asText())
                    .append("，申报日 ").append(sheet.path("filed").asText()).append("，单位见首行）：\n");
            for (int i = 0; i < Math.min(80, lines.size()); i++) text.append(lines.get(i).asText()).append('\n');
            text.append("来源：").append(sheet.path("source_url").asText());
        } catch (Exception malformed) {
            // 原表只是补充证据，解析失败时保留紧凑表。
        }
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

    private static void appendCashMetrics(StringBuilder text, SecFinancialsResponse response, List<String> ends) {
        appendCashConversion(text, response, "经营现金流/净利润", "OperatingCashFlow",
                "ConsolidatedNetIncome", "NetIncome", ends);
        if (response.metrics().containsKey("ConsolidatedNetIncome")) {
            appendCashConversion(text, response, "经营现金流/归母净利润", "OperatingCashFlow", "NetIncome", null, ends);
        }
        appendCashConversion(text, response, "持续经营现金流/持续经营净利润", "ContinuingOperationsCashFlow",
                "ContinuingOperationsNetIncome", null, ends);
        List<String> spending = new ArrayList<>(), ratios = new ArrayList<>(), freeCash = new ArrayList<>();
        boolean any = false;
        for (String end : ends) {
            var cash = moneyFact(response, "OperatingCashFlow", end);
            BigDecimal capex = capitalExpenditure(response, cash);
            if (capex == null) {
                spending.add("—"); ratios.add("—"); freeCash.add("—");
            } else {
                boolean split = splitCapitalExpenditure(response);
                String basis = split && !samePeriod(cash, moneyFact(response, "LeasedEquipmentExpenditure", end))
                        ? "（仅已披露项，可能低估）" : split ? "（含出租设备支出）" : "";
                spending.add(cashAmount(capex) + basis);
                ratios.add(cash.value().signum() <= 0 ? "不适用（经营现金流≤0）"
                        : capex.multiply(BigDecimal.valueOf(100)).divide(cash.value(), 2, RoundingMode.HALF_UP) + "%" + basis);
                freeCash.add(cashAmount(cash.value().subtract(capex)) + (basis.contains("低估") ? "（资本开支不完整）" : ""));
                any = true;
            }
        }
        if (any) {
            text.append("资本开支毛额（计算；支出取绝对值） | ").append(String.join(" | ", spending)).append('\n');
            text.append("资本开支/经营现金流（计算） | ").append(String.join(" | ", ratios)).append('\n');
            text.append("简化自由现金流（经营现金流−资本开支毛额，计算） | ").append(String.join(" | ", freeCash)).append('\n');
        }
    }

    private static void appendCashConversion(StringBuilder text, SecFinancialsResponse response, String name,
                                             String cashMetric, String incomeMetric, String fallbackMetric, List<String> ends) {
        List<String> cells = new ArrayList<>();
        boolean any = false;
        for (String end : ends) {
            var cash = moneyFact(response, cashMetric, end);
            var income = moneyFact(response, incomeMetric, end);
            String basis = label(incomeMetric);
            if (income == null && fallbackMetric != null) {
                income = moneyFact(response, fallbackMetric, end);
                basis = label(fallbackMetric) + "，缺合并口径";
            }
            if (!samePeriod(cash, income)) {
                cells.add("—");
                continue;
            }
            boolean unsuitable = income.value().signum() <= 0
                    || income.value().abs().multiply(BigDecimal.valueOf(20)).compareTo(cash.value().abs()) < 0;
            cells.add((unsuitable ? "不适用；差额 " + cashAmount(cash.value().subtract(income.value()))
                    : cash.value().divide(income.value(), 2, RoundingMode.HALF_UP) + " 倍")
                    + "（" + basis + "）");
            any = true;
        }
        if (any) text.append(name).append("（计算；净利润≤0或绝对值不足经营现金流绝对值5%时，改报现金流−净利润差额） | ")
                .append(String.join(" | ", cells)).append('\n');
    }

    private static boolean samePeriod(SecFinancialsResponse.Fact left, SecFinancialsResponse.Fact right) {
        return left != null && right != null && "duration".equals(left.periodType())
                && "duration".equals(right.periodType()) && left.start().equals(right.start()) && left.end().equals(right.end());
    }

    private static SecFinancialsResponse.Fact moneyFact(SecFinancialsResponse response, String metric, String end) {
        var values = response.metrics().get(metric);
        if (values == null || !"USD".equals(values.unit())) return null;
        return values.data().stream().filter(fact -> fact.end().equals(end))
                .max(Comparator.comparing(SecFinancialsResponse.Fact::filed)).orElse(null);
    }

    private static boolean splitCapitalExpenditure(SecFinancialsResponse response) {
        var metric = response.metrics().get("CapitalExpenditure");
        return metric != null && "us-gaap:PaymentsToAcquireOtherPropertyPlantAndEquipment".equals(metric.concept());
    }

    private static BigDecimal capitalExpenditure(SecFinancialsResponse response, SecFinancialsResponse.Fact cash) {
        if (cash == null) return null;
        var spend = moneyFact(response, "CapitalExpenditure", cash.end());
        if (!samePeriod(cash, spend)) return null;
        BigDecimal total = spend.value().abs();
        var leased = moneyFact(response, "LeasedEquipmentExpenditure", cash.end());
        // Other PP&E 不含单列的出租设备；总 PP&E/ProductiveAssets 不再重复加它。
        if (splitCapitalExpenditure(response) && samePeriod(cash, leased)) total = total.add(leased.value().abs());
        return total;
    }

    private static String cashAmount(BigDecimal value) {
        return value.divide(MILLION, 2, RoundingMode.HALF_UP).toPlainString();
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
            var operatingCash = moneyFact(sec, "OperatingCashFlow", end);
            BigDecimal capex = capitalExpenditure(sec, operatingCash);

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
                    text.append("；简化自由现金流收益率 ").append(String.format(Locale.ROOT, "%.1f%%",
                            operatingCash.value().subtract(capex).doubleValue() / cap * 100));
                    if (splitCapitalExpenditure(sec) && !samePeriod(operatingCash, moneyFact(sec, "LeasedEquipmentExpenditure", end))) {
                        text.append("（资本开支仅含已披露项，可能低估）");
                    }
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
            case "CurrentAssets" -> "流动资产";
            case "CurrentLiabilities" -> "流动负债";
            case "ShortTermBorrowings" -> "短期借款";
            case "CommercialPaper" -> "商业票据";
            case "DebtCurrent" -> "一年内到期债务（含短期借款）";
            case "LongTermDebtCurrent" -> "一年内到期长期债务";
            case "LongTermDebtNoncurrent" -> "长期债务（非流动部分）";
            case "LongTermDebtAndFinanceLeases" -> "长期债务及融资租赁负债";
            case "TotalAssets" -> "总资产";
            case "TotalLiabilities" -> "总负债";
            case "StockholdersEquity" -> "股东权益";
            case "TotalDebt" -> "长期债务（LongTermDebt，是否含一年内到期部分以公司列示为准）";
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
