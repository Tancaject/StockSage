package com.stocksage.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 搜索时间窗描述供应商请求；文章发布日期只来自每条结果，不能由抓取时间补造。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchResponse(
        int schemaVersion, Status status, String query, String provider, String searchType,
        String requestedTimelimit, String effectiveTimelimit, String timelimit,
        String requestedDepth, String effectiveDepth, String depth, String topic,
        int requestedMaxResults, int count, String fetchedAt, List<Result> results,
        String fallbackFrom, String fallbackReason, boolean error, String errorCode, String message, Boolean retryable,
        Integer requestedDays, String input, String market, String resolvedCode, String source, String routeReason
) {
    public enum Status { SUCCESS, EMPTY, ERROR }
    public enum PublicationTimeKind { INSTANT, DATE, UNKNOWN }
    private static final Set<String> TIME_LIMITS = Set.of("d", "w", "m", "y");
    private static final Set<String> FALLBACK_REASONS = Set.of("PRIMARY_NOT_CONFIGURED", "UPSTREAM_TIMEOUT",
            "UPSTREAM_RATE_LIMIT", "UPSTREAM_ERROR", "INVALID_PROVIDER_DATA");
    private static final ObjectMapper MAPPER = StrictResponseJson.newMapper();

    public SearchResponse {
        if (schemaVersion != 1 || status == null || query == null || searchType == null
                || !Set.of("web", "news").contains(searchType) || requestedDepth == null
                || !Set.of("basic", "advanced").contains(requestedDepth)
                || requestedMaxResults < 1 || requestedMaxResults > 20 || (requestedDays != null && requestedDays < 1)
                || (requestedTimelimit != null && !TIME_LIMITS.contains(requestedTimelimit))) {
            throw new IllegalArgumentException("Invalid search response request scope");
        }
        utcInstant(fetchedAt);
        results = List.copyOf(results);
        if (count != results.size() || count > requestedMaxResults || error != (status == Status.ERROR)
                || (status == Status.SUCCESS ? results.isEmpty() : !results.isEmpty())
                || (status == Status.ERROR ? errorCode == null || errorCode.isBlank() : errorCode != null || retryable != null)
                || !Objects.equals(timelimit, effectiveTimelimit) || !Objects.equals(depth, effectiveDepth)) {
            throw new IllegalArgumentException("Inconsistent search status/count/effective metadata");
        }
        if ((fallbackFrom == null) != (fallbackReason == null)
                || (fallbackFrom != null && (!"tavily".equals(fallbackFrom) || !FALLBACK_REASONS.contains(fallbackReason)))) {
            throw new IllegalArgumentException("Invalid search fallback metadata");
        }
        if (provider == null) {
            if (status != Status.ERROR || effectiveTimelimit != null || effectiveDepth != null || topic != null || fallbackFrom != null) {
                throw new IllegalArgumentException("Unknown search provider cannot claim effective settings");
            }
        } else {
            String actualTimeLimit = "news".equals(searchType) && "y".equals(requestedTimelimit) ? null : requestedTimelimit;
            if (!Objects.equals(actualTimeLimit, effectiveTimelimit)) throw new IllegalArgumentException("Invalid effective search time window");
            if ("tavily".equals(provider)) {
                if (!requestedDepth.equals(effectiveDepth) || topic == null || !Set.of("general", "finance", "news").contains(topic)
                        || ("news".equals(searchType) && !"news".equals(topic))
                        || fallbackFrom != null) {
                    throw new IllegalArgumentException("Invalid Tavily search metadata");
                }
            } else if ("ddg".equals(provider)) {
                if (effectiveDepth != null || !("web".equals(searchType) ? "general" : "news").equals(topic)
                        || fallbackFrom == null) {
                    throw new IllegalArgumentException("Invalid DDG search metadata");
                }
            } else {
                throw new IllegalArgumentException("Unknown search provider");
            }
        }
    }

    public static SearchResponse fromJson(JsonNode root) {
        try {
            if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("requestedMaxResults").isIntegralNumber() || !root.path("count").isIntegralNumber()
                    || !root.path("error").isBoolean() || !root.path("results").isArray()
                    || (root.hasNonNull("requestedDays") && !root.get("requestedDays").isIntegralNumber())) {
                throw new IllegalArgumentException("Missing search version/scope/count/results");
            }
            return MAPPER.treeToValue(root, SearchResponse.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid search v1 response", error);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot serialize search response", error);
        }
    }

    public static SearchResponse failure(String query, String searchType, String requestedTimelimit, String requestedDepth,
                                         int requestedMaxResults, Integer requestedDays, String errorCode, String message, Boolean retryable) {
        return new SearchResponse(1, Status.ERROR, query == null ? "" : query, null, searchType,
                requestedTimelimit, null, null, requestedDepth, null, null, null,
                requestedMaxResults, 0, Instant.now().toString(), List.of(), null, null, true, errorCode, message, retryable,
                requestedDays, null, null, null, null, null);
    }

    private static void utcInstant(String value) {
        if (value == null || !ZoneOffset.UTC.equals(OffsetDateTime.parse(value).getOffset())) {
            throw new IllegalArgumentException("Search timestamps must be UTC instants");
        }
    }

    private static void businessDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000")) {
            throw new IllegalArgumentException("Published date must be YYYY-MM-DD");
        }
        LocalDate.parse(value);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String title, String link, String snippet, String date, String source,
                         PublicationTimeKind publishedTimeKind, String publishedAt, String publishedDate, String publishedTimeBasis) {
        public Result {
            if (title == null || snippet == null || (title.isBlank() && snippet.isBlank()) || date == null || source == null
                    || link == null || link.codePoints().anyMatch(code -> Character.isWhitespace(code) || Character.isSpaceChar(code))
                    || publishedTimeKind == null || !"PROVIDER_REPORTED".equals(publishedTimeBasis)) {
                throw new IllegalArgumentException("Search result requires content, source fields and publication precision");
            }
            URI uri = URI.create(link);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Search result link must be an absolute HTTP(S) URL");
            }
            switch (publishedTimeKind) {
                case INSTANT -> {
                    utcInstant(publishedAt);
                    if (publishedDate != null) throw new IllegalArgumentException("Instant publication cannot also claim date precision");
                }
                case DATE -> {
                    businessDate(publishedDate);
                    if (publishedAt != null) throw new IllegalArgumentException("Date-only publication cannot claim an instant");
                }
                case UNKNOWN -> {
                    if (publishedAt != null || publishedDate != null) throw new IllegalArgumentException("Unknown publication must not invent a date");
                }
            }
        }
    }
}
