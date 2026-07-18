package com.stocksage.tool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 当前对话流的工具调用上下文。
 *
 * ThreadLocal 负责普通的同步工具调用路径。活跃上下文注册表为演示应用提供受限兜底：
 * 当工具调用发生在工作线程上，且当前只有一条活跃对话流时，AOP 层仍能找到上下文。
 */
public final class ToolCallContext {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OBSERVATION_SUPPRESSED =
            ThreadLocal.withInitial(() -> false);
    private static final ConcurrentMap<String, Context> ACTIVE_CONTEXTS = new ConcurrentHashMap<>();

    /** 工具类不允许实例化。 */
    private ToolCallContext() {}

    /**
     * 注册当前对话流的工具上下文。
     *
     * <p>注册会同时写入 ThreadLocal 和活跃上下文表，方便同步调用和少量工作线程兜底读取。</p>
     */
    public static void register(String traceId, Long conversationId) {
        register(traceId, conversationId, null);
    }

    /**
     * 注册带用户原始问题的工具上下文。
     *
     * @param traceId 当前追踪 ID
     * @param conversationId 当前会话 ID
     * @param userQuery 当前用户问题，用于工具侧补充上下文
     */
    public static void register(String traceId, Long conversationId, String userQuery) {
        Context context = new Context(traceId, conversationId, userQuery);
        ACTIVE_CONTEXTS.put(traceId, context);
        CURRENT.set(context);
    }

    /**
     * 仅设置当前线程上下文，不加入活跃注册表。
     */
    public static void set(String traceId, Long conversationId) {
        set(traceId, conversationId, null);
    }

    /**
     * 仅设置当前线程上下文并携带用户问题。
     *
     * <p>该方法适合短生命周期同步调用；跨线程场景优先使用 register/unregister 成对管理。</p>
     */
    public static void set(String traceId, Long conversationId, String userQuery) {
        CURRENT.set(new Context(traceId, conversationId, userQuery));
    }

    /**
     * 获取当前工具调用所属追踪 ID。
     */
    public static String getTraceId() {
        Context context = currentContext();
        return context == null ? null : context.traceId();
    }

    /**
     * 获取当前工具调用所属会话 ID。
     */
    public static Long getConversationId() {
        Context context = currentContext();
        return context == null ? null : context.conversationId();
    }

    /**
     * 获取当前用户原始问题。
     */
    public static String getUserQuery() {
        Context context = currentContext();
        return context == null ? null : context.userQuery();
    }

    /**
     * 清理当前线程上下文，避免线程复用时串到下一次请求。
     */
    public static void clear() {
        CURRENT.remove();
        OBSERVATION_SUPPRESSED.remove();
    }

    /**
     * Execute a local {@code @Tool} through CapabilityGateway without emitting a second AOP trace.
     */
    public static <T> T withoutObservation(Supplier<T> action) {
        boolean previous = OBSERVATION_SUPPRESSED.get();
        OBSERVATION_SUPPRESSED.set(true);
        try {
            return action.get();
        } finally {
            if (previous) {
                OBSERVATION_SUPPRESSED.set(true);
            } else {
                OBSERVATION_SUPPRESSED.remove();
            }
        }
    }

    public static boolean isObservationSuppressed() {
        return OBSERVATION_SUPPRESSED.get();
    }

    /**
     * 注销指定追踪 ID 的活跃上下文。
     *
     * <p>对话流结束时调用，防止活跃上下文表长期持有已完成请求。</p>
     */
    public static void unregister(String traceId) {
        ACTIVE_CONTEXTS.remove(traceId);
        Context context = CURRENT.get();
        if (context != null && context.traceId().equals(traceId)) {
            CURRENT.remove();
        }
    }

    /**
     * 解析当前可用上下文。
     *
     * <p>优先使用 ThreadLocal；如果当前只有一条活跃对话流，则允许作为兜底返回，
     * 让工具调用发生在线程切换后仍能被追踪到。</p>
     */
    private static Context currentContext() {
        Context context = CURRENT.get();
        if (context != null) {
            return context;
        }
        if (ACTIVE_CONTEXTS.size() == 1) {
            return ACTIVE_CONTEXTS.values().iterator().next();
        }
        return null;
    }

    /**
     * 工具调用追踪所需的最小上下文。
     */
    private record Context(String traceId, Long conversationId, String userQuery) {}
}
