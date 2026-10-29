package com.stocksage.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.client.DataServiceClient;
import com.stocksage.client.SecFinancialsResponse;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.ibkr.IbkrHistoricalResponse;
import com.stocksage.ibkr.IbkrReadOnlyService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 只读报价与有来源的输入计算。公式、时间口径与不能计算的原因一起返回。 */
@Component
@RequiredArgsConstructor
public class MarketResearchTools {
    private final MarketTools marketTools;
    private final DataServiceClient dataServiceClient;
    private final IbkrReadOnlyService ibkrReadOnlyService;
    private final ObjectMapper objectMapper;
    private static final ZoneId US_MARKET_TIME = ZoneId.of("America/New_York");

    @Tool(description = "证券报价与申报股数快照：IBKR last/bid/ask 保留更新时间和行情权限标记；美股附 SEC 最近申报期末普通股及期间加权股数。股数并非今日总股本，乘积仅为跨时点估算，不能当成准确当前市值")
    public String getSecuritySnapshot(
            @ToolParam(description = "已确认的股票代码，如 AAPL 或 0700.HK") String code,
            @ToolParam(description = "SEC 股数期间 annual 或 quarterly，默认 quarterly；港股无 SEC 股数") String period) {
        try {
            String scope = period == null || period.isBlank() ? "quarterly" : period;
            require(Set.of("annual", "quarterly").contains(scope), "period must be annual or quarterly");
            ObjectNode result = result("securitySnapshot");
            JsonNode quote = read(ibkrReadOnlyService.getMarketSnapshot(code));
            result.set("quote", quote);
            result.putNull("currentMarketCapitalization");
            result.put("marketCapitalizationReason", "Reported period-end shares and weighted shares do not establish today's shares outstanding.");
            if (quote.path("error").asBoolean() || !"SUCCESS".equals(quote.path("status").asText())) {
                result.put("status", quote.path("error").asBoolean() ? "ERROR" : "EMPTY")
                        .put("error", quote.path("error").asBoolean())
                        .put("message", quote.path("message").asText("Quote unavailable; check Gateway and retry."));
                return result.toString();
            }
            if (!"US".equals(quote.path("market").asText())) {
                result.put("sharesStatus", "UNSUPPORTED").put("sharesReason", "This tool's reported-share source is SEC US filings.");
                return result.toString();
            }
            SecFinancialsResponse financials = dataServiceClient.getEdgarXbrl(quote.path("symbol").asText(), scope, 1, null, true);
            result.put("sharesStatus", financials.status().name());
            result.put("sharesFetchedAt", financials.fetchedAt());
            if (financials.status() != SecFinancialsResponse.Status.SUCCESS) {
                result.put("status", "PARTIAL").put("sharesReason", financials.message());
                return result.toString();
            }
            addShares(result, financials, "CommonSharesOutstanding", "reportedPeriodEndShares", quote);
            addShares(result, financials, "DilutedShares", "periodWeightedDilutedShares", quote);
            if (!result.hasNonNull("reportedPeriodEndShares")) {
                result.put("status", "PARTIAL").put("sharesReason", "The requested SEC period has no ordinary shares-outstanding fact; weighted shares cannot replace it.");
            }
            return result.toString();
        } catch (Exception error) { return failure("securitySnapshot", error); }
    }

    private void addShares(ObjectNode result, SecFinancialsResponse financials, String metricName, String outputName, JsonNode quote) {
        var metric = financials.metrics().get(metricName);
        if (metric == null || metric.data().isEmpty()) {
            result.putNull(outputName);
            return;
        }
        var fact = metric.data().stream().max(Comparator.comparing(SecFinancialsResponse.Fact::end)
                .thenComparing(SecFinancialsResponse.Fact::filed)).orElseThrow();
        ObjectNode shares = result.putObject(outputName);
        shares.set("fact", objectMapper.valueToTree(fact));
        shares.put("concept", metric.concept()).put("unit", metric.unit());
        shares.put("basis", metricName.equals("CommonSharesOutstanding") ? "REPORTED_PERIOD_END_NOT_CURRENT" : "PERIOD_WEIGHTED_AVERAGE_NOT_CURRENT");
        if (fact.value().signum() > 0 && quote.path("last").isNumber() && "shares".equals(metric.unit())) {
            shares.put("priceProductEstimate", fact.value().multiply(quote.get("last").decimalValue()));
            shares.put("currency", quote.path("currency").asText("UNKNOWN"));
            shares.put("formula", "IBKR last * reported share value; price and share dates differ, not current market capitalization");
            shares.put("shareClassAlignment", "Share class and any depositary-receipt ratio are not verified; this arithmetic is not a valuation input without that reconciliation.");
            shares.set("priceAsOf", quote.path("asOf"));
        }
    }

