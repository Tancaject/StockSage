import assert from 'node:assert/strict'
import test from 'node:test'

import { readSseEvents } from './sse-stream.js'

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
