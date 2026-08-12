package com.stocksage.service;

import com.stocksage.model.entity.CompanyRelation;
import com.stocksage.repository.CompanyRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 公司关系图谱的只读读取服务。
 *
 * <p>把 {@link CompanyRelation} 边组装成前端可直接渲染的 ego-graph 载荷：
 * 一个中心节点 + 一圈 spoke 节点，每个 spoke 自带关系类型、置信度、逐字证据与出处。
 * 同一对端在不同关系类型下会各占一个 spoke（如 Samsung 既是竞争对手又是供应商），
 * 节点 id 用 {@code type::target} 区分。同类型下的别名/简称（Intel/Intel Corporation、
 * AMD/Advanced Micro Devices）按归一规则合并成一个节点，保留先到的高置信代表。
 * 只依赖仓储、不触碰 LLM，保证读取路径轻量。</p>
 */
@Service
@RequiredArgsConstructor
public class CompanyRelationService {

    /** 按 ticker 读取已由离线抽取流程写入的公司关系边。 */
    private final CompanyRelationRepository companyRelationRepository;

    /**
     * 读取并组装某 ticker 的关系图谱；无数据时返回 empty=true 的空图，由前端走空态。
     *
     * @param ticker 中心公司的美股 ticker
     * @return 包含 center、nodes、counts 和 empty 的前端图谱载荷
     */
    public Map<String, Object> getGraph(String ticker) {
        String normTicker = ticker == null ? "" : ticker.trim().toUpperCase();
        List<CompanyRelation> relations = normTicker.isEmpty()
                ? List.of()
                : companyRelationRepository.findBySourceTickerOrderByConfidenceDesc(normTicker);

        String centerLabel = relations.stream()
                .map(CompanyRelation::getSourceName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(normTicker);

        // 实体合并：同一对端在不同 chunk 用了全称/简称（Huawei vs Huawei Technologies Co. Ltd.）会落成两行；
        // relations 已按置信度降序，先到的高置信全称作代表，后到的别名（短名是其词前缀，或缩写表命中）被合并掉。
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<List<String>>> acceptedByType = new LinkedHashMap<>();
        for (CompanyRelation relation : relations) {
            String type = relation.getRelationType();
            List<String> tokens = canonicalTokens(relation.getTargetName());
            List<List<String>> accepted = acceptedByType.computeIfAbsent(type, k -> new ArrayList<>());
            if (accepted.stream().anyMatch(existing -> isSameEntity(tokens, existing))) {
                continue;
            }
            accepted.add(tokens);
            counts.merge(type, 1, Integer::sum);

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", type + "::" + relation.getTargetName().toLowerCase());
            node.put("label", relation.getTargetName());
            node.put("type", type);
            node.put("ticker", relation.getTargetTicker());
            node.put("confidence", relation.getConfidence());
            node.put("section", relation.getEvidenceSection());
            node.put("accession", relation.getEvidenceAccession());
            node.put("filingDate", relation.getFilingDate());
            node.put("snippet", relation.getEvidenceSnippet());
            nodes.add(node);
        }

        Map<String, Object> center = new LinkedHashMap<>();
        center.put("ticker", normTicker);
        center.put("label", centerLabel);

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("ticker", normTicker);
        graph.put("center", center);
        graph.put("nodes", nodes);
        graph.put("counts", counts);
        graph.put("empty", nodes.isEmpty());
        return graph;
    }

    /** 公司名末尾的法律后缀，归一时剥掉以便简称/全称能对上（"Intel Corporation"→"Intel"）。 */
    private static final Pattern LEGAL_SUFFIX = Pattern.compile(
            "[,，]?\\s*(Incorporated|Inc\\.?|Corporation|Corp\\.?|Co\\.?\\s*,?\\s*Ltd\\.?|"
                    + "Company\\s+Limited|Company|Limited|Ltd\\.?|LLC|PLC|Holdings)\\.?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 常见缩写 → 规范名，用于把无法靠词前缀对上的别名合并（AMD↔Advanced Micro Devices）。 */
    private static final Map<String, String> ALIASES = Map.of(
            "amd", "advanced micro devices",
            "hpe", "hewlett packard enterprise",
            "tsmc", "taiwan semiconductor manufacturing");

    /**
     * 把对端公司名归一成可比较的小写词序列：剥法律后缀、去标点、套缩写表。
     *
     * @param name 原始公司名
     * @return 用于别名比较的词序列
     */
    private List<String> canonicalTokens(String name) {
        String stripped = stripLegalSuffix(name).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        stripped = ALIASES.getOrDefault(stripped, stripped);
        if (stripped.isEmpty()) {
            return List.of();
        }
        return List.of(stripped.split(" "));
    }

    /**
     * 反复剥离末尾法律后缀（处理 "... Holdings Inc." 这类双重后缀）；剥空则回退原名。
     *
     * @param name 原始公司名
     * @return 去除法律实体后缀的名称
     */
    private String stripLegalSuffix(String name) {
        String text = name == null ? "" : name.trim();
        for (int i = 0; i < 2; i++) {
            String next = LEGAL_SUFFIX.matcher(text).replaceAll("").replaceAll("[,，]\\s*$", "").trim();
            if (next.equals(text) || next.isEmpty()) {
                break;
            }
            text = next;
        }
        return text.isEmpty() ? (name == null ? "" : name) : text;
    }

    /**
     * 两个归一词序列是否指向同一实体：其一是另一个的词前缀即视为同一家
     * （"Huawei" 之于 "Huawei Technologies"）。任一为空时退化为严格相等，避免空名误并。
     *
     * @param a 第一个公司名词序列
     * @param b 第二个公司名词序列
     * @return true 表示可合并为同一图节点
     */
    private boolean isSameEntity(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b);
        }
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = a.size() <= b.size() ? b : a;
        for (int i = 0; i < shorter.size(); i++) {
            if (!shorter.get(i).equals(longer.get(i))) {
                return false;
            }
        }
        return true;
    }
}