    @Tool(description = "用 IBKR 美股日线计算最大回撤、对数收益年化波动率、期间价格收益及可选基准相关性。基准只使用相同起止日收益；复权未知时明确不是含分红总收益")
    public String getMarketRisk(
            @ToolParam(description = "已确认的美股代码") String code,
            @ToolParam(description = "可选美股或 ETF 基准代码，如 SPY；无则传空") String benchmarkCode,
            @ToolParam(description = "IBKR 历史窗口，例如 1y，默认 1y") String period) {
        String window = period == null || period.isBlank() ? "1y" : period;
        try {
            String history = marketTools.getIbkrHistoricalBars(code, window, "1d");
            String benchmark = benchmarkCode == null || benchmarkCode.isBlank() ? null
                    : marketTools.getIbkrHistoricalBars(benchmarkCode, window, "1d");
            return analyzeMarketRisk(history, benchmark);
        } catch (Exception error) { return failure("marketRisk", error); }
    }

    @Tool(description = "事件窗口价格收益：带时区事件时间按纽约16:00常规收盘界限映射当天或下一根真实美股日线，列出起止价与基准同区间超额收益。半日市未接交易日历，不能自动识别提前收盘；结果不证明因果")
    public String getEventWindowReturns(
            @ToolParam(description = "已确认的美股代码") String code,
            @ToolParam(description = "可选基准代码，如 SPY") String benchmarkCode,
            @ToolParam(description = "事件时间 ISO-8601，必须带时区，如 2026-10-20T20:30:00Z") String eventAt,
            @ToolParam(description = "锚点前日线根数，1 至 60") int beforeBars,
            @ToolParam(description = "锚点后日线根数，0 至 60") int afterBars,
            @ToolParam(description = "覆盖事件前后的 IBKR 历史窗口，默认 1y") String period) {
        String window = period == null || period.isBlank() ? "1y" : period;
        try {
            String history = marketTools.getIbkrHistoricalBars(code, window, "1d");
            String benchmark = benchmarkCode == null || benchmarkCode.isBlank() ? null
                    : marketTools.getIbkrHistoricalBars(benchmarkCode, window, "1d");
            return analyzeEventWindow(history, benchmark, eventAt, beforeBars, afterBars);
        } catch (Exception error) { return failure("eventWindow", error); }
    }

    /** 同一实现同时接受已取得的版本化行情，便于复算，不向模型暴露重复的工具。 */
    public String analyzeMarketRisk(String historyJson, String benchmarkJson) {
        try {
            Series series = series(historyJson);
            ObjectNode result = result("marketRisk");
            metadata(result, series);
            if (series.prices().size() < 2) return empty(result, "At least two distinct daily closes are needed.");
            List<Map.Entry<LocalDate, Double>> prices = new ArrayList<>(series.prices().entrySet());
            double high = prices.get(0).getValue();
            double drawdown = 0;
            LocalDate highDate = prices.get(0).getKey(), peak = highDate, trough = highDate;
            List<Double> returns = new ArrayList<>();
            for (int i = 1; i < prices.size(); i++) {
                double price = prices.get(i).getValue();
                if (price > high) { high = price; highDate = prices.get(i).getKey(); }
                double decline = (price / high - 1) * 100;
                if (decline < drawdown) { drawdown = decline; peak = highDate; trough = prices.get(i).getKey(); }
                returns.add(Math.log(price / prices.get(i - 1).getValue()));
            }
            result.put("startDate", prices.get(0).getKey().toString()).put("endDate", prices.get(prices.size() - 1).getKey().toString());
            result.put("priceReturnPct", pct(prices.get(0).getValue(), prices.get(prices.size() - 1).getValue()));
            result.put("maximumDrawdownPct", drawdown).put("drawdownPeakDate", peak.toString()).put("drawdownTroughDate", trough.toString());
            result.put("returnObservationCount", returns.size()).put("annualizationPeriods", 252);
            if (returns.size() > 1) result.put("annualizedVolatilityPct", Math.sqrt(variance(returns)) * Math.sqrt(252) * 100);
            else result.putNull("annualizedVolatilityPct").put("volatilityReason", "At least two returns are needed for sample standard deviation.");
            result.put("drawdownFormula", "100 * min(close / running maximum close - 1)");
            result.put("volatilityFormula", "100 * sample_stddev(log(close_t / close_previous)) * sqrt(252)");
            result.put("samplingNote", "Each adjacent returned daily bar is one observation; missing trading sessions cannot be verified without an exchange calendar.");
            if (benchmarkJson != null && !benchmarkJson.isBlank()) {
                try { addCorrelation(result.putObject("benchmark"), series, series(benchmarkJson)); }
                catch (IllegalArgumentException error) { result.put("status", "PARTIAL").putObject("benchmark").put("status", "UNAVAILABLE").put("reason", error.getMessage()); }
            }
            return result.toString();
        } catch (Exception error) { return failure("marketRisk", error); }
    }

