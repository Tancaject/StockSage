package com.stocksage.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.stocksage.client.DataServiceClient;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.knowledge.DisclosureDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.*;

/** 官方公告事件与有来源的业绩预期比较；不推算尚未披露的日程。 */
@Component
@RequiredArgsConstructor
public class EventResearchTools {
    private final DataServiceClient dataServiceClient;
    private final DisclosureDocumentService disclosureDocumentService;
    private final ObjectMapper objectMapper;

    @Tool(description = "查询美股已披露 SEC 公告事件，含 8-K、6-K、10-Q、10-K、20-F。eventDate 是申报日期，不是推算的财报日；只覆盖各类型最近 10 份，不提供未确认的未来日历")
    public String getCompanyEvents(
            @ToolParam(description = "美股 ticker，如 AAPL") String ticker,
            @ToolParam(description = "起始日期 YYYY-MM-DD，空值为最近一年") String fromDate,
            @ToolParam(description = "结束日期 YYYY-MM-DD，空值为今天") String toDate,
            @ToolParam(description = "结果上限，1-50") int limit) {
        try {
            String symbol = ticker(ticker);
            LocalDate from = date(fromDate, LocalDate.now().minusYears(1));
            LocalDate to = date(toDate, LocalDate.now());
            if (from.isAfter(to) || limit < 1 || limit > 50) throw new IllegalArgumentException("起止日期倒置，或 limit 不在 1-50 内");
            List<Map<String, Object>> events = new ArrayList<>();
            List<String> gaps = new ArrayList<>(List.of("仅查询 SEC recent 索引中每种类型最近 10 份；不是完整历史或未来事件日历。", "没有接入未来财报/股东大会日程来源；未列出不代表没有事件。"));
            int failedSources = 0;
            if (!from.isAfter(LocalDate.now())) {
                for (String type : List.of("8-K", "6-K", "10-Q", "10-K", "20-F")) {
                    ToolCallContext.checkRunDeadline();
                    try {
                        JsonNode index = objectMapper.readTree(dataServiceClient.getEdgarFilings(symbol, type, 10));
                        events.addAll(filingEvents(index, type, from, to));
                    } catch (Exception error) {
                        ResearchBudgetExceededException.rethrowIfPresent(error);
                        ResearchCapacityExceededException.rethrowIfPresent(error);
                        ToolCallContext.checkRunDeadline();
                        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("事件检索已中断", error);
                        failedSources++;
                        gaps.add(type + " 公告索引不可用；检查数据服务与 SEC 访问状态。");
                    }
                }
            }
            events.sort(Comparator.comparing(e -> e.get("eventDate").toString(), Comparator.reverseOrder()));
            boolean truncated = events.size() > limit;
            var out = base(failedSources == 5 ? "ERROR" : events.isEmpty() ? "EMPTY" : "PARTIAL", "SEC_EDGAR");
            out.put("ticker", symbol);
            out.put("fromDate", from.toString()); out.put("toDate", to.toString());
            out.put("events", events.stream().limit(limit).toList());
            out.put("truncated", truncated); out.put("gaps", gaps);
            out.put("coverage", Map.of("historical", "RECENT_SEC_FILINGS_ONLY", "future", "NOT_COVERED", "dateBasis", "FILING_DATE"));
            if (failedSources == 5) out.put("error", Map.of("code", "SEC_FILINGS_UNAVAILABLE", "message", "各类型 SEC 索引均读取失败；检查数据服务与 SEC 访问状态后重试。"));
            return json(out);
        } catch (Exception error) { return failure(error, "COMPANY_EVENTS_FAILED"); }
    }

