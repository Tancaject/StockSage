package com.stocksage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 短期会话记忆压缩后的结果。
 *
 * <p>当 Redis 中的对话消息超过可注入上限时，记忆服务会裁剪或压缩旧消息。
 * 该对象把压缩前后数量返回给诊断接口，方便判断上下文是否因为过长而被截断。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryCompressionResult {

    /** 被压缩的会话 ID。 */
    private Long conversationId;

    /** 本次调用是否真的发生了压缩；消息数量未超限时为 false。 */
    private boolean compressed;

    /** 压缩前的短期记忆消息数量。 */
    private int beforeCount;

    /** 压缩后的短期记忆消息数量。 */
    private int afterCount;

    /** 当前配置允许保留的最大上下文消息数量。 */
    private int maxContextMessages;
}