    public String analyzeEventWindow(String historyJson, String benchmarkJson, String eventAt, int beforeBars, int afterBars) {
        try {
            require(beforeBars >= 1 && beforeBars <= 60 && afterBars >= 0 && afterBars <= 60, "beforeBars must be 1..60 and afterBars 0..60");
            Instant eventTime = Instant.parse(eventAt);
            LocalDate eventDate = eventTime.atZone(US_MARKET_TIME).toLocalDate();
            boolean afterRegularClose = !eventTime.atZone(US_MARKET_TIME).toLocalTime().isBefore(java.time.LocalTime.of(16, 0));
            LocalDate firstReactionDate = afterRegularClose ? eventDate.plusDays(1) : eventDate;
            Series series = series(historyJson);
            ObjectNode result = result("eventWindow");
            metadata(result, series);
            result.put("eventAt", eventTime.toString()).put("eventMarketDate", eventDate.toString())
                    .put("marketTimeZone", US_MARKET_TIME.getId()).put("beforeBars", beforeBars).put("afterBars", afterBars);
            result.put("anchorPolicy", "FIRST_AVAILABLE_MARKET_DATE_ON_OR_AFTER_REACTION_DATE")
                    .put("firstReactionDate", firstReactionDate.toString()).put("atOrAfterRegularClose", afterRegularClose)
                    .put("sessionAssumption", "16:00 America/New_York regular-session close; early closes are not identified without an exchange calendar.");
            result.put("interpretation", "Daily close-to-close price association, not causal attribution. Daily bars include the whole session; an intraday event is not isolated from earlier price moves.");
            List<LocalDate> dates = new ArrayList<>(series.prices().keySet());
            if (dates.isEmpty() || eventDate.isBefore(dates.get(0)) || eventDate.isAfter(dates.get(dates.size() - 1))) {
                return empty(result, "Event date lies outside the returned history; request a window covering the event.");
            }
            int anchor = 0;
            while (anchor < dates.size() && dates.get(anchor).isBefore(firstReactionDate)) anchor++;
            if (anchor - beforeBars < 0 || anchor + afterBars >= dates.size()) {
                return empty(result, "Insufficient daily bars before or after the event; request more history or wait for the window to finish.");
            }
            LocalDate start = dates.get(anchor - beforeBars), end = dates.get(anchor + afterBars);
            double startPrice = series.prices().get(start), endPrice = series.prices().get(end);
            double stockReturn = pct(startPrice, endPrice);
            result.put("anchorDate", dates.get(anchor).toString()).put("startDate", start.toString()).put("endDate", end.toString())
                    .put("startPrice", startPrice).put("endPrice", endPrice).put("priceReturnPct", stockReturn)
                    .put("formula", "100 * (end close / start close - 1)");
            if (benchmarkJson != null && !benchmarkJson.isBlank()) {
                ObjectNode out = result.putObject("benchmark");
                try {
                    Series benchmark = series(benchmarkJson);
                    metadata(out, benchmark);
                    require(knownSameCurrency(series, benchmark), "Benchmark and security need the same explicit currency for excess returns");
                    require(benchmark.prices().containsKey(start) && benchmark.prices().containsKey(end), "Benchmark lacks an exact start or end trading date; no interpolation is applied");
                    double benchmarkReturn = pct(benchmark.prices().get(start), benchmark.prices().get(end));
                    out.put("status", "SUCCESS").put("startDate", start.toString()).put("endDate", end.toString())
                            .put("startPrice", benchmark.prices().get(start)).put("endPrice", benchmark.prices().get(end))
                            .put("priceReturnPct", benchmarkReturn).put("excessPriceReturnPct", stockReturn - benchmarkReturn)
                            .put("excessFormula", "security price return percentage minus benchmark price return percentage (percentage points)");
                } catch (IllegalArgumentException error) {
                    result.put("status", "PARTIAL");
                    out.put("status", "UNAVAILABLE").put("reason", error.getMessage()).putNull("excessPriceReturnPct");
                }
            }
            return result.toString();
        } catch (Exception error) { return failure("eventWindow", error); }
    }

