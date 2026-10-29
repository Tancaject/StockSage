package com.stocksage.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.tool.ToolCallContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.*;

/** FRED 官方单序列接口。历史时点只走 API，不用最新 CSV 伪装 ALFRED vintage。 */
@Component
public class MacroDataClient {
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final WebClient api;
    private final WebClient graph;
    private static final int MAX_POINTS = 5000;

    public MacroDataClient(ObjectMapper objectMapper,
                           @Value("${macro.fred.api-key:${FRED_API_KEY:}}") String apiKey) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.api = client("https://api.stlouisfed.org");
        this.graph = client("https://fred.stlouisfed.org");
    }

    private static WebClient client(String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
                .exchangeStrategies(ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024)).build())
                .build();
    }

    public String series(String seriesId, String startDate, String endDate, String asOf) {
        try {
            ToolCallContext.checkRunDeadline();
            if (seriesId == null || !seriesId.matches("[A-Za-z0-9_]{1,80}")) throw new IllegalArgumentException("seriesId 需要 FRED 序列代码，如 FEDFUNDS、CPIAUCSL、DGS10；不能传 URL");
            LocalDate start = date(startDate, LocalDate.now().minusYears(5));
            LocalDate end = date(endDate, LocalDate.now());
            LocalDate vintage = asOf == null || asOf.isBlank() ? null : LocalDate.parse(asOf);
            if (start.isAfter(end) || start.isBefore(LocalDate.of(1776, 7, 4)) || end.isAfter(LocalDate.now()))
                throw new IllegalArgumentException("观察期需满足 1776-07-04 <= startDate <= endDate <= 今天");
            if (vintage != null && (vintage.isAfter(LocalDate.now()) || vintage.isBefore(LocalDate.of(1776, 7, 4))))
                throw new IllegalArgumentException("asOf 必须是 1776-07-04 到今天之间的历史日期");
            if (vintage != null && apiKey.isBlank()) return failure("FRED_API_KEY_REQUIRED", "历史时点查询需要 FRED_API_KEY 环境变量或 macro.fred.api-key 本地配置；未发起当期 CSV 替代请求。");
            return apiKey.isBlank() ? latestCsv(seriesId, start, end) : apiSeries(seriesId, start, end, vintage);
        } catch (Exception error) {
            ResearchBudgetExceededException.rethrowIfPresent(error); ResearchCapacityExceededException.rethrowIfPresent(error); ToolCallContext.checkRunDeadline();
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("宏观序列请求已中断", error);
            if (error instanceof IllegalArgumentException) return failure("INVALID_INPUT", error.getMessage());
            if (error instanceof DateTimeException) return failure("INVALID_INPUT", "startDate、endDate、asOf 必须是有效的 YYYY-MM-DD 日期。");
            if (error instanceof InvalidProviderResponse) return failure("INVALID_FRED_RESPONSE", error.getMessage());
            if (error instanceof WebClientResponseException http)
                return failure("FRED_HTTP_" + http.getStatusCode().value(), "FRED 请求失败，HTTP " + http.getStatusCode().value() + "；检查序列代码、API key 权限、官方限流或稍后重试。");
            // 异常原文可能含带 key 的 URI，不写入工具结果。
            return failure("FRED_UNAVAILABLE", "无法读取 FRED 官方数据；检查网络和官方服务后重试。未切换数据口径。");
        }
    }

    private String latestCsv(String id, LocalDate start, LocalDate end) {
        String body = graph.get().uri(b -> b.path("/graph/fredgraph.csv").queryParam("id", id)
                        .queryParam("cosd", start).queryParam("coed", end).build())
                .retrieve().bodyToMono(String.class).block(timeout());
        List<Map<String, Object>> points = parseCsv(body, id, start, end);
        var out = base(id, start, end, null, "FRED_PUBLIC_CSV");
        out.put("status", points.isEmpty() ? "EMPTY" : "PARTIAL");
        out.put("vintageMode", "CURRENT_REVISED");
        out.put("observations", points.stream().limit(MAX_POINTS).toList());
        out.put("truncated", points.size() > MAX_POINTS);
        out.put("coverage", "LATEST_REVISED_VALUES_ONLY");
        out.put("sourceUrl", "https://fred.stlouisfed.org/graph/fredgraph.csv?id=" + id + "&cosd=" + start + "&coed=" + end);
        out.put("metadata", Map.of("units", "UNAVAILABLE", "frequency", "UNAVAILABLE", "seasonalAdjustment", "UNAVAILABLE"));
        out.put("gaps", List.of("无 key 官方 CSV 不带单位、频率、季调和历史 vintage 元数据；请查看 seriesUrl，或配置 FRED_API_KEY 获取元数据。", "历史观察日期对应今天可得的修订值，不能用于历史时点回测。"));
        return json(out);
    }

    private String apiSeries(String id, LocalDate start, LocalDate end, LocalDate vintage) throws Exception {
        LocalDate effectiveVintage = vintage == null ? LocalDate.now() : vintage;
        String observationsJson = api.get().uri(b -> b.path("/fred/series/observations")
                        .queryParam("series_id", id).queryParam("api_key", apiKey).queryParam("file_type", "json")
                        .queryParam("observation_start", start).queryParam("observation_end", end)
                        .queryParam("realtime_start", effectiveVintage).queryParam("realtime_end", effectiveVintage)
                        .queryParam("limit", MAX_POINTS).queryParam("sort_order", "asc").build())
                .retrieve().bodyToMono(String.class).block(timeout());
        JsonNode data = objectMapper.readTree(observationsJson);
        if (data == null || !data.path("observations").isArray() || !data.path("count").isIntegralNumber()
                || !effectiveVintage.toString().equals(data.path("realtime_start").asText())
                || !effectiveVintage.toString().equals(data.path("realtime_end").asText()))
            throw new InvalidProviderResponse("FRED 观察值或 vintage 字段不符合接口协议；未把响应当有效序列。");
        List<Map<String, Object>> points = new ArrayList<>();
        LocalDate previous = null;
        for (JsonNode observation : data.path("observations")) {
            String dateText = observation.path("date").asText();
            LocalDate observed = providerDate(dateText);
            if (!observation.path("value").isTextual() || observed.isBefore(start) || observed.isAfter(end)
                    || (previous != null && !observed.isAfter(previous))) throw new InvalidProviderResponse("FRED 返回重复、无序或请求范围外的观察值。");
            Map<String, Object> point = point(observed, observation.path("value").asText());
            point.put("realtimeStart", observation.path("realtime_start").asText());
            point.put("realtimeEnd", observation.path("realtime_end").asText());
            points.add(point); previous = observed;
        }
        String metadataJson = api.get().uri(b -> b.path("/fred/series").queryParam("series_id", id)
                        .queryParam("api_key", apiKey).queryParam("file_type", "json")
                        .queryParam("realtime_start", effectiveVintage).queryParam("realtime_end", effectiveVintage).build())
                .retrieve().bodyToMono(String.class).block(timeout());
        JsonNode metadata = objectMapper.readTree(metadataJson);
        if (metadata == null || !metadata.path("seriess").isArray() || metadata.path("seriess").size() != 1)
            throw new InvalidProviderResponse("FRED 序列元数据缺失；请检查序列代码和 vintage。");
        JsonNode series = metadata.path("seriess").get(0);
        if (!id.equalsIgnoreCase(series.path("id").asText()) || !series.path("units").isTextual() || !series.path("frequency").isTextual())
            throw new InvalidProviderResponse("FRED 元数据代码、单位或频率无效。");
        boolean truncated = data.path("count").asInt() > points.size();
        var out = base(id, start, end, effectiveVintage, "FRED_API");
        out.put("status", points.isEmpty() ? "EMPTY" : truncated ? "PARTIAL" : "SUCCESS");
        out.put("vintageMode", vintage == null ? "CURRENT_REVISED" : "AS_OF_DATE");
        out.put("coverage", "SINGLE_SERIES_OBSERVATIONS"); out.put("observations", points);
        out.put("truncated", truncated); out.put("availableObservationCount", data.path("count").asInt());
        out.put("metadata", Map.of("title", series.path("title").asText(), "units", series.path("units").asText(),
                "frequency", series.path("frequency").asText(), "seasonalAdjustment", series.path("seasonal_adjustment").asText(),
                "lastUpdated", series.path("last_updated").asText()));
        out.put("gaps", truncated ? List.of("响应最多 5000 个观察值；缩短日期范围获取剩余部分。") : List.of());
        return json(out);
    }

    private static List<Map<String, Object>> parseCsv(String body, String id, LocalDate start, LocalDate end) {
        if (body == null || body.isBlank()) throw new InvalidProviderResponse("FRED CSV 响应为空。");
        String[] lines = body.replace("\uFEFF", "").split("\\R");
        if (!("observation_date," + id).equalsIgnoreCase(lines[0]) && !("DATE," + id).equalsIgnoreCase(lines[0]))
            throw new InvalidProviderResponse("FRED CSV 列名变化或序列不匹配；未尝试猜测列。");
        var points = new ArrayList<Map<String, Object>>();
        LocalDate previous = null;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] row = lines[i].split(",", -1);
            if (row.length != 2) throw new InvalidProviderResponse("FRED CSV 需要两列。");
            LocalDate observed = providerDate(row[0]);
            if (previous != null && !observed.isAfter(previous)) throw new InvalidProviderResponse("FRED CSV 日期重复或未升序排列。");
            previous = observed;
            if (!observed.isBefore(start) && !observed.isAfter(end)) points.add(point(observed, row[1]));
        }
        return points;
    }

    private static Map<String, Object> point(LocalDate observed, String raw) {
        var point = new LinkedHashMap<String, Object>(); point.put("date", observed.toString());
        boolean missing = raw.isBlank() || raw.equals(".");
        try {
            BigDecimal value = missing ? null : new BigDecimal(raw);
            if (value != null && !Double.isFinite(value.doubleValue())) throw new NumberFormatException();
            point.put("value", value); point.put("status", missing ? "MISSING" : "AVAILABLE");
        } catch (NumberFormatException error) { throw new InvalidProviderResponse("FRED 观察值包含非有限数值。"); }
        return point;
    }
    private static LocalDate providerDate(String value) {
        try { return LocalDate.parse(value); } catch (Exception error) { throw new InvalidProviderResponse("FRED 观察日期格式无效。"); }
    }
    private static LocalDate date(String value, LocalDate fallback) { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
    private static Duration timeout() { return Duration.ofMillis(ToolCallContext.remainingMillis(15000)); }
    private static Map<String, Object> base(String id, LocalDate start, LocalDate end, LocalDate asOf, String provider) {
        var out = new LinkedHashMap<String, Object>(); out.put("schemaVersion", 1); out.put("provider", provider);
        out.put("seriesId", id); out.put("startDate", start.toString()); out.put("endDate", end.toString());
        out.put("asOf", asOf == null ? null : asOf.toString()); out.put("fetchedAt", Instant.now().toString());
        out.put("seriesUrl", "https://fred.stlouisfed.org/series/" + id); return out;
    }
    private String failure(String code, String message) {
        return json(Map.of("schemaVersion", 1, "status", "ERROR", "provider", "FRED", "error",
                Map.of("code", code, "message", message == null ? "宏观数据请求无效" : message)));
    }
    private String json(Object data) { return objectMapper.valueToTree(data).toString(); }
    private static final class InvalidProviderResponse extends IllegalStateException {
        private InvalidProviderResponse(String message) { super(message); }
    }
}
