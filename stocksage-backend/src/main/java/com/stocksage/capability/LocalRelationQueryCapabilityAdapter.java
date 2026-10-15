package com.stocksage.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.service.CompanyRelationService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关系图谱查询能力：读取已从 10-K 抽取、带逐字证据的公司关系（只读，不触发抽取）。
 */
@Component
public class LocalRelationQueryCapabilityAdapter implements CapabilityAdapter {

    public static final String ID = "local.relation.query";
    static final Set<String> RELATION_TYPES = Set.of("COMPETITOR", "CUSTOMER", "SUPPLIER", "PARTNER");
    private static final Set<String> KEYS = Set.of("ticker", "relationType");
    private static final int MAX_RELATIONS = 10;

    private final CompanyRelationService companyRelationService;
    private final ObjectMapper objectMapper;

    public LocalRelationQueryCapabilityAdapter(CompanyRelationService companyRelationService, ObjectMapper objectMapper) {
        this.companyRelationService = companyRelationService;
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
    @SuppressWarnings("unchecked")
    public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) throws Exception {
        ResearchCapabilityArguments.requireOnlyKeys(arguments, KEYS);
        String ticker = ResearchCapabilityArguments.requireTicker(arguments);
        String relationType = ResearchCapabilityArguments.requireEnum(arguments, "relationType", RELATION_TYPES);

        Object rawNodes = companyRelationService.getGraph(ticker).get("nodes");
        List<Map<String, Object>> nodes = rawNodes instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        List<Map<String, Object>> relations = nodes.stream()
                .filter(node -> relationType.equals(node.get("type")))
                .limit(MAX_RELATIONS)
                .map(node -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (String key : List.of("label", "ticker", "confidence", "section", "accession", "filingDate", "snippet")) {
                        item.put(key, node.get(key));
                    }
                    return item;
                })
                .toList();
        if (relations.isEmpty()) {
            // 空结果返回空内容，证据层记为 EMPTY，不能算作新增可用证据。
            return "";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", "STOCKSAGE_RELATION_GRAPH");
        payload.put("ticker", ticker);
        payload.put("relationType", relationType);
        payload.put("sourceRef", "provider://relation-graph/" + ticker + "/" + relationType);
        payload.put("resultCount", relations.size());
        payload.put("relations", relations);
        return objectMapper.writeValueAsString(payload);
    }
}