    private void addCorrelation(ObjectNode out, Series security, Series benchmark) {
        metadata(out, benchmark);
        Map<String, Double> left = intervalReturns(security.prices()), right = intervalReturns(benchmark.prices());
        List<Double> x = new ArrayList<>(), y = new ArrayList<>();
        List<String> intervals = new ArrayList<>();
        left.forEach((interval, value) -> {
            if (right.containsKey(interval)) { x.add(value); y.add(right.get(interval)); intervals.add(interval); }
        });
        out.put("matchedReturnCount", x.size()).put("correlationFormula", "Pearson correlation of log price returns with identical start and end market dates");
        if (!intervals.isEmpty()) out.put("firstMatchedInterval", intervals.get(0)).put("lastMatchedInterval", intervals.get(intervals.size() - 1));
        out.put("currencyTreatment", "Native-currency returns; no FX conversion. Correlation is not a common-currency portfolio return.");
        if (x.size() < 2 || variance(x) == 0 || variance(y) == 0) {
            out.putNull("correlation").put("reason", "At least two matched returns and nonzero variance in both series are required.");
            return;
        }
        double mx = mean(x), my = mean(y), numerator = 0, xx = 0, yy = 0;
        for (int i = 0; i < x.size(); i++) { double dx = x.get(i) - mx, dy = y.get(i) - my; numerator += dx * dy; xx += dx * dx; yy += dy * dy; }
        out.put("correlation", Math.max(-1, Math.min(1, numerator / Math.sqrt(xx * yy))));
    }

    private Map<String, Double> intervalReturns(TreeMap<LocalDate, Double> prices) {
        Map<String, Double> result = new LinkedHashMap<>();
        Map.Entry<LocalDate, Double> previous = null;
        for (var current : prices.entrySet()) {
            if (previous != null) result.put(previous.getKey() + "/" + current.getKey(), Math.log(current.getValue() / previous.getValue()));
            previous = current;
        }
        return result;
    }

    @Tool(description = "读取 SEC 最近 8-K 公告候选索引，供核对拆股、分红、发行或回购事项。此索引不是已确认公司行动；用 readDisclosure 打开 document_url，再据原文整理带公告时间/生效日/来源的数据")
    public String getCorporateActionDisclosures(
            @ToolParam(description = "已确认的美股 ticker") String code,
            @ToolParam(description = "最近 8-K 数量，1 至 20") int count) {
        try {
            require(count >= 1 && count <= 20, "count must be 1..20");
            JsonNode filings = read(dataServiceClient.getEdgarFilings(code, "8-K", count));
            ObjectNode result = result("corporateActionDisclosures");
            result.set("filings", filings);
            result.put("coverage", "Recent SEC 8-K index only; not a complete corporate-action calendar and no action is inferred from a filing title.");
            result.put("nextStep", "Read the official document_url with readDisclosure; provide confirmed fields and source URLs to analyzeCorporateActions.");
            if (filings.path("error").asBoolean()) result.put("status", "ERROR").put("error", true);
            else if (filings.path("filings").isEmpty()) result.put("status", "EMPTY");
            return result.toString();
        } catch (Exception error) { return failure("corporateActionDisclosures", error); }
    }

