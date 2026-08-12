package com.stocksage.capability;

import java.util.Map;

/**
 * 把不同能力提供方统一成 StockSage 可执行的本地接口。
 *
 * <p>{@link CapabilityRegistry} 在启动时把适配器与仓库内的能力清单绑定，
 * {@link CapabilityGateway} 再通过本接口调用本地工具或 MCP 工具。适配器只负责协议转换，
 * 不负责授权、超时和结果限长，这些边界统一由 Gateway 执行。</p>
 */
public interface CapabilityAdapter {

    /**
     * 返回与能力清单一致的稳定 ID。
     *
     * @return 供 Skill 引用的能力 ID
     */
    String capabilityId();

    /**
     * 检查当前提供方是否可调用；不得在此执行真实业务调用。
     *
     * @return 提供方已就绪时为 {@code true}
     */
    boolean isAvailable();

    /**
     * 按统一参数契约调用底层提供方。
     *
     * @param arguments Skill 传入的参数，具体字段由适配器校验和映射
     * @param context 本次调用的用户、链路、允许能力和截止时间
     * @return 提供方返回的原始文本，随后由 Gateway 限长并包装
     * @throws Exception 底层协议或提供方调用失败
     */
    String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) throws Exception;
}
