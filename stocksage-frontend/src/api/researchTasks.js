import { BASE_URL, apiFetch, requestJson, responseMessage } from './http.js'
import { readSseEvents } from '../lib/sse-stream.js'

const TASK_STREAM_IDLE_TIMEOUT_MS = 90_000

export async function getActiveTask(conversationId) {
  if (conversationId === null || conversationId === undefined || conversationId === '') return null
  const params = new URLSearchParams({ conversationId: String(conversationId) })
  return requestJson(`${BASE_URL}/research-tasks/active?${params.toString()}`)
}

export function openTaskEvents(taskId, lastEventId, onChunk, onDone, onError) {
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
    }, TASK_STREAM_IDLE_TIMEOUT_MS)
  }

  const headers = { Accept: 'text/event-stream' }
  if (lastEventId) headers['Last-Event-ID'] = lastEventId

  apiFetch(`${BASE_URL}/research-tasks/${encodeURIComponent(taskId)}/events`, {
    method: 'GET',
    headers,
    signal: controller.signal,
  })
    .then(async response => {
      if (!response.ok) throw new Error(await responseMessage(response))
      resetIdleTimer()
      await readSseEvents(response.body, {
        onActivity: resetIdleTimer,
        onEvent(event) {
          const data = event.data.trim()
          if (!data || data === '[DONE]') return
          try {
            const chunk = JSON.parse(data)
            onChunk?.(event.id ? { ...chunk, entryId: event.id } : chunk)
          } catch {
            // Heartbeats and non-JSON diagnostics are intentionally ignored.
          }
        },
      })
      clearIdleTimer()
      onDone?.()
    })
    .catch(error => {
      clearIdleTimer()
      if (timedOut) {
        onError?.(new Error('后台研究事件流长时间没有新数据，正在尝试重连。'))
        return
      }
      if (error.name !== 'AbortError') onError?.(error)
    })

  return controller
}
