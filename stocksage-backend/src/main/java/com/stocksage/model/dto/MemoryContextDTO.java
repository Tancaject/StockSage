package com.stocksage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个会话在 Redis 中的短期记忆快照。
 *
 * <p>消息列表已经按提示词组装格式处理，诊断调用方可直接拿该 DTO
 * 与 ChatService 实际注入的内容对比。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryContextDTO {

    /** 会话 ID，用于定位 Redis 中对应的短期记忆快照。 */
    private Long conversationId;

    /** 已按提示词注入格式整理好的消息片段列表。 */
    private List<String> messages;

    /** 当前快照中实际包含的消息数量。 */
    private int messageCount;

    /** 配置允许注入模型上下文的最大消息数量。 */
    private int maxContextMessages;

    /** Redis 短期记忆的剩余或配置 TTL，单位小时。 */
    private int ttlHours;
}
