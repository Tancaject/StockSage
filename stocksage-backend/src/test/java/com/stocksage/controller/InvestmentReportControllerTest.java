package com.stocksage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.identity.RequestIdentity;
import com.stocksage.exception.GlobalExceptionHandler;
import com.stocksage.exception.ResourceNotFoundException;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.dto.InvestmentReportReviewRequest;
import com.stocksage.model.dto.InvestmentReportReviewSummary;
import com.stocksage.model.dto.InvestmentReportVersionSummary;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.research.InvestmentReportVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvestmentReportControllerTest {

    private final InvestmentReportVersionService service = mock(InvestmentReportVersionService.class);
    private final RequestIdentity requestIdentity = mock(RequestIdentity.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InvestmentReportController controller = new InvestmentReportController(service, requestIdentity);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(requestIdentity.currentUserId()).thenReturn("u_001");
    }

    @Test
    void ownerCanReadReportDetailThroughCurrentIdentity() throws Exception {
        InvestmentReportVersionService.ReportDetail detail = detail();
        when(service.getReportDetail("u_001", 31L)).thenReturn(detail);

        mockMvc.perform(get("/api/reports/investment/31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.id").value(31))
                .andExpect(jsonPath("$.summary.reviewStatus").value("DRAFT"))
                .andExpect(jsonPath("$.evidenceItems").isArray())
                .andExpect(jsonPath("$.citations").isArray())
                .andExpect(jsonPath("$.reviewHistory").isArray())
                .andExpect(jsonPath("$.reviewHistory[0].reviewerUserId").value("u_001"))
                .andExpect(jsonPath("$.reviewHistory[0].fromStatus").value("DRAFT"))
                .andExpect(jsonPath("$.reviewHistory[0].toStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.reviewHistory[0].createdAt").exists());

        verify(service).getReportDetail("u_001", 31L);
    }

    @Test
    void crossUserOrMissingReportIsReturnedAsNotFound() throws Exception {
        when(service.getReportDetail("u_001", 31L))
                .thenThrow(new ResourceNotFoundException("Investment report not found"));

        mockMvc.perform(get("/api/reports/investment/31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Investment report not found"));
    }

    @Test
    void patchBindsReviewContractAndReturnsUpdatedDetail() throws Exception {
        InvestmentReportReviewRequest request = new InvestmentReportReviewRequest(
                InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                "Ready",
                0L
        );
        when(service.reviewReport(eq("u_001"), eq(31L), eq(request))).thenReturn(detail());

        mockMvc.perform(patch("/api/reports/investment/31/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());

        verify(service).reviewReport("u_001", 31L, request);
    }

    @Test
    void missingExpectedLockVersionIsBadRequest() throws Exception {
        mockMvc.perform(patch("/api/reports/investment/31/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_REVIEW","comment":"Ready"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "expectedLockVersion: must not be null"));
    }

    @Test
    void staleReviewIsConflict() throws Exception {
        InvestmentReportReviewRequest request = new InvestmentReportReviewRequest(
                InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                null,
                0L
        );
        when(service.reviewReport(eq("u_001"), eq(31L), eq(request)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Investment report review changed; refresh and retry"
                ));

        mockMvc.perform(patch("/api/reports/investment/31/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Investment report review changed; refresh and retry"));
    }

    private InvestmentReportVersionService.ReportDetail detail() {
        InvestmentReportVersionSummary summary = new InvestmentReportVersionSummary(
                31L,
                10L,
                "NVDA",
                3,
                "HOLD",
                "d".repeat(64),
                "c".repeat(64),
                "STRONG",
                "qwen3.6-max",
                null,
                null,
                "Should I buy NVDA?",
                "Hold"
        );
        InvestmentReportReviewSummary review = new InvestmentReportReviewSummary(
                71L,
                31L,
                InvestmentReportVersion.ReviewStatus.DRAFT,
                InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                "Ready",
                "u_001",
                LocalDateTime.parse("2026-07-28T10:30:00")
        );
        return new InvestmentReportVersionService.ReportDetail(
                summary,
                InvestmentReport.builder().recommendation("HOLD").build(),
                List.of(),
                List.of(),
                List.of(review)
        );
    }
}
