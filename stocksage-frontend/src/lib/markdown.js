const QUOTED_STRONG_PATTERN = /\*\*([“‘"])([^*\n]+?)([”’"])\*\*/g

export function normalizeMarkdownEmphasis(markdown = '') {
  return String(markdown).replace(QUOTED_STRONG_PATTERN, '$1**$2**$3')
}

export function markdownToPlainText(markdown = '') {
  const lines = String(markdown)
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map(stripMarkdownLine)
    .filter(line => line !== null)

  while (lines[0] === '') lines.shift()
  while (lines.at(-1) === '') lines.pop()

  return lines
    .filter((line, index) => line !== '' || lines[index - 1] !== '')
    .join('\n')
}

function stripMarkdownLine(line) {
  let text = String(line || '').trim()

  if (/^(```|~~~)/.test(text)) return null
  if (/^([-*_]\s*){3,}$/.test(text)) return null
  if (isMarkdownTableSeparator(text)) return null

  const tableRow = markdownTableRowToPlainText(text)
  if (tableRow !== null) return tableRow

  return stripMarkdownInline(text)
}

function markdownTableRowToPlainText(text) {
  if (!String(text || '').includes('|')) return null

  const cells = String(text)
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map(cell => stripMarkdownInline(cell.trim()))
    .filter(Boolean)

  if (cells.length < 2) return null
  return `${cells[0]}：${cells.slice(1).join(' · ')}`
}

function isMarkdownTableSeparator(text) {
  if (!String(text || '').includes('|')) return false
  const cells = String(text)
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map(cell => cell.trim())
    .filter(Boolean)

  return cells.length > 0 && cells.every(cell => /^:?-{2,}:?$/.test(cell))
}

function stripMarkdownInline(text) {
  return String(text || '')
    .replace(/^#{1,6}\s+/, '')
    .replace(/^>\s?/, '')
    .replace(/^[-*+]\s+/, '')
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*\*([^*\n]+)\*\*/g, '$1')
    .replace(/__([^_\n]+)__/g, '$1')
    .replace(/\*([^*\n]+)\*/g, '$1')
    .replace(/_([^_\n]+)_/g, '$1')
    .replace(/~~([^~\n]+)~~/g, '$1')
    .replace(/\\([\\`*{}\[\]()#+\-.!_>])/g, '$1')
    .trim()
}
