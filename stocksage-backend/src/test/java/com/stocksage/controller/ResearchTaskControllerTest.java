package com.stocksage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.identity.RequestIdentity;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.research.InvestmentReportVersionService;
import com.stocksage.research.ResearchTaskObservationService;
import com.stocksage.research.ModelInvocationStore;
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
    void usageEndpointUsesAuthenticatedOwnerAndPreservesUnknownValues() throws Exception {
        RequestIdentity identity = mock(RequestIdentity.class);
        ModelInvocationStore invocations = mock(ModelInvocationStore.class);
        ResearchTaskController controller = new ResearchTaskController(mock(ResearchTaskRepository.class),
                mock(ResearchTaskObservationService.class), identity, invocations);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(identity.currentUserId()).thenReturn("u1");
        when(invocations.usage(7L, "u1")).thenReturn(new ModelInvocationStore.RunUsage(
                7L, "RECORDED_DEEP_INVOCATIONS", "SUCCEEDED", 1,
                new ModelInvocationStore.UsageTotals(0, 0, 0, null, null, null, 0, 0, 0, "NO_DATA",
                        new ModelInvocationStore.CostTotals("UNAVAILABLE", 0, 0, 0, List.of(), java.util.Map.of())),
                List.of(), "UNAVAILABLE", null, new ModelInvocationStore.RunBudget(null, null, 0, null,
                        new ModelInvocationStore.TokenBudget("UNAVAILABLE", null, null, null))));
        String json = mvc.perform(get("/api/research-tasks/7/usage").param("userId", "stranger"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var body = new ObjectMapper().readTree(json);
        assertThat(body.path("observedUsage").path("totalTokens").isNull()).isTrue();
        assertThat(body.path("observedUsage").path("completeness").asText()).isEqualTo("NO_DATA");
        org.mockito.Mockito.verify(invocations).usage(7L, "u1");
        when(invocations.usage(8L, "u1"))
                .thenThrow(new com.stocksage.exception.ResourceNotFoundException("Research task not found"));
        mvc.perform(get("/api/research-tasks/8/usage")).andExpect(status().isNotFound());
    }

    @Test
    void missingOwnedTaskReturnsEmptyNotFoundForSseClient() throws Exception {
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        InvestmentReportVersionService reports = mock(InvestmentReportVersionService.class);
        TraceEventStore store = mock(TraceEventStore.class);
        TraceEventRelay relay = mock(TraceEventRelay.class);
        RequestIdentity identity = mock(RequestIdentity.class);
        ResearchTaskController controller = new ResearchTaskController(
                repository, new ResearchTaskObservationService(repository, reports, store, relay, new ObjectMapper()), identity,
                mock(ModelInvocationStore.class));
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
                repository, new ResearchTaskObservationService(repository, reports, store, relay, objectMapper), identity,
                mock(ModelInvocationStore.class));


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

    @Test
    void completedSubmissionReplaysOriginalIdentityAndDatabaseReportAfterEventsExpire() throws Exception {
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        InvestmentReportVersionService reports = mock(InvestmentReportVersionService.class);
        TraceEventStore store = mock(TraceEventStore.class);
        TraceEventRelay relay = mock(TraceEventRelay.class);
        ObjectMapper mapper = new ObjectMapper();
        var observations = new ResearchTaskObservationService(repository, reports, store, relay, mapper);
        ResearchTask completed = task(7L, ResearchTask.Status.SUCCEEDED);
        completed.setResultReportVersionId(12L);
        when(repository.findByIdAndUserId(7L, "u1")).thenReturn(Optional.of(completed));
        when(store.replayRange("trace-7", null)).thenReturn(List.of());
        when(reports.findReportBrief("u1", 12L)).thenReturn(Optional.of("original report"));

        List<String> chunks = observations.observeChat(completed, "u1").collectList().block(Duration.ofSeconds(3));

        assertThat(chunks).hasSize(2);
        var identity = mapper.readTree(chunks.get(0));
        assertThat(identity.path("type").asText()).isEqualTo("meta");
        assertThat(identity.path("conversationId").asLong()).isEqualTo(5L);
        assertThat(identity.path("traceId").asText()).isEqualTo("trace-7");
        assertThat(identity.path("metadata").path("taskId").asLong()).isEqualTo(7L);
        assertThat(mapper.readTree(chunks.get(1)).path("content").asText()).isEqualTo("original report");
        org.mockito.Mockito.verifyNoInteractions(relay);
    }

    @Test
    void failedSubmissionKeepsOriginalFailureAndRejectsOtherUsers() {
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        InvestmentReportVersionService reports = mock(InvestmentReportVersionService.class);
        TraceEventStore store = mock(TraceEventStore.class);
        TraceEventRelay relay = mock(TraceEventRelay.class);
        var observations = new ResearchTaskObservationService(repository, reports, store, relay, new ObjectMapper());
        ResearchTask failed = task(7L, ResearchTask.Status.FAILED);
        failed.setErrorMessage("original failure");
        when(repository.findByIdAndUserId(7L, "u1")).thenReturn(Optional.of(failed));
        when(store.replayRange("trace-7", null)).thenReturn(List.of());

        assertThat(observations.observeChat(failed, "u1").collectList().block(Duration.ofSeconds(3)))
                .hasSize(2).last().asString().contains("original failure", "task-final");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> observations.observeChat(failed, "stranger"))
                .isInstanceOf(com.stocksage.exception.ResourceNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> observations.terminalContent(failed, "stranger"))
                .isInstanceOf(com.stocksage.exception.ResourceNotFoundException.class);
        org.mockito.Mockito.verifyNoInteractions(reports, relay);
    }

    @Test
    void observationHeartbeatsUseOneEventSubscriptionAndStopOnCompletionOrCancellation() {
        for (boolean complete : List.of(true, false)) {
            var observations = org.mockito.Mockito.spy(new ResearchTaskObservationService(
                    mock(ResearchTaskRepository.class), mock(InvestmentReportVersionService.class),
                    mock(TraceEventStore.class), mock(TraceEventRelay.class), new ObjectMapper()));
            var scheduler = mock(reactor.core.scheduler.Scheduler.class);
            var worker = mock(reactor.core.scheduler.Scheduler.Worker.class);
            when(scheduler.createWorker()).thenReturn(worker);
            var tick = new java.util.concurrent.atomic.AtomicReference<Runnable>();
            when(worker.schedulePeriodically(org.mockito.ArgumentMatchers.any(Runnable.class),
                    org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(20).toNanos()),
                    org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(20).toNanos()),
                    org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.NANOSECONDS)))
                    .thenAnswer(call -> {
                        tick.set(call.getArgument(0));
                        return reactor.core.Disposables.single();
                    });
            var source = reactor.core.publisher.Sinks.many().unicast()
                    .<ServerSentEvent<String>>onBackpressureBuffer();
            var subscriptions = new java.util.concurrent.atomic.AtomicInteger();
            var cancellations = new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doReturn(source.asFlux().doOnSubscribe(ignored -> subscriptions.incrementAndGet())
                    .doOnCancel(cancellations::incrementAndGet))
                    .when(observations).events(7L, "u1", null);
            var chunks = new java.util.ArrayList<String>();
            var finished = new java.util.concurrent.atomic.AtomicBoolean();
            try (var schedulers = org.mockito.Mockito.mockStatic(reactor.core.scheduler.Schedulers.class)) {
                schedulers.when(reactor.core.scheduler.Schedulers::parallel).thenReturn(scheduler);
                var subscription = observations.observeChat(task(7L, ResearchTask.Status.RUNNING), "u1")
                        .subscribe(chunks::add, error -> { throw new AssertionError(error); }, () -> finished.set(true));
                tick.get().run(); // Advance the captured periodic clock to 20s.
                tick.get().run(); // 40s: no worker event is required to keep the browser connected.
                assertThat(chunks.stream().filter(chunk -> chunk.contains("\"type\":\"heartbeat\""))).hasSize(2);
                assertThat(subscriptions.get()).isEqualTo(1);
                if (complete) {
                    source.tryEmitNext(ServerSentEvent.builder("{\"type\":\"task-final\",\"content\":\"original report\"}").build());
                    source.tryEmitComplete();
                    assertThat(finished.get()).isTrue();
                } else {
                    subscription.dispose();
                    assertThat(cancellations.get()).isEqualTo(1);
                    assertThat(finished.get()).isFalse();
                }
                org.mockito.Mockito.verify(worker).dispose();
            }
        }
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
