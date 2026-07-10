package com.stocksage.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.trace.TraceEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchTaskEventsIT extends AuthIntegrationTestBase {

    @Autowired
    private ResearchTaskRepository researchTaskRepository;

    @Autowired
    private TraceEventStore traceEventStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void nonOwnerCannotDiscoverResearchTask() {
        Owner owner = createOwner("events-owner");
        Owner stranger = createOwner("events-stranger");
        ResearchTask task = saveTask(
                owner.userId(), owner.conversationId(), ResearchTask.Status.SUCCEEDED,
                ResearchTask.Stage.COMPLETE, "trace-owner-only", LocalDateTime.now());

        ResponseEntity<String> response = getEvents(task.getId(), stranger.session(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ownerReceivesReplayWithIdsAndLastEventIdResumesExclusively() {
        Owner owner = createOwner("events-replay");
        String traceId = "trace-" + UUID.randomUUID();
        ResearchTask task = saveTask(
                owner.userId(), owner.conversationId(), ResearchTask.Status.SUCCEEDED,
                ResearchTask.Stage.COMPLETE, traceId, LocalDateTime.now());

        traceEventStore.append(traceId, chunk("thought", "one"));
        traceEventStore.append(traceId, chunk("observation", "two"));
        traceEventStore.append(traceId, chunk("thought", "three"));
        traceEventStore.append(traceId, chunk("task-final", "done"));
        List<TraceEventStore.StoredEvent> stored = traceEventStore.replayRange(traceId, null);
        assertThat(stored).hasSize(4);

        ResponseEntity<String> full = getEvents(task.getId(), owner.session(), null);
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sseEvents(full.getBody()))
                .hasSize(4)
                .allMatch(event -> event.contains("id:"));

        ResponseEntity<String> resumed = getEvents(
                task.getId(), owner.session(), stored.get(1).entryId());
        List<String> resumedEvents = sseEvents(resumed.getBody());
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumedEvents).hasSize(2);
        assertThat(resumedEvents.get(0)).contains("three");
        assertThat(resumedEvents.get(1)).contains("task-final").contains("done");
    }

    @Test
    void activeReturnsLatestOwnedTaskAndNoContentWhenAbsent() {
        Owner owner = createOwner("events-active");
        LocalDateTime now = LocalDateTime.now();
        saveTask(owner.userId(), owner.conversationId(), ResearchTask.Status.PENDING,
                ResearchTask.Stage.CREATED, "trace-old", now.minusMinutes(2));
        ResearchTask latest = saveTask(owner.userId(), owner.conversationId(), ResearchTask.Status.RUNNING,
                ResearchTask.Stage.AGENT_DEBATE, "trace-new", now.minusMinutes(1));
        saveTask(owner.userId(), owner.conversationId(), ResearchTask.Status.SUCCEEDED,
                ResearchTask.Stage.COMPLETE, "trace-terminal", now);

        ResponseEntity<Map<String, Object>> active = get(
                "/api/research-tasks/active?conversationId=" + owner.conversationId(), owner.session());
        ResponseEntity<Map<String, Object>> absent = get(
                "/api/research-tasks/active?conversationId=" + (owner.conversationId() + 10_000), owner.session());

        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) active.getBody().get("taskId")).longValue()).isEqualTo(latest.getId());
        assertThat(active.getBody()).containsEntry("status", "RUNNING")
                .containsEntry("stage", "AGENT_DEBATE")
                .containsEntry("ticker", "AAPL");
        assertThat(absent.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void expiredTerminalStreamCompletesWithTaskFinalFallback() {
        Owner owner = createOwner("events-fallback");
        ResearchTask failed = saveTask(
                owner.userId(), owner.conversationId(), ResearchTask.Status.FAILED,
                ResearchTask.Stage.FAILED, "trace-expired-" + UUID.randomUUID(), LocalDateTime.now());
        failed.setErrorMessage("simulated worker failure");
        researchTaskRepository.saveAndFlush(failed);

        ResponseEntity<String> response = getEvents(failed.getId(), owner.session(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sseEvents(response.getBody())).singleElement()
                .satisfies(event -> assertThat(event)
                        .contains("task-final")
                        .contains("simulated worker failure"));
    }

    private Owner createOwner(String prefix) {
        LoginSession session = registerAndLogin(prefix + "-" + UUID.randomUUID() + "@example.com");
        ResponseEntity<Map<String, Object>> conversation = post("/api/test/conversations", Map.of(), session);
        Map<String, Object> body = conversation.getBody();
        return new Owner(
                session,
                String.valueOf(body.get("userId")),
                ((Number) body.get("id")).longValue()
        );
    }

    private ResearchTask saveTask(
            String userId,
            Long conversationId,
            ResearchTask.Status status,
            ResearchTask.Stage stage,
            String traceId,
            LocalDateTime createdAt
    ) {
        ResearchTask task = new ResearchTask();
        task.setUserId(userId);
        task.setConversationId(conversationId);
        task.setIdempotencyKey("events-it-" + UUID.randomUUID());
        task.setTicker("AAPL");
        task.setStatus(status);
        task.setStage(stage);
        task.setPayloadJson(writeJson(Map.of("traceId", traceId)));
        task.setCreatedAt(createdAt);
        return researchTaskRepository.saveAndFlush(task);
    }

    private ResponseEntity<String> getEvents(Long taskId, LoginSession session, String lastEventId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        headers.set(HttpHeaders.COOKIE, session.cookies());
        if (lastEventId != null) {
            headers.set("Last-Event-ID", lastEventId);
        }
        return rest.exchange(
                "/api/research-tasks/" + taskId + "/events",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private List<String> sseEvents(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return Arrays.stream(body.split("\\R\\s*\\R"))
                .map(String::trim)
                .filter(event -> !event.isBlank())
                .toList();
    }

    private String chunk(String type, String content) {
        return writeJson(Map.of("type", type, "content", content));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Owner(LoginSession session, String userId, Long conversationId) {
    }
}
