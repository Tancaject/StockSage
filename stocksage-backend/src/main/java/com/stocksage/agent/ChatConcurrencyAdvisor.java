package com.stocksage.agent;

import com.stocksage.exception.ResearchCapacityExceededException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

import java.util.concurrent.Semaphore;

/** One process-wide chat limit shared by ordinary, research and background clients. */
public final class ChatConcurrencyAdvisor implements CallAdvisor, StreamAdvisor {
    private static final Object HTTP_STOP = new Object();
    private final Semaphore permits;

    public ChatConcurrencyAdvisor(int maxConcurrentCalls) {
        if (maxConcurrentCalls <= 0) throw new IllegalArgumentException("Chat concurrency must be positive");
        permits = new Semaphore(maxConcurrentCalls);
    }

    @Override public String getName() { return "chat-concurrency"; }
    @Override public int getOrder() { return Integer.MIN_VALUE; }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Semaphore acquired = acquire();
        try {
            return chain.nextCall(request);
        } finally {
            acquired.release();
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Sinks.Empty<Void> stop = Sinks.empty();
            return Flux.using(this::acquire,
                    ignored -> chain.nextStream(request).contextWrite(context -> context.put(HTTP_STOP, stop)),
                    acquired -> {
                        // The SDK's fused window can retain upstream before its first chunk.
                        // Stop the HTTP exchange directly before admitting another chat call.
                        try { stop.tryEmitEmpty(); }
                        finally { acquired.release(); }
                    }, true);
        });
    }

    public static ExchangeFilterFunction cancellationFilter() {
        return (request, next) -> Mono.deferContextual(context -> {
            Sinks.Empty<Void> stop = context.getOrDefault(HTTP_STOP, null);
            if (stop == null) return next.exchange(request);
            return next.exchange(request)
                    .map(response -> response.mutate()
                            .body(body -> body.takeUntilOther(stop.asMono())).build())
                    .takeUntilOther(stop.asMono());
        });
    }

    private Semaphore acquire() {
        if (!permits.tryAcquire()) throw new ResearchCapacityExceededException("chat-provider", null);
        return permits;
    }
}
