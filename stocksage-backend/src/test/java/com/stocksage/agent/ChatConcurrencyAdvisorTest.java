package com.stocksage.agent;

import com.stocksage.exception.ResearchCapacityExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatConcurrencyAdvisorTest {
    @Test
    void capacityIsSharedAndReleasedOnEveryTerminalPathIncludingSynchronousConstructionFailure() {
        assertThatThrownBy(() -> new ChatConcurrencyAdvisor(0)).isInstanceOf(IllegalArgumentException.class);
        var advisor = new ChatConcurrencyAdvisor(1);
        var request = mock(ChatClientRequest.class);
        var sync = mock(CallAdvisorChain.class);
        var stream = mock(StreamAdvisorChain.class);
        when(stream.nextStream(request)).thenReturn(Flux.never());
        var active = advisor.adviseStream(request, stream).subscribe();
        assertThatThrownBy(() -> advisor.adviseCall(request, sync)).isInstanceOf(ResearchCapacityExceededException.class);
        verifyNoInteractions(sync);
        active.dispose();
        advisor.adviseCall(request, sync);
        when(sync.nextCall(request)).thenThrow(new IllegalStateException("sync provider failure"));
        assertThatThrownBy(() -> advisor.adviseCall(request, sync)).hasMessage("sync provider failure");
        when(stream.nextStream(request)).thenThrow(new IllegalStateException("stream construction failure"));
        assertThatThrownBy(() -> advisor.adviseStream(request, stream).blockLast()).hasMessage("stream construction failure");
        doReturn(Flux.error(new IllegalStateException("stream failure"))).when(stream).nextStream(request);
        assertThatThrownBy(() -> advisor.adviseStream(request, stream).blockLast()).hasMessage("stream failure");
        when(stream.nextStream(request)).thenReturn(Flux.empty());
        advisor.adviseStream(request, stream).blockLast();
        var next = advisor.adviseStream(request, stream).blockLast();
        assertThat(next).isNull();
    }
}