    @Tool(description = "整理有来源的公司行动输入，计算拆股股数倍数和现金股息，按公告时间排除 asOf 之后的信息。输入 JSON: symbol,asOf(带时区),actions[]；每项 type(SPLIT/CASH_DIVIDEND/SHARE_ISSUANCE/REPURCHASE),announcedAt,effectiveDate,sourceUrl；拆股另需newShares/oldShares，股息需amountPerShare/currency，发行回购需shares。来源由调用者提供，不自动证明事项已完成，不自动复权行情")
    public String analyzeCorporateActions(@ToolParam(description = "带来源与时间的公司行动 JSON") String actionsJson) {
        try {
            JsonNode input = read(actionsJson);
            String symbol = requiredText(input, "symbol");
            Instant asOf = Instant.parse(requiredText(input, "asOf"));
            require(input.path("actions").isArray() && input.get("actions").size() <= 200, "actions must be an array of at most 200 items");
            ObjectNode result = result("corporateActions");
            result.put("symbol", symbol).put("asOf", asOf.toString()).put("provenance", "USER_SUPPLIED")
                    .put("interpretation", "Source URLs are supplied by the caller, not independently verified. A reached effective date does not prove completion; no prices are adjusted.");
            ArrayNode actions = result.putArray("actions");
            int excluded = 0;
            for (JsonNode item : input.get("actions")) {
                String type = requiredText(item, "type");
                require(Set.of("SPLIT", "CASH_DIVIDEND", "SHARE_ISSUANCE", "REPURCHASE").contains(type), "Unsupported corporate action type: " + type);
                Instant announced = Instant.parse(requiredText(item, "announcedAt"));
                LocalDate effective = LocalDate.parse(requiredText(item, "effectiveDate"));
                String source = sourceUrl(item);
                if (announced.isAfter(asOf)) { excluded++; continue; }
                ObjectNode action = actions.addObject();
                action.put("type", type).put("announcedAt", announced.toString()).put("effectiveDate", effective.toString()).put("sourceUrl", source)
                        .put("effectiveByAsOf", !effective.isAfter(asOf.atZone(US_MARKET_TIME).toLocalDate()))
                        .put("effectiveByAsOfMeaning", "Effective calendar date has been reached in America/New_York; completion is not verified");
                if (type.equals("SPLIT")) {
                    double oldShares = positive(item, "oldShares"), newShares = positive(item, "newShares");
                    action.put("oldShares", oldShares).put("newShares", newShares).put("shareMultiplier", newShares / oldShares)
                            .put("formula", "newShares / oldShares; inverse is the theoretical price factor, not an observed return");
                } else if (type.equals("CASH_DIVIDEND")) {
                    action.put("amountPerShare", nonnegative(item, "amountPerShare")).put("currency", requiredText(item, "currency"));
                    action.put("dateMeaning", "effectiveDate is caller-provided; ex-date, record date and payment date must not be interchanged");
                } else {
                    action.put("shares", positive(item, "shares")).put("interpretation", "Reported action quantity; not assumed executed or subtracted from current shares outstanding");
                }
            }
            result.put("excludedAfterAsOf", excluded);
            if (actions.isEmpty()) result.put("status", "EMPTY");
            return result.toString();
        } catch (Exception error) { return failure("corporateActions", error); }
    }

