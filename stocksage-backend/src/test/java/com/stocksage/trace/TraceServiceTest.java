package com.stocksage.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.AgentTrace;
import com.stocksage.repository.AgentTraceRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceServiceTest {

    @Test
    void successfulEndTraceIncludesTimeSincePersistedTraceStart() {
        AgentTraceRepository repository = mock(AgentTraceRepository.class);
        PhoenixTraceService phoenix = mock(PhoenixTraceService.class);
        AgentTrace trace = new AgentTrace();
        trace.setTraceId("trace-1");
        trace.setCreatedAt(LocalDateTime.now().minusSeconds(2));
        trace.setSteps("[]");
        when(repository.findById("trace-1")).thenReturn(Optional.of(trace));
        TraceService service = new TraceService(repository, new ObjectMapper(), phoenix);

        service.endTrace("trace-1", "success", 12, 100, "COMPLETED");

        assertThat(trace.getDurationMs()).isGreaterThanOrEqualTo(1_000);
        assertThat(trace.getTaskOutcome()).isEqualTo("COMPLETED");
    }
}
