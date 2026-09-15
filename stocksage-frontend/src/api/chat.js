/**
 * 对话 API —— 处理与后端的 SSE 流式通信。
 *
 * SSE（服务端发送事件）是一种基于 HTTP 的单向推送协议：
 * - 前端发送一个普通的 POST 请求
 * - 后端返回 Content-Type: text/event-stream，持续推送数据
 * - 每条数据格式为 "data: {...JSON...}\n\n"
 * - 前端通过 ReadableStream 逐块读取，实现"打字机效果"
 *
 * 为什么不用 EventSource？
 * - EventSource 只支持 GET 请求，我们需要 POST 发送请求体
 * - 所以用 fetch + ReadableStream 手动解析 SSE 协议
 */

import { BASE_URL, getJson, requestOk } from './http.js'
import { openSseStream } from '../lib/sse-stream.js'

/**
 * 发送对话消息，接收 SSE 流式回复。
 *
 * @param {Object} request       请求体：{ conversationId, message }
 * @param {Function} onChunk     每收到一个 SSE 数据块时的回调：(chunk: { type, content, modelTier, modelName, traceId, entryId }) => void
 * @param {Function} onDone      流结束时的回调
 * @param {Function} onError     出错时的回调
 * @returns {AbortController}    返回控制器，调用 .abort() 可中断请求
 */
export function streamChat(request, callbacks) {
  return openSseStream(`${BASE_URL}/chat/stream`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream', 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  }, {
    ...callbacks,
    timeoutMessage: '流式响应长时间没有新数据，已自动中断。',
  })
}

/**
 * 获取某个用户已持久化的会话列表。
 */
export async function listConversations() {
  return getJson(`${BASE_URL}/chat/conversations`)
}

/**
 * 获取单个会话已持久化的消息。
 */
export async function getConversationMessages(conversationId) {
  return getJson(`${BASE_URL}/chat/conversations/${conversationId}/messages`)
}

/**
 * 删除会话及其持久化消息。
 */
export async function deleteConversation(conversationId) {
  await requestOk(`${BASE_URL}/chat/conversations/${conversationId}`, {
    method: 'DELETE',
  })
}

/**
 * 获取可观测性视图所需的链路详情。
 */
export async function getTrace(traceId) {
  return getJson(`${BASE_URL}/trace/${traceId}`)
}

/**
 * 获取用户画像。
 */
export async function getUserProfile() {
  return getJson(`${BASE_URL}/user/me/profile`)
}

export async function addToWatchList(ticker) {
  await requestOk(`${BASE_URL}/user/me/profile/watchlist/${encodeURIComponent(ticker)}`, { method: 'POST' })
}

export async function removeFromWatchList(ticker) {
  await requestOk(`${BASE_URL}/user/me/profile/watchlist/${encodeURIComponent(ticker)}`, { method: 'DELETE' })
}
