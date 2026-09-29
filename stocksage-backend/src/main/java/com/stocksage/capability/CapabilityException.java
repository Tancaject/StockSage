package com.stocksage.capability;

/**
 * 能力授权、发现或执行阶段的统一失败异常。
 *
 * <p>{@link CapabilityGateway} 用 {@link Reason} 区分可降级故障与必须立即拒绝的策略错误；
 * 上游 Skill 据此决定走本地 fallback 还是终止执行。异常默认失败关闭，不会把未知能力当作可用。</p>
 */
public class CapabilityException extends RuntimeException {

    /** 稳定的失败类别，避免上游解析异常文本。 */
    private final Reason reason;

    /**
     * 创建不带底层原因的能力异常。
     *
     * @param reason 稳定失败类别
     * @param message 面向日志的简短说明
     */
    public CapabilityException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * 创建并保留底层异常的能力异常。
     *
     * @param reason 稳定失败类别
     * @param message 面向日志的简短说明
     * @param cause 提供方或协议异常
     */
    public CapabilityException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** @return 供 Skill 降级分支判断的失败类别 */
    public Reason reason() {
        return reason;
    }

    /** 能力调用对外暴露的有限失败分类。 */
    public enum Reason {
        UNKNOWN,
        DENIED,
        UNAVAILABLE,
        TIMEOUT,
        CAPACITY_EXCEEDED,
        FAILED
    }
}
