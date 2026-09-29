package com.stocksage.research;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchTaskQueueTest {

    private static final String STREAM = "stream:research-tasks:test";
    private static final String GROUP = "research-workers-test";

    @Test
    void ackDeletesCompletedRecordAfterRemovingItFromPendingEntries() {
        QueueFixture fixture = fixture();

        fixture.queue().ack(fixture.record());

        var ordered = inOrder(fixture.streamOperations());
        ordered.verify(fixture.streamOperations())
                .acknowledge(STREAM, GROUP, fixture.recordId());
        ordered.verify(fixture.streamOperations())
                .delete(STREAM, fixture.recordId());
    }

    @Test
    void ackFailureDoesNotDeleteRecordThatMayStillNeedReclaiming() {
        QueueFixture fixture = fixture();
        when(fixture.streamOperations().acknowledge(STREAM, GROUP, fixture.recordId()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> fixture.queue().ack(fixture.record()))
                .isInstanceOf(ResearchTaskQueue.QueueUnavailableException.class)
                .hasMessageContaining("ack and delete research task queue record");

        verify(fixture.streamOperations(), never()).delete(STREAM, fixture.recordId());
    }

    @SuppressWarnings("unchecked")
    private QueueFixture fixture() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> streamOperations = mock(StreamOperations.class);
        MapRecord<String, String, String> record = mock(MapRecord.class);
        RecordId recordId = RecordId.of("1-0");
        when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        when(record.getId()).thenReturn(recordId);
        ResearchTaskQueue queue = new ResearchTaskQueue(
                redisTemplate,
                STREAM,
                GROUP,
                "stream:research-tasks-dlq:test",
                100,
                500
        );
        return new QueueFixture(queue, streamOperations, record, recordId);
    }

    private record QueueFixture(
            ResearchTaskQueue queue,
            StreamOperations<String, String, String> streamOperations,
            MapRecord<String, String, String> record,
            RecordId recordId
    ) {
    }
}
