package com.stocksage.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.rag.RagService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 财报原文检索能力：在知识库中按"标的 + 表单 + 主题"召回 SEC 10-K/10-Q 片段。
 *
 * <p>标的和表单由服务端给定并作为 Milvus 过滤条件，主题只用于语义召回，不能改变过滤范围。</p>
 */
@Component
public class LocalFilingTextCapabilityAdapter implements CapabilityAdapter {

    public static final String ID = "local.filing.searchText";
    static final Set<String> FORMS = Set.of("10-K", "10-Q");
    private static final Set<String> KEYS = Set.of("ticker", "form", "topic");
    private static final int MAX_RESULTS = 5;
    private static final int TOTAL_TEXT_CHARS = 3200;
    /** 两段命中文本的 5 词片段重合到这个比例，视为同一段（如 10-K 与各期 10-Q 反复出现的风险因素）。 */
    private static final double DUPLICATE_OVERLAP = 0.8;
    private static final int SHINGLE_WORDS = 5;

    private final RagService ragService;
    private final ObjectMapper objectMapper;
    private final DataServiceClient dataServiceClient;

    public LocalFilingTextCapabilityAdapter(RagService ragService, ObjectMapper objectMapper,
                                            DataServiceClient dataServiceClient) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
        this.dataServiceClient = dataServiceClient;
    }

    @Override
    public String capabilityId() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) throws Exception {
        ResearchCapabilityArguments.requireOnlyKeys(arguments, KEYS);
        String ticker = ResearchCapabilityArguments.requireTicker(arguments);
        String form = ResearchCapabilityArguments.requireEnum(arguments, "form", FORMS);
        String topic = ResearchCapabilityArguments.requireText(arguments, "topic", 120);

        // 过滤之外再校验一次元数据，任何跨标的或跨表单的片段都不进入结果。
        List<Document> documents = distinctPassages(ragService.retrieveForTickerForm(topic, ticker, form).stream()
                .filter(document -> ticker.equals(String.valueOf(document.getMetadata().get("ticker")))
                        && form.equals(String.valueOf(document.getMetadata().get("filing_type"))))
                .toList());
        if (documents.isEmpty()) {
            // 空结果返回空内容，证据层记为 EMPTY，不能算作新增可用证据。
            return "";
        }
        int perPassage = TOTAL_TEXT_CHARS / documents.size();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Document document : documents) {
            Map<String, Object> meta = document.getMetadata();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filingType", form);
            item.put("filingDate", text(meta, "filing_date"));
            item.put("section", text(meta, "section", "section_title"));
            item.put("title", text(meta, "title"));
            item.put("source", text(meta, "source", "source_id"));
            item.put("docId", document.getId());
            item.put("sourceId", text(meta, "source_id"));
            item.put("accession", text(meta, "accession"));
            item.put("documentUrl", text(meta, "document_url"));
            item.put("text", matchedWindows(document, perPassage));
            results.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", "STOCKSAGE_KB_SEC_FILING");
        payload.put("ticker", ticker);
        payload.put("form", form);
        payload.put("topic", topic);
        Map<String, Object> first = results.get(0);
        long sourceCount = results.stream().map(item -> item.get("accession") + "|" + item.get("documentUrl")).distinct().count();
        payload.put("selectedSource", Map.of("accession", first.get("accession"), "documentUrl", first.get("documentUrl"),
                "filingType", form, "selection", "FIRST_PASSAGE"));
        payload.put("otherSourceCount", Math.max(0, sourceCount - 1));
        String sourceRef = "provider://sec-filing/" + ticker + "/" + form;
        if (!String.valueOf(first.get("accession")).isBlank() || !String.valueOf(first.get("documentUrl")).isBlank()) {
            sourceRef += "?accession=" + java.net.URLEncoder.encode(String.valueOf(first.get("accession")), java.nio.charset.StandardCharsets.UTF_8)
                    + "&documentUrl=" + java.net.URLEncoder.encode(String.valueOf(first.get("documentUrl")), java.nio.charset.StandardCharsets.UTF_8)
                    + "&otherSourceCount=" + Math.max(0, sourceCount - 1);
        }
        payload.put("sourceRef", sourceRef);
        payload.put("resultCount", results.size());
        payload.put("fetchedAt", java.time.Instant.now().toString());
        Map<String, Object> latestFiling = latestFiling(ticker, form);
        if (latestFiling != null) payload.put("latestFiling", latestFiling);
        payload.put("results", results);
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * SEC 申报索引中该表单最新一份的申报日期（带缓存）。时效判断据此核对检索到的段落是否来自最新申报；
     * 读不到时省略，时效保持未确认，不影响检索结果本身。
     */
    private Map<String, Object> latestFiling(String ticker, String form) {
        try {
            JsonNode filing = objectMapper.readTree(dataServiceClient.getEdgarFilings(ticker, form, 1)).path("filings").path(0);
            String filed = filing.path("filing_date").asText("");
            if (filed.isBlank()) return null;
            Map<String, Object> latest = new LinkedHashMap<>();
            latest.put("filingDate", filed);
            latest.put("reportDate", filing.path("report_date").asText(""));
            return latest;
        } catch (Exception unavailable) {
            com.stocksage.exception.ResearchBudgetExceededException.rethrowIfPresent(unavailable);
            com.stocksage.exception.ResearchCapacityExceededException.rethrowIfPresent(unavailable);
            return null;
        }
    }

    /**
     * 按相关度顺序取不重复的段落。不同申报里几乎相同的段落只留一段，并换成申报日期最新的那一份；
     * 否则同一段风险因素会因 10-K 和每期 10-Q 都有而占满名额。
     */
    private static List<Document> distinctPassages(List<Document> candidates) {
        List<Document> kept = new ArrayList<>();
        List<Set<String>> keptShingles = new ArrayList<>();
        for (Document candidate : candidates) {
            Set<String> shingles = shingles(matchedText(candidate));
            int duplicateOf = -1;
            for (int index = 0; index < kept.size() && duplicateOf < 0; index++) {
                if (overlap(shingles, keptShingles.get(index)) >= DUPLICATE_OVERLAP) duplicateOf = index;
            }
            if (duplicateOf < 0) {
                if (kept.size() < MAX_RESULTS) {
                    kept.add(candidate);
                    keptShingles.add(shingles);
                }
            } else if (text(candidate.getMetadata(), "filing_date")
                    .compareTo(text(kept.get(duplicateOf).getMetadata(), "filing_date")) > 0) {
                kept.set(duplicateOf, candidate);
                keptShingles.set(duplicateOf, shingles);
            }
        }
        return kept;
    }

    /** 与查询命中的子块文本；没有命中记录时用整段。 */
    private static String matchedText(Document document) {
        Object matched = document.getMetadata().get(RagService.MATCHED_CHILD_TEXTS);
        String hits = matched instanceof List<?> texts
                ? texts.stream().filter(String.class::isInstance).map(String.class::cast).reduce("", (a, b) -> a + " " + b)
                : "";
        return hits.isBlank() ? compact(document.getText()) : compact(hits);
    }

    private static Set<String> shingles(String text) {
        List<String> words = java.util.Arrays.stream(text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty()).toList();
        Set<String> shingles = new java.util.HashSet<>();
        if (words.size() < SHINGLE_WORDS) {
            shingles.add(String.join(" ", words));
            return shingles;
        }
        for (int index = 0; index + SHINGLE_WORDS <= words.size(); index++) {
            shingles.add(String.join(" ", words.subList(index, index + SHINGLE_WORDS)));
        }
        return shingles;
    }

    /** 较短一段被另一段包含的比例。 */
    private static double overlap(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        return smaller.stream().filter(larger::contains).count() / (double) smaller.size();
    }

    private static String matchedWindows(Document document, int maxChars) {
        String parent = compact(document.getText());
        Object matched = document.getMetadata().get(RagService.MATCHED_CHILD_TEXTS);
        List<String> hits = matched instanceof List<?> texts
                ? texts.stream().filter(String.class::isInstance).map(String.class::cast)
                        .map(LocalFilingTextCapabilityAdapter::compact).filter(text -> !text.isBlank()).distinct().toList()
                : List.of();
        if (hits.isEmpty()) hits = List.of(parent);
        if (parent.length() <= maxChars && hits.stream().allMatch(parent::contains)) return parent;
        int perHit = (maxChars - (hits.size() - 1) * 3) / hits.size();
        List<String> windows = new ArrayList<>();
        for (String hit : hits) {
            int position = parent.indexOf(hit);
            String source = position < 0 ? hit : parent;
            if (source.length() <= perHit) {
                windows.add(source);
                continue;
            }
            int width = perHit - 2;
            int center = Math.max(0, position) + hit.length() / 2;
            int start = Math.max(0, Math.min(source.length() - width, center - width / 2));
            int end = start + width;
            windows.add((start > 0 ? "…" : "") + source.substring(start, end) + (end < source.length() ? "…" : ""));
        }
        return String.join(" … ", windows.stream().distinct().toList());
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String text(Map<String, Object> meta, String... keys) {
        for (String key : keys) {
            Object value = meta.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }
}
