package com.stocksage.controller;

import com.stocksage.agent.CoordinatorRegressionService;
import com.stocksage.identity.RequestIdentity;
import com.stocksage.model.dto.ChatRequest;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.conversation.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 对话控制器 —— 整个系统的核心入口。
 *
 * 采用 SSE（服务端发送事件）协议实现流式输出：
 * - 前端发送 POST 请求，后端持续推送 text/event-stream 数据
 * - 每个推送的数据块是一个 JSON：{ "type": "thought|action|observation|answer", "content": "..." }
 * - 前端实时展示后端记录的计划、动作和观察，不暴露模型隐藏思维链
 *
 * 为什么用 SSE 而不是 WebSocket？
 * - SSE 是单向推送（服务端→客户端），对话场景足够用
 * - 基于标准 HTTP，不需要额外的握手协议，Nginx/CDN 天然支持
 * - Spring WebFlux 的 Flux 可以直接映射为 SSE 流
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /** 对话编排、记忆、预取、模型流和消息持久化的主服务。 */
    private final ChatService chatService;

    /** 仅供可选连通性探针使用的默认 ChatClient。 */
    private final ChatClient chatClient;

    /** 执行不调用模型的固定 Coordinator 回归用例。 */
    private final CoordinatorRegressionService coordinatorRegressionService;

    /** 从登录 Session 提取用户 ID，覆盖请求体中任何不可信值。 */
    private final RequestIdentity requestIdentity;

    /** 是否开放直接调用模型的开发连通性探针。 */
    @Value("${stocksage.debug.chat-test-enabled:false}")
    private boolean chatTestEnabled;

    /**
     * 流式对话接口。
     *
     * 请求示例：{ "conversationId": 1, "message": "分析一下英伟达" }
     * 响应：SSE 流，每行格式为 data:{"type":"answer","content":"...","traceId":"xxx"}
     *
     * produces = TEXT_EVENT_STREAM_VALUE 告诉 Spring 以 SSE 格式输出 Flux 中的每个元素。
     *
     * @param request 会话 ID、用户消息、重试标记和可选图片
     * @return 持续发送元数据、进度、观察和答案块的 SSE Flux
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request) {
        // 服务端身份覆盖客户端字段，ChatService 后续所有读写都以当前 Session 用户为准。
        request.setUserId(requestIdentity.currentUserId());
        return chatService.streamChat(request);
    }

    /**
     * 查询用户的历史会话列表，按最近更新时间倒序返回。
     *
     * @return 当前用户可见的会话摘要
     */
    @GetMapping("/conversations")
    public List<Conversation> listConversations() {
        return chatService.listConversations(requestIdentity.currentUserId());
    }

    /**
     * 查询单个会话的完整历史消息。
     *
     * @param conversationId 当前用户拥有的会话 ID
     * @return 按时间排序的会话消息
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<Message> listMessages(@PathVariable Long conversationId) {
        return chatService.listMessages(requestIdentity.currentUserId(), conversationId);
    }

    /**
     * 删除用户的某个会话及其全部消息。
     *
     * @param conversationId 当前用户拥有的会话 ID
     * @return 无响应体的 204 结果
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable Long conversationId) {
        chatService.deleteConversation(requestIdentity.currentUserId(), conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Coordinator 路由的固定回归检查。
     *
     * <p>该接口不调用模型，适合快速确认路由规则没有被改坏。</p>
     *
     * @return 各固定用例的预期与实际路由
     */
    @PostMapping({"/regression/coordinator", "/regression/react"})
    public Map<String, Object> runCoordinatorRegression() {
        return coordinatorRegressionService.runDefaultRegression();
    }

    /**
     * DashScope 连通性测试接口（阶段 1.1）。
     * 用于验证 API 密钥配置和网络连通性，开发调试用。
     * 成功返回 LLM 回复文本；失败返回错误信息。
     *
     * @return 模型的一句测试回复
     */
    @GetMapping("/test")
    public String testConnection() {
        if (!chatTestEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return chatClient.prompt()
                .user("你好，请用一句话介绍自己。")
                .call()
                .content();
    }
}
