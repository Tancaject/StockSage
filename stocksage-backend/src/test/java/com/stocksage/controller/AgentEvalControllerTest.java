package com.stocksage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.PlannerEvalMode;
import com.stocksage.model.dto.PlannerEvalResponse;
import com.stocksage.service.PlannerEvalService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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
                "planner_eval_v1", PlannerEvalMode.DETERMINISTIC, "passed",
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
                .andExpect(jsonPath("$.schemaVersion").value("planner_eval_v1"))
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
}
