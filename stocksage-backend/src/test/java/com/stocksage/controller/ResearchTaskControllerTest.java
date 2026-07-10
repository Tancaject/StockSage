package com.stocksage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.config.RequestIdentity;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.service.InvestmentReportVersionService;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResearchTaskControllerTest {

    @Test
    void missingOwnedTaskReturnsEmptyNotFoundForSseClient() throws Exception {
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        InvestmentReportVersionService reports = mock(InvestmentReportVersionService.class);
        TraceEventStore store = mock(TraceEventStore.class);
        TraceEventRelay relay = mock(TraceEventRelay.class);
        RequestIdentity identity = mock(RequestIdentity.class);
        ResearchTaskController controller = new ResearchTaskController(
                repository, reports, store, relay, identity, new ObjectMapper());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(identity.currentUserId()).thenReturn("stranger");
        when(repository.findByIdAndUserId(99L, "stranger")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/research-tasks/99/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void liveStreamFallsBackToDatabaseTerminalWhenWorkerEventWasLost() {
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        InvestmentReportVersionService reports = mock(InvestmentReportVersionService.class);
        TraceEventStore store = mock(TraceEventStore.class);
        TraceEventRelay relay = mock(TraceEventRelay.class);
        RequestIdentity identity = mock(RequestIdentity.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ResearchTaskController controller = new ResearchTaskController(
                repository, reports, store, relay, identity, objectMapper);

        ResearchTask running = task(7L, ResearchTask.Status.RUNNING);
        ResearchTask completed = task(7L, ResearchTask.Status.SUCCEEDED);
        completed.setResultReportVersionId(12L);
        when(identity.currentUserId()).thenReturn("u1");
        when(repository.findByIdAndUserId(7L, "u1"))
                .thenReturn(Optional.of(running), Optional.of(completed));
        when(relay.live("trace-7", null)).thenReturn(Flux.never());
        when(reports.findReportBrief("u1", 12L)).thenReturn(Optional.of("final report"));

        List<ServerSentEvent<String>> events = controller.taskEvents(7L, null)
                .collectList()
                .block(Duration.ofSeconds(3));

        assertThat(events).singleElement()
                .satisfies(event -> assertThat(event.data())
                        .contains("task-final")
                        .contains("final report"));
    }

    private ResearchTask task(Long id, ResearchTask.Status status) {
        ResearchTask task = new ResearchTask();
        task.setId(id);
        task.setUserId("u1");
        task.setConversationId(5L);
        task.setTicker("AAPL");
        task.setStatus(status);
        task.setStage(status == ResearchTask.Status.SUCCEEDED
                ? ResearchTask.Stage.COMPLETE
                : ResearchTask.Stage.AGENT_DEBATE);
        task.setPayloadJson("{\"traceId\":\"trace-7\"}");
        return task;
    }
}
