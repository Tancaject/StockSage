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

import { BASE_URL, apiFetch, getJson, requestOk } from './http.js'

const STREAM_IDLE_TIMEOUT_MS = 90_000

/**
 * 发送对话消息，接收 SSE 流式回复。
 *
 * @param {Object} request       请求体：{ conversationId, message }
 * @param {Function} onChunk     每收到一个 SSE 数据块时的回调：(chunk: { type, content, modelTier, modelName, traceId }) => void
 * @param {Function} onDone      流结束时的回调
 * @param {Function} onError     出错时的回调
 * @returns {AbortController}    返回控制器，调用 .abort() 可中断请求
 */
export function streamChat(request, { onChunk, onDone, onError }) {
  // AbortController 允许调用方随时取消请求（如用户切换对话）
  const controller = new AbortController()
  let idleTimer = null
  let timedOut = false

  function clearIdleTimer() {
    if (idleTimer !== null) {
      window.clearTimeout(idleTimer)
      idleTimer = null
    }
  }

  function resetIdleTimer() {
    clearIdleTimer()
    idleTimer = window.setTimeout(() => {
      timedOut = true
      controller.abort()
    }, STREAM_IDLE_TIMEOUT_MS)
  }

  apiFetch(`${BASE_URL}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        let message = `HTTP ${response.status}`
        try {
          const body = await response.json()
          if (body?.message) message = body.message
        } catch { /* 非 JSON 错误响应 */ }
        throw new Error(message)
      }

      // 通过 ReadableStream 逐块读取响应体
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''  // 缓冲区，处理跨数据块的不完整行
      resetIdleTimer()

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        resetIdleTimer()

        // 将二进制数据解码为文本，stream: true 表示可能有后续数据
        buffer += decoder.decode(value, { stream: true })

        // SSE 协议以换行符分隔每条消息
        const lines = buffer.split('\n')
        buffer = lines.pop()  // 最后一行可能不完整，留在缓冲区

        for (const line of lines) {
          // SSE 数据行以 "data:" 开头
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (data === '[DONE]') continue  // 流结束标记
            try {
              onChunk(JSON.parse(data))  // 解析 JSON 并回调
            } catch (e) {
              // 非 JSON 数据（如心跳），忽略
            }
          }
        }
      }
      clearIdleTimer()
      onDone?.()
    })
    .catch((err) => {
      clearIdleTimer()
      if (timedOut) {
        onError?.(new Error('流式响应长时间没有新数据，已自动中断。'))
        return
      }
      // AbortError 是主动取消，不算错误
      if (err.name !== 'AbortError') onError?.(err)
    })

  return controller
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
