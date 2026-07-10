package com.stocksage.controller;

import com.stocksage.agent.CoordinatorRegressionService;
import com.stocksage.config.RequestIdentity;
import com.stocksage.model.dto.ChatRequest;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.Message;
import com.stocksage.service.ChatService;
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
 * - 这样前端可以实时展示智能体的"思考过程"，而不是等全部生成完才返回
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

    private final ChatService chatService;
    private final ChatClient chatClient;
    private final CoordinatorRegressionService coordinatorRegressionService;
    private final RequestIdentity requestIdentity;

    @Value("${stocksage.debug.chat-test-enabled:false}")
    private boolean chatTestEnabled;

    /**
     * 流式对话接口。
     *
     * 请求示例：{ "conversationId": 1, "message": "分析一下英伟达" }
     * 响应：SSE 流，每行格式为 data:{"type":"answer","content":"...","traceId":"xxx"}
     *
     * produces = TEXT_EVENT_STREAM_VALUE 告诉 Spring 以 SSE 格式输出 Flux 中的每个元素。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest request) {
        request.setUserId(requestIdentity.currentUserId());
        return chatService.streamChat(request);
    }

    /**
     * 查询用户的历史会话列表，按最近更新时间倒序返回。
     */
    @GetMapping("/conversations")
    public List<Conversation> listConversations() {
        return chatService.listConversations(requestIdentity.currentUserId());
    }

    /**
     * 查询单个会话的完整历史消息。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<Message> listMessages(@PathVariable Long conversationId) {
        return chatService.listMessages(requestIdentity.currentUserId(), conversationId);
    }

    /**
     * 删除用户的某个会话及其全部消息。
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
     */
    @PostMapping({"/regression/coordinator", "/regression/react"})
    public Map<String, Object> runCoordinatorRegression() {
        return coordinatorRegressionService.runDefaultRegression();
    }

    /**
     * DashScope 连通性测试接口（阶段 1.1）。
     * 用于验证 API 密钥配置和网络连通性，开发调试用。
     * 成功返回 LLM 回复文本；失败返回错误信息。
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
