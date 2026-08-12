package com.stocksage.controller;

import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.ShortTermMemory;
import com.stocksage.model.dto.MemoryCompressionResult;
import com.stocksage.model.dto.MemoryContextDTO;
import com.stocksage.model.dto.MemoryMessageRequest;
import com.stocksage.model.dto.UserProfileDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 短期会话记忆和长期用户画像的调试、维护接口。
 *
 * <p>正常对话轮次会通过 {@code ChatService} 更新记忆；
 * 该控制器用于让界面或本地诊断直接查看、压缩或修复记忆状态，而不必生成一轮回答。</p>
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    /** Redis 中的会话窗口、摘要和压缩操作。 */
    private final ShortTermMemory shortTermMemory;

    /** MySQL 中的用户画像及提示词上下文构造。 */
    private final LongTermMemory longTermMemory;

    /**
     * 查看某个会话当前会注入模型的短期记忆快照。
     *
     * @param conversationId 待诊断的会话 ID
     * @return 摘要、近期消息和估算大小
     */
    @GetMapping("/conversations/{conversationId}/context")
    public MemoryContextDTO getConversationContext(@PathVariable Long conversationId) {
        return shortTermMemory.getContextSnapshot(conversationId);
    }

    /**
     * 手动向短期记忆追加一条消息。
     *
     * <p>该接口用于调试记忆拼装，不会触发模型生成回答。</p>
     *
     * @param conversationId 目标会话 ID
     * @param request 消息角色与文本
     * @return 追加后的短期记忆快照
     */
    @PostMapping("/conversations/{conversationId}/messages")
    public MemoryContextDTO addConversationMessage(
            @PathVariable Long conversationId,
            @Valid @RequestBody MemoryMessageRequest request
    ) {
        return shortTermMemory.addMessage(conversationId, request.getRole(), request.getContent());
    }

    /**
     * 对指定会话执行一次短期记忆压缩。
     *
     * <p>当消息数量未超过上限时，服务会返回未压缩状态。</p>
     *
     * @param conversationId 目标会话 ID
     * @return 是否压缩、压缩前后数量及摘要
     */
    @PostMapping("/conversations/{conversationId}/compress")
    public MemoryCompressionResult compressConversationContext(@PathVariable Long conversationId) {
        return shortTermMemory.compressIfNeeded(conversationId);
    }

    /**
     * 清空某个会话的短期记忆缓存。
     *
     * @param conversationId 目标会话 ID
     * @return 清理成功状态
     */
    @DeleteMapping("/conversations/{conversationId}/context")
    public Map<String, Object> clearConversationContext(@PathVariable Long conversationId) {
        shortTermMemory.clear(conversationId);
        return Map.of("status", "ok", "conversationId", conversationId);
    }

    /**
     * 读取用户长期画像。
     *
     * @param userId 目标用户 ID；该管理接口不从 Session 推断
     * @return 风险偏好、关注行业与自选股画像
     */
    @GetMapping("/users/{userId}/profile")
    public UserProfileDTO getLongTermProfile(@PathVariable String userId) {
        return longTermMemory.loadUserProfile(userId);
    }

    /**
     * 合并更新用户长期画像。
     *
     * <p>合并逻辑在 LongTermMemory 中完成，控制器只负责参数绑定和返回结果。</p>
     *
     * @param userId 目标用户 ID
     * @param profile 待合并的画像字段
     * @return 合并并持久化后的画像
     */
    @PutMapping("/users/{userId}/profile")
    public UserProfileDTO mergeLongTermProfile(
            @PathVariable String userId,
            @Valid @RequestBody UserProfileDTO profile
    ) {
        return longTermMemory.mergeUserProfile(userId, profile);
    }

    /**
     * 查看长期画像拼接成提示词上下文后的文本。
     *
     * @param userId 目标用户 ID
     * @return 用户 ID 与最终提示词片段
     */
    @GetMapping("/users/{userId}/prompt-context")
    public Map<String, Object> getPromptContext(@PathVariable String userId) {
        return Map.of(
                "userId", userId,
                "context", longTermMemory.buildPromptContext(userId)
        );
    }
}
