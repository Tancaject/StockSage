package com.stocksage.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 诊断型记忆接口使用的手动追加消息请求。
 *
 * <p>该请求只用于调试短期记忆写入，不走完整聊天生成流程。
 * 调用方需要明确写入的角色和内容，以便复现 Redis 记忆上下文的拼装结果。</p>
 */
@Data
public class MemoryMessageRequest {

    /** 消息角色，例如 user、assistant 或 system。 */
    @NotBlank
    private String role;

    /** 需要写入短期记忆的消息正文。 */
    @NotBlank
    private String content;
}