    @Tool(description = "从用户提供的同一时点期权链选最接近标的价格的有效行权价，计算跨式中价成本、报价区间和到期盈亏平衡点，不调用交易账户。JSON需symbol,currency,asOf,underlyingPrice,underlyingAsOf,expiry(带时区到期时间),sourceUrl,quotes[]；每项right(CALL/PUT),strike,bid,ask,volume,openInterest。剔除无流动性/无效报价，输入不全不能确认真正ATM，不输出概率区间或隐含波动率")
    public String analyzeOptionImpliedMove(@ToolParam(description = "完整同一时点、单一到期日的期权链 JSON") String chainJson) {
        try {
            JsonNode input = read(chainJson);
            String symbol = requiredText(input, "symbol"), currency = requiredText(input, "currency");
            Instant asOf = Instant.parse(requiredText(input, "asOf")), expiry = Instant.parse(requiredText(input, "expiry"));
            require(Instant.parse(requiredText(input, "underlyingAsOf")).equals(asOf), "underlyingAsOf must equal chain asOf; mixed observation times cannot be priced together");
            require(expiry.isAfter(asOf), "expiry must be after chain asOf");
            double spot = positive(input, "underlyingPrice");
            String source = sourceUrl(input);
            require(input.path("quotes").isArray() && input.get("quotes").size() <= 5000, "quotes must be an array of at most 5000 rows");
            TreeMap<Double, Map<String, JsonNode>> byStrike = new TreeMap<>();
            Set<String> seen = new java.util.HashSet<>();
            ObjectNode result = result("optionImpliedMove");
            result.put("symbol", symbol).put("currency", currency).put("asOf", asOf.toString()).put("expiry", expiry.toString())
                    .put("sourceUrl", source).put("provenance", "USER_SUPPLIED").put("underlyingPrice", spot);
            ArrayNode rejected = result.putArray("rejectedQuotes");
            int index = 0;
            for (JsonNode quote : input.get("quotes")) {
                require(!quote.hasNonNull("asOf") || Instant.parse(requiredText(quote, "asOf")).equals(asOf), "All quote asOf values must equal chain asOf");
                require(!quote.hasNonNull("quoteAt") || Instant.parse(requiredText(quote, "quoteAt")).equals(asOf), "All quoteAt values must equal chain asOf");
                require(!quote.hasNonNull("expiry") || Instant.parse(requiredText(quote, "expiry")).equals(expiry), "All quotes must share chain expiry");
                require(!quote.hasNonNull("symbol") || symbol.equals(requiredText(quote, "symbol")), "All quotes must share chain symbol");
                require(!quote.hasNonNull("currency") || currency.equals(requiredText(quote, "currency")), "All quotes must share chain currency");
                String right = requiredText(quote, "right");
                require(Set.of("CALL", "PUT").contains(right), "right must be CALL or PUT");
                double strike = positive(quote, "strike");
                require(seen.add(right + ":" + strike), "Duplicate option right and strike: " + right + " " + strike);
                try {
                    double bid = positive(quote, "bid"), ask = positive(quote, "ask");
                    require(ask >= bid, "Crossed bid/ask");
                    double volume = nonnegative(quote, "volume"), oi = nonnegative(quote, "openInterest");
                    require(volume == Math.rint(volume) && oi == Math.rint(oi), "volume and openInterest must be whole counts");
                    require(volume > 0 || oi > 0, "Neither volume nor open interest is positive");
                    byStrike.computeIfAbsent(strike, ignored -> new LinkedHashMap<>()).put(right, quote);
                } catch (IllegalArgumentException error) { rejected.addObject().put("row", index).put("reason", error.getMessage()); }
                index++;
            }
            var selection = byStrike.entrySet().stream().filter(entry -> entry.getValue().size() == 2)
                    .min(Comparator.<Map.Entry<Double, Map<String, JsonNode>>>comparingDouble(entry -> Math.abs(entry.getKey() - spot))
                            .thenComparingDouble(Map.Entry::getKey));
            if (selection.isEmpty()) return empty(result, "No same-strike call and put pair survives liquidity and quote checks.");
            var pair = selection.get();
            JsonNode call = pair.getValue().get("CALL"), put = pair.getValue().get("PUT");
            double bid = call.get("bid").doubleValue() + put.get("bid").doubleValue();
            double ask = call.get("ask").doubleValue() + put.get("ask").doubleValue();
            double mid = (bid + ask) / 2, strike = pair.getKey();
            result.put("strike", strike).put("strikeDistancePct", 100 * Math.abs(strike / spot - 1))
                    .put("selectionRule", "Nearest provided underlying-price strike with a valid call and put; lower strike wins ties")
                    .put("chainCoverage", "Caller-supplied chain; completeness and actual exchange ATM strike are not verified. Inspect strikeDistancePct before interpreting the premium.")
                    .put("bidPremium", bid).put("askPremium", ask).put("midPremium", mid).put("midPremiumPct", 100 * mid / spot)
                    .put("askBreakEvenLow", strike - ask).put("askBreakEvenHigh", strike + ask)
                    .put("quoteSpreadPctOfMid", 100 * (ask - bid) / mid);
            result.set("call", call); result.set("put", put);
            result.put("formula", "midPremium=(call bid+call ask+put bid+put ask)/2; midPremiumPct=100*midPremium/spot; long-straddle expiry breakevens=strike ± (call ask+put ask)");
            result.put("interpretation", "Per-share premium-derived move proxy and expiry break-even arithmetic, not a probability interval, volatility estimate or guarantee of execution. Excludes fees and early exercise; sources and quotes are caller-supplied, valid only at asOf. A negative lower breakeven is unreachable for a nonnegative stock price.");
            return result.toString();
        } catch (Exception error) { return failure("optionImpliedMove", error); }
    }

