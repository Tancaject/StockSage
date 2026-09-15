import assert from 'node:assert/strict'
import test from 'node:test'

import { readSseEvents } from './sse-stream.js'
import { streamChat } from '../api/chat.js'
import { openTaskEvents } from '../api/researchTasks.js'

function responseBody(parts) {
  const encoded = parts.map(part => new TextEncoder().encode(part))
  let index = 0
  return {
    getReader() {
      return {
        async read() {
          if (index >= encoded.length) return { done: true, value: undefined }
          return { done: false, value: encoded[index++] }
        },
      }
    },
  }
}

test('readSseEvents preserves ids and data across byte chunk boundaries', async () => {
  const events = []
  await readSseEvents(responseBody([
    'id: 1720000000000-1\r\nda',
    'ta: {"type":"thought",\r\n',
    'data: "content":"done"}\r\n\r\n',
  ]), { onEvent: event => events.push(event) })

  assert.deepEqual(events, [{
    id: '1720000000000-1',
    data: '{"type":"thought",\n"content":"done"}',
  }])
})

test('readSseEvents dispatches a final event even without a trailing blank line', async () => {
  const events = []
  await readSseEvents(responseBody(['id: 2-0\ndata: {"type":"task-final"}']), {
    onEvent: event => events.push(event),
  })

  assert.deepEqual(events, [{ id: '2-0', data: '{"type":"task-final"}' }])
})

test('chat and task streams preserve request options, event cursors and completion', async t => {
  globalThis.document = { cookie: 'XSRF-TOKEN=test-token' }
  t.after(() => { delete globalThis.document })
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, options })
    return { ok: true, body: responseBody([
      ': heartbeat\n\ndata: not-json\n\nid: 9-1\ndata: {"type":"task-final"}\n\ndata: [DONE]\n\n',
    ]) }
  })
  for (const start of [
    callbacks => streamChat({ message: 'query' }, callbacks),
    callbacks => openTaskEvents('task/1', '8-0', callbacks.onChunk, callbacks.onDone, callbacks.onError),
  ]) {
    const chunks = []
    await new Promise((resolve, reject) => start({ onChunk: chunk => chunks.push(chunk), onDone: resolve, onError: reject }))
    assert.deepEqual(chunks, [{ type: 'task-final', entryId: '9-1' }])
  }
  assert.equal(requests[0].options.method, 'POST')
  assert.deepEqual(JSON.parse(requests[0].options.body), { message: 'query' })
  assert.equal(requests[0].options.headers.get('X-XSRF-TOKEN'), 'test-token')
  assert.equal(requests[1].options.method, 'GET')
  assert.equal(requests[1].url, '/api/research-tasks/task%2F1/events')
  assert.equal(requests[1].options.headers.get('Last-Event-ID'), '8-0')
})

test('stream idle timeouts keep endpoint messages while explicit aborts remain silent', async t => {
  globalThis.document = { cookie: 'XSRF-TOKEN=test-token' }
  t.after(() => { delete globalThis.document })
  t.mock.timers.enable({ apis: ['setTimeout'] })
  t.mock.method(globalThis, 'fetch', async (_url, options) => ({
    ok: true,
    body: { getReader: () => ({ read: () => new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
    }) }) },
  }))
  for (const [start, message] of [
    [callbacks => streamChat({ message: 'query' }, callbacks), '流式响应长时间没有新数据，已自动中断。'],
    [callbacks => openTaskEvents('1', '', callbacks.onChunk, callbacks.onDone, callbacks.onError), '后台研究事件流长时间没有新数据，正在尝试重连。'],
  ]) {
    for (const timeout of [true, false]) {
      const events = []
      const controller = start({ onDone: () => events.push('done'), onError: error => events.push(error.message) })
      await new Promise(setImmediate)
      if (timeout) t.mock.timers.tick(90_000)
      else controller.abort()
      await new Promise(setImmediate)
      assert.equal(controller.signal.aborted, true)
      assert.deepEqual(events, timeout ? [message] : [])
    }
  }
})
