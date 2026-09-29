package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.model.entity.CompanyRelation;
import com.stocksage.model.entity.VectorDocument;
import com.stocksage.repository.CompanyRelationRepository;
import com.stocksage.repository.VectorDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RelationExtractionService 单元测试：聚焦三道防幻觉闸门
 * （逐字证据命中 / 置信度阈值 / 类型白名单）与无来源切片时的兜底。
 */
class RelationExtractionServiceTest {

    private ChatClient chatClient;
    private VectorDocumentRepository vectorDocumentRepository;
    private CompanyRelationRepository companyRelationRepository;
    private RelationExtractionService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        vectorDocumentRepository = mock(VectorDocumentRepository.class);
        companyRelationRepository = mock(CompanyRelationRepository.class);
        service = new RelationExtractionService(
                chatClient, vectorDocumentRepository, companyRelationRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "minConfidence", 0.5);
        ReflectionTestUtils.setField(service, "modelName", "test-extraction-model");
    }

    private void stubParent(String text) {
        VectorDocument doc = new VectorDocument();
        doc.setContentFull(text);
        doc.setMetadata("""
                {"ticker":"NVDA","company":"NVIDIA Corporation","section":"Item 1. Business",\
                "accession":"0001045810-24-000029","filing_date":"2024-02-21",\
                "doc_id":"parent_abc","is_parent":true}
                """);
        when(vectorDocumentRepository.findLatest10KBusinessParents("NVDA")).thenReturn(List.of(doc));
    }

    private void stubLlm(String json) {
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(json);
    }

    @SuppressWarnings("unchecked")
    private List<CompanyRelation> capturedSaved() {
        ArgumentCaptor<List<CompanyRelation>> captor = ArgumentCaptor.forClass(List.class);
        verify(companyRelationRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void providerCapacityRejectionDoesNotPublishAnEmptyGraphOrDeleteExistingRelations() {
        stubParent("NVIDIA's primary competitors include Advanced Micro Devices and Intel Corporation in the GPU market.");
        var capacity = new ResearchCapacityExceededException("provider-admission", null);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenThrow(new IllegalStateException("wrapped provider", capacity));
        assertThatThrownBy(() -> service.extractForTicker("NVDA")).isSameAs(capacity);
        verify(companyRelationRepository, never()).deleteBySourceTicker(anyString());
        verify(companyRelationRepository, never()).saveAll(anyList());
    }

    @Test
    void acceptsRelationWithVerbatimEvidence() {
        stubParent("NVIDIA's primary competitors include Advanced Micro Devices and Intel Corporation in the GPU market.");
        stubLlm("""
                [{"target":"Advanced Micro Devices","type":"COMPETITOR",
                  "quote":"NVIDIA's primary competitors include Advanced Micro Devices and Intel Corporation",
                  "confidence":0.92}]
                """);

        var result = service.extractForTicker("nvda");

        assertThat(result.get("accepted")).isEqualTo(1);
        verify(companyRelationRepository).deleteBySourceTicker("NVDA");

        List<CompanyRelation> saved = capturedSaved();
        assertThat(saved).hasSize(1);
        CompanyRelation relation = saved.get(0);
        assertThat(relation.getSourceTicker()).isEqualTo("NVDA");
        assertThat(relation.getTargetName()).isEqualTo("Advanced Micro Devices");
        assertThat(relation.getRelationType()).isEqualTo("COMPETITOR");
        assertThat(relation.getEvidenceSection()).isEqualTo("Item 1. Business");
        assertThat(relation.getEvidenceAccession()).isEqualTo("0001045810-24-000029");
        assertThat(relation.getEvidenceSnippet()).contains("Advanced Micro Devices");
    }

    @Test
    void dropsHallucinatedEvidenceNotInSource() {
        stubParent("NVIDIA designs GPUs and operates in a competitive market.");
        stubLlm("""
                [{"target":"Qualcomm","type":"COMPETITOR",
                  "quote":"NVIDIA competes directly with Qualcomm in mobile chipsets",
                  "confidence":0.9}]
                """);

        var result = service.extractForTicker("NVDA");

        assertThat(result.get("accepted")).isEqualTo(0);
        assertThat(result.get("droppedNoEvidence")).isEqualTo(1);
        verify(companyRelationRepository).saveAll(eq(List.of()));
    }

    @Test
    void dropsLowConfidence() {
        stubParent("NVIDIA's primary competitors include Advanced Micro Devices and Intel Corporation.");
        stubLlm("""
                [{"target":"Intel Corporation","type":"COMPETITOR",
                  "quote":"NVIDIA's primary competitors include Advanced Micro Devices and Intel Corporation",
                  "confidence":0.30}]
                """);

        var result = service.extractForTicker("NVDA");

        assertThat(result.get("accepted")).isEqualTo(0);
        assertThat(result.get("droppedLowConfidence")).isEqualTo(1);
    }

    @Test
    void dropsUnknownRelationType() {
        stubParent("NVIDIA collaborates with Advanced Micro Devices on nothing in particular here.");
        stubLlm("""
                [{"target":"Advanced Micro Devices","type":"FRENEMY",
                  "quote":"NVIDIA collaborates with Advanced Micro Devices",
                  "confidence":0.9}]
                """);

        var result = service.extractForTicker("NVDA");

        assertThat(result.get("accepted")).isEqualTo(0);
        assertThat(result.get("droppedBadType")).isEqualTo(1);
    }

    @Test
    void returnsErrorWhenNoFilingChunks() {
        when(vectorDocumentRepository.findLatest10KBusinessParents("NVDA")).thenReturn(List.of());

        var result = service.extractForTicker("NVDA");

        assertThat(result.get("error")).isEqualTo(true);
        verify(chatClient, never()).prompt();
        verify(companyRelationRepository, never()).saveAll(anyList());
        verify(companyRelationRepository, never()).deleteBySourceTicker(anyString());
    }
}