    private record Series(IbkrHistoricalResponse source, TreeMap<LocalDate, Double> prices) { }
    private Series series(String json) {
        IbkrHistoricalResponse source = IbkrHistoricalResponse.fromJson(read(json));
        require(source.status() == IbkrHistoricalResponse.Status.SUCCESS, "History is " + source.status() + ": " + source.message());
        require("US".equals(source.market()) && "1d".equals(source.bar()), "This calculation requires US daily bars with explicit market dates");
        TreeMap<LocalDate, Double> prices = new TreeMap<>();
        for (var bar : source.data()) {
            require(bar.c() != null && Double.isFinite(bar.c()) && bar.c() > 0, "Daily close must be a positive finite price");
            LocalDate date = Instant.ofEpochMilli(bar.t()).atZone(US_MARKET_TIME).toLocalDate();
            require(prices.putIfAbsent(date, bar.c()) == null, "Duplicate market date in daily history: " + date);
        }
        return new Series(source, prices);
    }
    private void metadata(ObjectNode output, Series series) {
        var source = series.source();
        output.put("symbol", source.symbol()).put("provider", source.provider()).put("currency", source.currency())
                .put("fetchedAt", source.fetchedAt()).put("asOf", source.asOf()).put("barCount", source.count())
                .put("adjustment", source.adjustment()).put("returnBasis", "PROVIDER_CLOSE_PRICE_RETURN_NOT_VERIFIED_TOTAL_RETURN")
                .put("adjustmentNote", "Provider adjustment is preserved. No split/dividend corrections are invented; unadjusted actions may distort measured returns and drawdowns.");
    }
    private boolean knownSameCurrency(Series a, Series b) {
        String currency = a.source().currency();
        return currency != null && !currency.isBlank() && !currency.equalsIgnoreCase("UNKNOWN") && currency.equals(b.source().currency());
    }
    private double pct(double start, double end) { return 100 * (end / start - 1); }
    private double mean(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
    private double variance(List<Double> values) {
        if (values.size() < 2) return 0;
        double average = mean(values), sum = 0;
        for (double value : values) sum += (value - average) * (value - average);
        return sum / (values.size() - 1);
    }
    private ObjectNode result(String operation) { return objectMapper.createObjectNode().put("schemaVersion", 1).put("operation", operation).put("status", "SUCCESS").put("error", false); }
    private String empty(ObjectNode result, String message) { return result.put("status", "EMPTY").put("message", message).toString(); }
    private String failure(String operation, Exception error) {
        ResearchBudgetExceededException.rethrowIfPresent(error);
        ToolCallContext.checkRunDeadline();
        return result(operation).put("status", "ERROR").put("error", true).put("errorCode", "INVALID_OR_UNAVAILABLE_INPUT")
                .put("message", operation + ": " + (error instanceof IllegalArgumentException ? error.getMessage() : "Data could not be obtained or decoded; check the input and provider, then retry.")).toString();
    }
    private JsonNode read(String json) {
        try {
            require(json != null && !json.isBlank() && json.length() <= 2_000_000, "JSON input must be nonempty and at most 2 MB");
            JsonNode parsed = objectMapper.readTree(json);
            require(parsed.isObject(), "JSON input must be an object");
            return parsed;
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Input is not valid JSON", error); }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    private String requiredText(JsonNode value, String field) {
        require(value.path(field).isTextual() && !value.get(field).textValue().isBlank(), field + " must be nonempty text");
        return value.get(field).textValue();
    }
    private double nonnegative(JsonNode value, String field) {
        require(value.path(field).isNumber() && Double.isFinite(value.get(field).doubleValue()) && value.get(field).doubleValue() >= 0, field + " must be a finite nonnegative number");
        return value.get(field).doubleValue();
    }
    private double positive(JsonNode value, String field) { double number = nonnegative(value, field); require(number > 0, field + " must be positive"); return number; }
    private String sourceUrl(JsonNode value) {
        String raw = requiredText(value, "sourceUrl");
        URI uri = URI.create(raw);
        require(("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null, "sourceUrl must be an absolute HTTP(S) URL without credentials");
        return raw;
    }
}
