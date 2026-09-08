import { apiFetch, responseMessage } from '../api/http.js'

const STREAM_IDLE_TIMEOUT_MS = 90_000

/** 共用聊天与任务 SSE 的取消、空闲超时和事件分发；续传参数由调用方提供。 */
export function openSseStream(url, options, { onChunk, onDone, onError, timeoutMessage }) {
  const controller = new AbortController()
  let idleTimer = null
  let timedOut = false

  function clearIdleTimer() {
    clearTimeout(idleTimer)
    idleTimer = null
  }

  function resetIdleTimer() {
    clearIdleTimer()
    idleTimer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, STREAM_IDLE_TIMEOUT_MS)
  }

  apiFetch(url, { ...options, signal: controller.signal })
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
            // 心跳和非 JSON 诊断不作为业务分片。
          }
        },
      })
      clearIdleTimer()
      onDone?.()
    })
    .catch(error => {
      clearIdleTimer()
      if (timedOut) onError?.(new Error(timeoutMessage))
      else if (error.name !== 'AbortError') onError?.(error)
    })

  return controller
}

/**
 * Read an SSE response body while preserving event ids across arbitrary byte chunks.
 */
export async function readSseEvents(body, { onEvent, onActivity } = {}) {
  if (!body?.getReader) throw new Error('SSE response body is unavailable')

  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventId = ''
  let dataLines = []

  function dispatch() {
    if (dataLines.length === 0) {
      eventId = ''
      return
    }
    onEvent?.({ id: eventId, data: dataLines.join('\n') })
    eventId = ''
    dataLines = []
  }

  function consumeLine(rawLine) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line === '') {
      dispatch()
      return
    }
    if (line.startsWith(':')) return

    const separator = line.indexOf(':')
    const field = separator === -1 ? line : line.slice(0, separator)
    let value = separator === -1 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'id' && !value.includes('\0')) eventId = value
    if (field === 'data') dataLines.push(value)
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    onActivity?.()
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    lines.forEach(consumeLine)
  }

  buffer += decoder.decode()
  if (buffer) consumeLine(buffer)
  dispatch()
}
