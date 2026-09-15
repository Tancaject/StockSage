import { BASE_URL, requestJson } from './http.js'
import { openSseStream } from '../lib/sse-stream.js'

export async function getActiveTask(conversationId) {
  if (conversationId === null || conversationId === undefined || conversationId === '') return null
  const params = new URLSearchParams({ conversationId: String(conversationId) })
  return requestJson(`${BASE_URL}/research-tasks/active?${params.toString()}`)
}

export function openTaskEvents(taskId, lastEventId, onChunk, onDone, onError) {
  const headers = { Accept: 'text/event-stream' }
  if (lastEventId) headers['Last-Event-ID'] = lastEventId
  return openSseStream(`${BASE_URL}/research-tasks/${encodeURIComponent(taskId)}/events`, {
    method: 'GET',
    headers,
  }, {
    onChunk,
    onDone,
    onError,
    timeoutMessage: '后台研究事件流长时间没有新数据，正在尝试重连。',
  })
}
