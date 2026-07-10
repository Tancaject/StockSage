function parseEntryId(value) {
  const text = String(value || '').trim()
  const match = /^(\d+)-(\d+)$/.exec(text)
  if (!match) return null
  return { text, milliseconds: BigInt(match[1]), sequence: BigInt(match[2]) }
}

export function latestEntryId(previous, next) {
  const prev = parseEntryId(previous)
  const candidate = parseEntryId(next)

  if (!prev) return candidate?.text || ''
  if (!candidate) return prev.text
  if (candidate.milliseconds !== prev.milliseconds) {
    return candidate.milliseconds > prev.milliseconds ? candidate.text : prev.text
  }
  return candidate.sequence > prev.sequence ? candidate.text : prev.text
}

export function isTaskTerminal(chunk) {
  return chunk?.type === 'task-final' || chunk?.type === 'error'
}
