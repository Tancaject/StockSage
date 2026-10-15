package com.stocksage.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final int MAX_TEXT_CHARS = 1200;

    private final RagService ragService;
    private final ObjectMapper objectMapper;

    public LocalFilingTextCapabilityAdapter(RagService ragService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
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

        List<Map<String, Object>> results = new ArrayList<>();
        for (Document document : ragService.retrieveForTickerForm(topic, ticker, form)) {
            if (results.size() >= MAX_RESULTS) break;
            Map<String, Object> meta = document.getMetadata();
            // 过滤之外再校验一次元数据，任何跨标的或跨表单的片段都不进入结果。
            if (!ticker.equals(String.valueOf(meta.get("ticker"))) || !form.equals(String.valueOf(meta.get("filing_type")))) {
                continue;
            }
            String text = document.getText() == null ? "" : document.getText().strip();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filingType", form);
            item.put("filingDate", text(meta, "filing_date"));
            item.put("section", text(meta, "section", "section_title"));
            item.put("title", text(meta, "title"));
            item.put("source", text(meta, "source", "source_id"));
            item.put("text", text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
            results.add(item);
        }

        if (results.isEmpty()) {
            // 空结果返回空内容，证据层记为 EMPTY，不能算作新增可用证据。
            return "";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", "STOCKSAGE_KB_SEC_FILING");
        payload.put("ticker", ticker);
        payload.put("form", form);
        payload.put("topic", topic);
        payload.put("sourceRef", "provider://sec-filing/" + ticker + "/" + form);
        payload.put("resultCount", results.size());
        payload.put("results", results);
        return objectMapper.writeValueAsString(payload);
    }

    private static String text(Map<String, Object> meta, String... keys) {
        for (String key : keys) {
            Object value = meta.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }
}
