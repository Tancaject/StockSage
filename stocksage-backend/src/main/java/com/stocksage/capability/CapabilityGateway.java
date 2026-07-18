package com.stocksage.capability;

import com.stocksage.tool.ToolCallContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Policy-enforced, timed and observed entry point for every Skill capability call. */
@Component
@RequiredArgsConstructor
public class CapabilityGateway {

    private final CapabilityRegistry registry;
    private final CapabilityPolicy policy;
    private final CapabilityInvocationObserver observer;
    private final AsyncTaskExecutor agentTaskExecutor;

    public CapabilityResult invoke(String capabilityId,
                                   Map<String, Object> arguments,
                                   CapabilityInvocationContext context) {
        CapabilityRegistry.RegisteredCapability registered = registry.require(capabilityId);
        CapabilityDescriptor descriptor = registered.descriptor();
        policy.authorize(descriptor, context);

        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        observer.started(descriptor, context);
        long startNanos = System.nanoTime();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            ToolCallContext.set(
                    context.traceId(),
                    context.conversationId(),
                    String.valueOf(safeArguments.getOrDefault("query", ""))
            );
            try {
                return ToolCallContext.withoutObservation(() -> {
                    if (!registered.adapter().isAvailable()) {
                        throw new CapabilityException(CapabilityException.Reason.UNAVAILABLE,
                                "Capability provider is unavailable: " + capabilityId);
                    }
                    return invokeAdapter(registered.adapter(), safeArguments, context);
                });
            } finally {
                ToolCallContext.clear();
            }
        }, agentTaskExecutor);

        try {
            long timeoutMs = effectiveTimeoutMs(descriptor, context);
            String raw = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long durationMs = elapsedMs(startNanos);
            LimitedContent limited = limit(raw, descriptor.maxResultBytes());
            CapabilityResult result = new CapabilityResult(
                    descriptor.id(),
                    descriptor.providerId(),
                    limited.truncated() ? CapabilityResult.Status.TRUNCATED : CapabilityResult.Status.SUCCESS,
                    limited.content(),
                    limited.bytes(),
                    durationMs
            );
            observer.completed(descriptor, context, safeArguments, result);
            return result;
        } catch (TimeoutException error) {
            future.cancel(true);
            CapabilityException capabilityError = new CapabilityException(
                    CapabilityException.Reason.TIMEOUT,
                    "Capability timed out: " + capabilityId,
                    error
            );
            observer.failed(descriptor, context, safeArguments, capabilityError, elapsedMs(startNanos));
            throw capabilityError;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            CapabilityException capabilityError = new CapabilityException(
                    CapabilityException.Reason.FAILED,
                    "Capability invocation interrupted: " + capabilityId,
                    error
            );
            observer.failed(descriptor, context, safeArguments, capabilityError, elapsedMs(startNanos));
            throw capabilityError;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            CapabilityException capabilityError = cause instanceof CapabilityException existing
                    ? existing
                    : new CapabilityException(CapabilityException.Reason.FAILED,
                    "Capability invocation failed: " + capabilityId + ": " + safeMessage(cause), cause);
            observer.failed(descriptor, context, safeArguments, capabilityError, elapsedMs(startNanos));
            throw capabilityError;
        }
    }

    private String invokeAdapter(CapabilityAdapter adapter,
                                 Map<String, Object> arguments,
                                 CapabilityInvocationContext context) {
        try {
            return adapter.invoke(arguments, context);
        } catch (CapabilityException error) {
            throw error;
        } catch (Exception error) {
            throw new CapabilityException(CapabilityException.Reason.FAILED,
                    "Provider call failed: " + safeMessage(error), error);
        }
    }

    private long effectiveTimeoutMs(CapabilityDescriptor descriptor, CapabilityInvocationContext context) {
        long remainingMs = Math.max(1, Duration.between(Instant.now(), context.deadline()).toMillis());
        return Math.max(1, Math.min(descriptor.timeoutMs(), remainingMs));
    }

    private LimitedContent limit(String raw, int maxBytes) {
        String content = raw == null ? "" : raw;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new LimitedContent(content, bytes.length, false);
        }
        byte[] limited = Arrays.copyOf(bytes, maxBytes);
        return new LimitedContent(new String(limited, StandardCharsets.UTF_8), maxBytes, true);
    }

    private long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "unknown error" : error.getMessage();
        if (message == null || message.isBlank()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }

    private record LimitedContent(String content, int bytes, boolean truncated) {
    }
}
