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
