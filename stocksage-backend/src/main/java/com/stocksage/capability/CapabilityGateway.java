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

/**
 * 所有 Skill 能力调用的统一执行网关。
 *
 * <p>上游 {@code SkillExecutionService} 只提交能力 ID 和参数；本类依次完成注册表解析、策略授权、
 * 异步限时执行、UTF-8 结果限长以及 Trace/指标记录，再把本地工具或 MCP 的差异隐藏在适配器后。
 * 任何未知、越权或超时调用都会以 {@link CapabilityException} 失败关闭。</p>
 */
@Component
@RequiredArgsConstructor
public class CapabilityGateway {

    /** 将稳定能力 ID 解析为本地策略描述和执行适配器。 */
    private final CapabilityRegistry registry;
    /** 在真正访问提供方前执行风险、allowlist 和截止时间校验。 */
    private final CapabilityPolicy policy;
    /** 统一记录本地与 MCP 调用的实时事件、持久化步骤和指标。 */
    private final CapabilityInvocationObserver observer;
    /** 承载可能阻塞的提供方调用，使调用方线程可以施加硬超时。 */
    private final AsyncTaskExecutor agentTaskExecutor;

    /**
     * 执行一项已注册能力，并返回限长后的统一结果。
     *
     * @param capabilityId Skill 清单中的稳定能力 ID
     * @param arguments 能力参数；{@code null} 按空参数处理
     * @param context 用户、Skill allowlist、Trace 和总截止时间
     * @return 统一结果；内容过长时状态为 {@link CapabilityResult.Status#TRUNCATED}
     * @throws CapabilityException 未授权、不可用、超时或提供方失败
     */
    public CapabilityResult invoke(String capabilityId,
                                   Map<String, Object> arguments,
                                   CapabilityInvocationContext context) {
        // 先解析本地清单并授权；远端 MCP 元数据永远不能绕过这两道服务器侧边界。
        CapabilityRegistry.RegisteredCapability registered = registry.require(capabilityId);
        CapabilityDescriptor descriptor = registered.descriptor();
        policy.authorize(descriptor, context);

        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        observer.started(descriptor, context);
        long startNanos = System.nanoTime();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            // 异步线程没有原请求的 ThreadLocal，因此显式补齐 Tool AOP 所需的最小链路上下文。
            ToolCallContext.set(
                    context.traceId(),
                    context.conversationId(),
                    String.valueOf(safeArguments.getOrDefault("query", ""))
            );
            try {
                return ToolCallContext.withoutObservation(() -> {
                    // Gateway Observer 已统一记录本次能力，抑制本地 @Tool AOP 的重复 observation。
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
            // 在结果进入 Agent 上下文前按 UTF-8 字节限长，防止远端返回无限膨胀。
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

    /** 将任意适配器异常归一化为稳定的能力失败类型。 */
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

    /** 取能力自身超时与 Skill 总截止时间中更早者。 */
    private long effectiveTimeoutMs(CapabilityDescriptor descriptor, CapabilityInvocationContext context) {
        long remainingMs = Math.max(1, Duration.between(Instant.now(), context.deadline()).toMillis());
        return Math.max(1, Math.min(descriptor.timeoutMs(), remainingMs));
    }

    /** 按 UTF-8 字节数截断提供方结果，并保留是否截断的状态。 */
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

    /** Gateway 内部使用的限长结果，不向业务层暴露。 */
    private record LimitedContent(String content, int bytes, boolean truncated) {
    }
}
