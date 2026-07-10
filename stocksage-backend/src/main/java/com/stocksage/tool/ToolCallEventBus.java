package com.stocksage.tool;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用事件总线。
 *
 * ChatService 为每次请求注册一个 Sink，AOP 拦截器往 Sink 推送工具调用事件，
 * ChatService 将事件流与答案流合并后统一推送给前端。
 */
@Component
public class ToolCallEventBus {

    private final ConcurrentHashMap<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    /** 注册一个新的事件通道，返回可订阅的 Flux */
    public Flux<String> register(String traceId) {
        Sinks.Many<String> sink = sinks.computeIfAbsent(
                traceId,
                ignored -> Sinks.many().replay().limit(512)
        );
        return sink.asFlux();
    }

    /**
     * 往指定通道推送一条事件。
     *
     * <p>unicast Sink 要求生产端串行调用。深度研究的多空辩论阶段，Bull/Bear 会在不同线程上
     * 并行流式生成、并发调用本方法，因此这里对 sink 加锁串行化，避免 {@code tryEmitNext}
     * 返回 {@code FAIL_NON_SERIALIZED} 把流式 token 静默丢弃。</p>
     */
    public void emit(String traceId, String chunk) {
        Sinks.Many<String> sink = sinks.get(traceId);
        if (sink != null) {
            synchronized (sink) {
                sink.tryEmitNext(chunk);
            }
        }
    }

    /** 关闭通道并短暂保留回放，供 Redis 故障时的重连观察者读取终态。 */
    public void complete(String traceId) {
        Sinks.Many<String> sink = sinks.get(traceId);
        if (sink != null) {
            synchronized (sink) {
                sink.tryEmitComplete();
            }
            Mono.delay(Duration.ofMinutes(5))
                    .subscribe(ignored -> sinks.remove(traceId, sink));
        }
    }
}
