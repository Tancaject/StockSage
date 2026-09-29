/**
 * 聊天编排与会话边界。消息 SQL、Prompt 组装和请求级 SSE 各自独立；
 * 研究结果只通过 ConversationMessageService 发布消息，不访问聊天传输内部状态。
 */
package com.stocksage.conversation;
