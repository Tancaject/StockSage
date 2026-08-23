package com.stocksage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.PlannerEvalMode;
import com.stocksage.model.dto.PlannerEvalRequest;
import com.stocksage.model.dto.PlannerEvalResponse;
import com.stocksage.service.PlannerEvalService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentEvalControllerTest {

    private final PlannerEvalService service = mock(PlannerEvalService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AgentEvalController(service))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesTypedPlannerEndpoint() throws Exception {
        when(service.evaluate(any())).thenReturn(new PlannerEvalResponse(
                "planner_eval_v2", PlannerEvalMode.DETERMINISTIC, "passed",
                1, 1, 0, 1.0, 1.0, java.util.Map.of(),
                1.0, 0.0, 1.0,
                3L, "2026-07-24T00:00:00", List.of()
        ));

        mockMvc.perform(post("/api/eval/agent/planner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "DETERMINISTIC",
                                  "cases": [{
                                    "id": "direct-1",
                                    "query": "what is PE",
                                    "ragHitCount": 1,
                                    "expectedRoute": "DIRECT",
                                    "requiredActions": ["KNOWLEDGE_RETRIEVAL"],
                                    "forbiddenActions": ["BULL_RESEARCHER"],
                                    "critical": true
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("planner_eval_v2"))
                .andExpect(jsonPath("$.mode").value("DETERMINISTIC"))
                .andExpect(jsonPath("$.status").value("passed"));
    }

    @Test
    void rejectsEmptyCaseList() throws Exception {
        mockMvc.perform(post("/api/eval/agent/planner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("mode", "DETERMINISTIC", "cases", List.of())
                        )))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsV2ContextAndStrictRoutingAssertions() throws Exception {
        when(service.evaluate(any())).thenReturn(new PlannerEvalResponse(
                "planner_eval_v2", PlannerEvalMode.LIVE_COORDINATOR, "passed",
                1, 1, 0, 1.0, 1.0, java.util.Map.of(),
                1.0, 0.0, 1.0,
                3L, "2026-08-20T00:00:00", List.of()
        ));

        mockMvc.perform(post("/api/eval/agent/planner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "LIVE_COORDINATOR",
                                  "cases": [{
                                    "id": "context-news-1",
                                    "query": "那它今天有什么最新消息？",
                                    "ragHitCount": 0,
                                    "expectedRoute": "NEWS",
                                    "requiredActions": ["NEWS_AGENT"],
                                    "forbiddenActions": [],
                                    "critical": true,
                                    "recentTurns": [
                                      "user: 看看 AAPL 最新财报",
                                      "assistant: 已总结 AAPL 最新财报"
                                    ],
                                    "expectedFineIntent": "NEWS_EVENT",
                                    "expectedDecisionSource": "INTENT_FUSION",
                                    "requireNoFallback": true,
                                    "expectedResolvedQueryContains": "AAPL"
                                  }]
                                }
                                """))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<PlannerEvalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(PlannerEvalRequest.class);
        verify(service).evaluate(captor.capture());
        assertThat(captor.getValue().cases().get(0).recentTurns())
                .containsExactly("user: 看看 AAPL 最新财报", "assistant: 已总结 AAPL 最新财报");
        assertThat(captor.getValue().cases().get(0).expectedFineIntent())
                .isEqualTo("NEWS_EVENT");
        assertThat(captor.getValue().cases().get(0).requireNoFallback())
                .isTrue();
        assertThat(captor.getValue().cases().get(0).expectedResolvedQueryContains())
                .isEqualTo("AAPL");
    }
}
