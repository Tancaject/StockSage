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

    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;

    /**
     * 查看某个会话当前会注入模型的短期记忆快照。
     */
    @GetMapping("/conversations/{conversationId}/context")
    public MemoryContextDTO getConversationContext(@PathVariable Long conversationId) {
        return shortTermMemory.getContextSnapshot(conversationId);
    }

    /**
     * 手动向短期记忆追加一条消息。
     *
     * <p>该接口用于调试记忆拼装，不会触发模型生成回答。</p>
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
     */
    @PostMapping("/conversations/{conversationId}/compress")
    public MemoryCompressionResult compressConversationContext(@PathVariable Long conversationId) {
        return shortTermMemory.compressIfNeeded(conversationId);
    }

    /**
     * 清空某个会话的短期记忆缓存。
     */
    @DeleteMapping("/conversations/{conversationId}/context")
    public Map<String, Object> clearConversationContext(@PathVariable Long conversationId) {
        shortTermMemory.clear(conversationId);
        return Map.of("status", "ok", "conversationId", conversationId);
    }

    /**
     * 读取用户长期画像。
     */
    @GetMapping("/users/{userId}/profile")
    public UserProfileDTO getLongTermProfile(@PathVariable String userId) {
        return longTermMemory.loadUserProfile(userId);
    }

    /**
     * 合并更新用户长期画像。
     *
     * <p>合并逻辑在 LongTermMemory 中完成，控制器只负责参数绑定和返回结果。</p>
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
     */
    @GetMapping("/users/{userId}/prompt-context")
    public Map<String, Object> getPromptContext(@PathVariable String userId) {
        return Map.of(
                "userId", userId,
                "context", longTermMemory.buildPromptContext(userId)
        );
    }
}