    private static List<Map<String, Object>> filingEvents(JsonNode index, String type, LocalDate from, LocalDate to) {
        if (index == null || index.path("error").asBoolean() || !index.path("filings").isArray())
            throw new IllegalArgumentException("SEC 公告索引缺少 filings 数组");
        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode filing : index.path("filings")) {
            LocalDate filed = LocalDate.parse(required(filing, "filing_date"));
            String url = required(filing, "document_url");
            if (!officialSec(url) || !type.equals(required(filing, "filing_type"))) throw new IllegalArgumentException("SEC 公告来源或类型不匹配");
            if (filed.isBefore(from) || filed.isAfter(to) || filed.isAfter(LocalDate.now())) continue;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "SEC_FILING"); event.put("filingType", type);
            event.put("eventDate", filed.toString()); event.put("datePrecision", "DATE");
            event.put("dateBasis", "FILING_DATE"); event.put("confirmed", true); event.put("estimated", false);
            event.put("sourceUrl", url); event.put("accessionNumber", required(filing, "accession_number"));
            if (!filing.path("report_date").isNull() && !filing.path("report_date").asText().isBlank())
                event.put("reportedPeriodEnd", LocalDate.parse(filing.path("report_date").asText()).toString());
            events.add(event);
        }
        return events;
    }

    @Tool(description = "发现 SEC 官方业绩、管理层指引或电话会材料链接。搜索摘要只作发现线索，必须 readEarningsMaterial 后才能引用正文；不提供商业一致预期或保证存在电话会全文")
    public String findEarningsMaterials(
            @ToolParam(description = "美股 ticker") String ticker,
            @ToolParam(description = "guidance、transcript 或 earnings_release") String materialType,
            @ToolParam(description = "1-20") int limit) {
        try {
            String symbol = ticker(ticker);
            String type = materialType == null ? "" : materialType.trim().toLowerCase(Locale.ROOT);
            String term = switch (type) {
                case "guidance" -> "earnings guidance outlook";
                case "transcript" -> "earnings conference call transcript";
                case "earnings_release" -> "earnings results exhibit 99.1";
                default -> throw new IllegalArgumentException("materialType 只支持 guidance、transcript、earnings_release");
            };
            if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit 必须在 1-20 内");
            JsonNode search = objectMapper.readTree(dataServiceClient.webSearch("site:sec.gov/Archives/edgar/data/ " + symbol + " " + term, limit));
            if (search == null || search.path("error").asBoolean() || !search.path("results").isArray()) throw new IllegalStateException("官方材料搜索不可用");
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode result : search.path("results")) {
                String link = result.path("link").asText();
                if (!officialSec(link)) continue;
                var item = new LinkedHashMap<String, Object>();
                item.put("sourceUrl", link); item.put("title", result.path("title").asText());
                String snippet = result.path("snippet").asText();
                item.put("discoverySnippet", snippet.substring(0, Math.min(600, snippet.length())));
                item.put("verification", "DISCOVERY_ONLY");
                item.put("publishedTimeKind", result.path("publishedTimeKind").asText("UNKNOWN"));
                item.put("publishedAt", result.path("publishedAt").isTextual() ? result.path("publishedAt").asText() : null);
                item.put("publishedDate", result.path("publishedDate").isTextual() ? result.path("publishedDate").asText() : null);
                items.add(item);
            }
            var out = base(items.isEmpty() ? "EMPTY" : "PARTIAL", "SEC_OFFICIAL_DISCOVERY");
            out.put("ticker", symbol); out.put("materialType", type); out.put("materials", items);
            out.put("coverage", "SEC_OFFICIAL_LINKS_ONLY");
            out.put("gaps", List.of("搜索命中未验证发行人身份、材料类型与全文；需要打开链接核对。", "未接入授权电话会库或市场一致预期；搜索摘要不能充当这两种数据。"));
            return json(out);
        } catch (Exception error) { return failure(error, "EARNINGS_MATERIAL_SEARCH_FAILED"); }
    }

    @Tool(description = "分页读取官方公告中的业绩、指引、电话会材料正文和表格。仅支持统一公告读取器允许的官方域；ERROR/EMPTY/AMBIGUOUS_SECTION 不代表读取成功，truncated 时继续 nextOffset")
    public String readEarningsMaterial(
            @ToolParam(description = "已发现的官方公告 HTTPS URL") String documentUrl,
            @ToolParam(description = "公告类型，如 8-K、6-K、10-Q、10-K") String filingType,
            @ToolParam(description = "分页起点，首次为 0") int offset,
            @ToolParam(description = "每页最多字符数，按公告读取器上限") int maxChars) {
        return disclosureDocumentService.read(documentUrl, filingType, "", false, offset, maxChars);
    }

    @Tool(description = "对有来源的 actual/guidance/consensus 做业绩比较。inputJson 各对象必须含 metric、period、basis、unit、currency、sourceUrl、publishedAt；actual 必须 value，基准用 value 或 low/high；可传 asOf。只计算用户给定资料，绝不把公司指引当市场预期")
    public String compareEarningsExpectations(@ToolParam(description = "JSON：actual、guidance 和/或 consensus；金额必须 JSON number，日期必须带时区 ISO 时间") String inputJson) {
        try {
            if (inputJson == null || inputJson.length() > 40000) throw new IllegalArgumentException("inputJson 不能为空且最多 40000 字符");
            JsonNode input = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(inputJson);
            if (input == null || !input.isObject()) throw new IllegalArgumentException("inputJson 必须是 JSON 对象");
            Instant asOf = input.hasNonNull("asOf") ? Instant.parse(required(input, "asOf")) : Instant.now();
            if (asOf.isAfter(Instant.now())) throw new IllegalArgumentException("asOf 不能晚于当前时间");
            JsonNode actual = input.path("actual");
            Instant actualAt = validatePoint(actual, "actual", asOf);
            BigDecimal value = number(actual, "value");
            if (actual.has("low") || actual.has("high")) throw new IllegalArgumentException("actual 只接受实际 value，不接受区间");
            List<Map<String, Object>> comparisons = new ArrayList<>();
            for (String kind : List.of("guidance", "consensus")) {
                if (!input.hasNonNull(kind)) continue;
                JsonNode estimate = input.path(kind);
                Instant estimateAt = validatePoint(estimate, kind, asOf);
                if (!estimateAt.isBefore(actualAt)) throw new IllegalArgumentException(kind + " 的发布时间必须早于 actual，避免使用事后预期");
                for (String key : List.of("metric", "period", "basis", "unit", "currency"))
                    if (!required(actual, key).equalsIgnoreCase(required(estimate, key))) throw new IllegalArgumentException(kind + " 的 " + key + " 与 actual 不一致；先统一口径再计算");
                boolean point = estimate.hasNonNull("value");
                if (point && (estimate.has("low") || estimate.has("high"))) throw new IllegalArgumentException(kind + " 不能同时传 value 和区间");
                BigDecimal low = number(estimate, point ? "value" : "low");
                BigDecimal high = point ? low : number(estimate, "high");
                if (low.compareTo(high) > 0) throw new IllegalArgumentException(kind + " 的 low 不能大于 high");
                BigDecimal midpoint = low.add(high).divide(BigDecimal.valueOf(2));
                BigDecimal difference = value.subtract(midpoint);
                var comparison = new LinkedHashMap<String, Object>();
                comparison.put("baseline", kind); comparison.put("low", low); comparison.put("high", high);
                comparison.put("midpoint", midpoint); comparison.put("actual", value);
                comparison.put("differenceFromMidpoint", difference);
                comparison.put("percentageDifference", midpoint.signum() > 0 ? difference.multiply(BigDecimal.valueOf(100)).divide(midpoint, 8, RoundingMode.HALF_UP) : null);
                comparison.put("position", value.compareTo(high) > 0 ? "ABOVE_RANGE" : value.compareTo(low) < 0 ? "BELOW_RANGE" : "WITHIN_RANGE");
                comparison.put("formula", "midpoint=(low+high)/2; difference=actual-midpoint; percentageDifference=(actual-midpoint)/midpoint*100，仅 midpoint>0");
                comparison.put("source", estimate);
                if (midpoint.signum() <= 0) comparison.put("gap", "基准为零或负数，只报告差值，不计算百分比");
                comparisons.add(comparison);
            }
            if (comparisons.isEmpty()) throw new IllegalArgumentException("至少提供 guidance 或 consensus 一个基准");
            var out = base("SUCCESS", "USER_SUPPLIED_SOURCES");
            out.put("asOf", asOf.toString()); out.put("actual", actual); out.put("comparisons", comparisons);
            out.put("coverage", "ARITHMETIC_ON_SUPPLIED_SOURCES");
            out.put("gaps", List.of("来源和一致预期代表性未经自动核验；工具只检查输入口径、时间顺序和计算。"));
            return json(out);
        } catch (Exception error) { return failure(error, "EARNINGS_COMPARISON_FAILED"); }
    }

    private static Instant validatePoint(JsonNode point, String name, Instant asOf) {
        if (!point.isObject()) throw new IllegalArgumentException(name + " 必须是对象");
        for (String key : List.of("metric", "period", "basis", "unit", "currency")) required(point, key);
        URI source = URI.create(required(point, "sourceUrl"));
        if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null || source.getUserInfo() != null) throw new IllegalArgumentException(name + " 需要有效的 HTTPS sourceUrl");
        Instant published = Instant.parse(required(point, "publishedAt"));
        if (published.isAfter(asOf)) throw new IllegalArgumentException(name + " 的发布时间晚于 asOf");
        return published;
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw new IllegalArgumentException(field + " 必须是有限 JSON 数字");
        return value.decimalValue();
    }
    private static String required(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank()) throw new IllegalArgumentException("缺少文本字段 " + key);
        return node.path(key).asText().trim();
    }
    private static boolean officialSec(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && "www.sec.gov".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getPath().startsWith("/Archives/edgar/data/");
        } catch (IllegalArgumentException error) { return false; }
    }
    private static String ticker(String value) {
        if (value == null || !value.trim().matches("[A-Za-z][A-Za-z0-9.-]{0,11}")) throw new IllegalArgumentException("ticker 必须是美股代码，如 AAPL");
        return value.trim().toUpperCase(Locale.ROOT);
    }
    private static LocalDate date(String value, LocalDate fallback) { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
    private static Map<String, Object> base(String status, String provider) {
        var out = new LinkedHashMap<String, Object>();
        out.put("schemaVersion", 1); out.put("status", status); out.put("provider", provider); out.put("fetchedAt", Instant.now().toString()); return out;
    }
    private String json(Object value) { return objectMapper.valueToTree(value).toString(); }
    private String failure(Exception error, String code) {
        ResearchBudgetExceededException.rethrowIfPresent(error); ResearchCapacityExceededException.rethrowIfPresent(error); ToolCallContext.checkRunDeadline();
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("事件研究已中断", error);
        boolean invalidInput = error instanceof IllegalArgumentException || error instanceof DateTimeException || error instanceof JsonProcessingException;
        String detail = error instanceof DateTimeException ? "日期格式无效；观察日期使用 YYYY-MM-DD，publishedAt/asOf 使用带时区 ISO 时间。"
                : error instanceof JsonProcessingException ? "JSON 格式无效；请检查输入对象、数字和引号。"
                : invalidInput ? error.getMessage() : "资料处理失败；检查数据服务或官方来源响应后重试。";
        var out = base("ERROR", "EVENT_RESEARCH");
        out.put("error", Map.of("code", invalidInput ? "INVALID_INPUT" : code, "message", detail == null ? "输入格式无效" : detail));
        return json(out);
    }
}
