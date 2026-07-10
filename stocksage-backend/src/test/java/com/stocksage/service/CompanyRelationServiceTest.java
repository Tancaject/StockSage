package com.stocksage.service;

import com.stocksage.model.entity.CompanyRelation;
import com.stocksage.repository.CompanyRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CompanyRelationService 单元测试：ego-graph 组装（中心节点 / spoke 节点 / 类型计数）
 * 与无关系时的空图兜底。
 */
class CompanyRelationServiceTest {

    private CompanyRelationRepository repository;
    private CompanyRelationService service;

    @BeforeEach
    void setUp() {
        repository = mock(CompanyRelationRepository.class);
        service = new CompanyRelationService(repository);
    }

    private CompanyRelation relation(String target, String type, double confidence) {
        CompanyRelation relation = new CompanyRelation();
        relation.setSourceTicker("NVDA");
        relation.setSourceName("NVIDIA Corporation");
        relation.setTargetName(target);
        relation.setRelationType(type);
        relation.setConfidence(confidence);
        relation.setEvidenceSection("Item 1. Business");
        relation.setEvidenceAccession("0001045810-24-000029");
        relation.setFilingDate("2024-02-21");
        relation.setEvidenceSnippet("...quote about " + target + "...");
        return relation;
    }

    @Test
    @SuppressWarnings("unchecked")
    void assemblesEgoGraphWithCenterNodesAndCounts() {
        when(repository.findBySourceTickerOrderByConfidenceDesc("NVDA")).thenReturn(List.of(
                relation("Advanced Micro Devices", "COMPETITOR", 0.92),
                relation("TSMC", "SUPPLIER", 0.88),
                relation("Samsung", "COMPETITOR", 0.70)
        ));

        Map<String, Object> graph = service.getGraph("nvda");

        assertThat(graph.get("empty")).isEqualTo(false);
        assertThat(graph.get("ticker")).isEqualTo("NVDA");
        assertThat(((Map<String, Object>) graph.get("center")).get("label")).isEqualTo("NVIDIA Corporation");

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0))
                .containsEntry("label", "Advanced Micro Devices")
                .containsEntry("type", "COMPETITOR")
                .containsKey("snippet");

        Map<String, Integer> counts = (Map<String, Integer>) graph.get("counts");
        assertThat(counts).containsEntry("COMPETITOR", 2).containsEntry("SUPPLIER", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergesAliasNodesKeepingHighestConfidenceRepresentative() {
        // 同一批对手在两个 chunk 里被写成全称(置信1.0)与简称(置信0.85)，落成 6 行；
        // relations 已按置信度降序返回，合并后应只剩 3 个全称代表 + TSMC。
        when(repository.findBySourceTickerOrderByConfidenceDesc("NVDA")).thenReturn(List.of(
                relation("Huawei Technologies Co. Ltd.", "COMPETITOR", 1.0),
                relation("Intel Corporation", "COMPETITOR", 1.0),
                relation("Advanced Micro Devices, Inc.", "COMPETITOR", 1.0),
                relation("TSMC", "SUPPLIER", 1.0),
                relation("Huawei", "COMPETITOR", 0.85),
                relation("Intel", "COMPETITOR", 0.85),
                relation("AMD", "COMPETITOR", 0.85)
        ));

        Map<String, Object> graph = service.getGraph("NVDA");

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        assertThat(nodes).hasSize(4);
        List<String> labels = nodes.stream().map(n -> (String) n.get("label")).toList();
        assertThat(labels)
                .contains("Huawei Technologies Co. Ltd.", "Intel Corporation", "Advanced Micro Devices, Inc.", "TSMC")
                .doesNotContain("Huawei", "Intel", "AMD");

        Map<String, Integer> counts = (Map<String, Integer>) graph.get("counts");
        assertThat(counts).containsEntry("COMPETITOR", 3).containsEntry("SUPPLIER", 1);
    }

    @Test
    void returnsEmptyGraphWhenNoRelations() {
        when(repository.findBySourceTickerOrderByConfidenceDesc("NVDA")).thenReturn(List.of());

        Map<String, Object> graph = service.getGraph("NVDA");

        assertThat(graph.get("empty")).isEqualTo(true);
        assertThat((List<?>) graph.get("nodes")).isEmpty();
        assertThat(((Map<?, ?>) graph.get("center")).get("label")).isEqualTo("NVDA");
    }
}
