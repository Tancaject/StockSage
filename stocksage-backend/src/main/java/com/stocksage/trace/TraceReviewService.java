package com.stocksage.trace;

import com.stocksage.agent.runtime.ModelInvocationStore;
import com.stocksage.model.entity.AgentTrace;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.AgentTraceRepository;
import com.stocksage.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TraceReviewService {
    private final AgentTraceRepository traces;
    private final MessageRepository messages;
    private final ModelInvocationStore invocations;

    public record Row(AgentTrace trace, OffsetDateTime createdAt, String answer, Long messageId,
                      List<ModelInvocationStore.Invocation> modelInvocations) {}
    public record Page(String schema, OffsetDateTime from, OffsetDateTime to, String storageTimeZone,
                       int page, int size, boolean hasNext, List<Row> rows) {}

    @Transactional(readOnly = true)
    public Page export(String fromText, String toText, int page, int size) {
        OffsetDateTime from;
        OffsetDateTime to;
        try {
            from = OffsetDateTime.parse(fromText);
            to = OffsetDateTime.parse(toText);
        } catch (DateTimeParseException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Trace export requires from/to with UTC offset, for example 2026-10-21T00:00:00+08:00");
        }
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || page < 0 || size < 1 || size > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Trace export requires from < to, at most 31 days, page >= 0 and size between 1 and 200");
        }
        // Trace 的历史时间列没有时区；按写入时使用的 JVM 时区解释，并在导出时附上 offset。
        ZoneId zone = ZoneId.systemDefault();
        var slice = traces.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscTraceIdAsc(
                from.atZoneSameInstant(zone).toLocalDateTime(), to.atZoneSameInstant(zone).toLocalDateTime(),
                PageRequest.of(page, size));
        List<String> ids = slice.getContent().stream().map(AgentTrace::getTraceId).toList();
        Map<String, Message> answers = new HashMap<>();
        if (!ids.isEmpty()) {
            messages.findByTraceIdInAndRoleOrderByIdDesc(ids, "assistant")
                    .forEach(message -> answers.putIfAbsent(message.getTraceId(), message));
        }
        var calls = invocations.forTraces(ids).stream().collect(Collectors.groupingBy(ModelInvocationStore.Invocation::traceId));
        List<Row> rows = slice.getContent().stream().map(trace -> {
            Message answer = answers.get(trace.getTraceId());
            return new Row(trace, trace.getCreatedAt().atZone(zone).toOffsetDateTime(),
                    answer == null ? null : answer.getContent(), answer == null ? null : answer.getId(),
                    calls.getOrDefault(trace.getTraceId(), List.of()));
        }).toList();
        return new Page("online_trace_page_v1", from, to, zone.getId(), page, size, slice.hasNext(), rows);
    }
}
