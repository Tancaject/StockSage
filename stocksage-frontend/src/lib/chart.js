export const KLINE_RANGE_OPTIONS = [
  { value: '1d', label: '1D' },
  { value: '10', label: '10D' },
  { value: '30', label: '30D' },
  { value: '60', label: '60D' },
  { value: '120', label: '120D' },
  { value: '250', label: '1Y' },
  { value: 'all', label: '全部' },
]

export function enrichKLineSeries(points, { maWindows = [5, 20], volumeWindow = 5 } = {}) {
  const normalized = (Array.isArray(points) ? points : [])
    .map(point => ({
      ...point,
      open: numberOrNull(point.open ?? point.Open ?? point.o),
      high: numberOrNull(point.high ?? point.High ?? point.h),
      low: numberOrNull(point.low ?? point.Low ?? point.l),
      close: numberOrNull(point.close ?? point.Close ?? point.c),
      volume: numberOrNull(point.volume ?? point.Volume ?? point.v) || 0,
    }))
    .filter(point => [point.open, point.high, point.low, point.close].every(value => value !== null))

  return normalized.map((point, index) => {
    const ma = {}
    for (const windowSize of maWindows) {
      ma[`MA${windowSize}`] = rollingAverage(normalized, index, windowSize, item => item.close)
    }
    return {
      ...point,
      ma,
      volumeAverage: rollingAverage(normalized, index, volumeWindow, item => item.volume),
    }
  })
}

export function buildChartProvenance(chart = {}) {
  const indicators = Array.isArray(chart.indicators)
    ? chart.indicators.map(item => String(item || '').trim()).filter(Boolean)
    : String(chart.indicators || '').split(',').map(item => item.trim()).filter(Boolean)
  return {
    symbol: chart.symbol || chart.ticker || '',
    period: chart.period || '',
    bar: chart.bar || chart.interval || '',
    indicators,
    generatedAt: chart.generatedAt || chart.generated_at || new Date().toISOString(),
    sourceTool: chart.sourceTool || chart.source_tool || '',
  }
}

export function selectKLineRange(points, range = 'all') {
  const series = Array.isArray(points) ? points : []
  if (range === 'all') return [...series]
  const count = Number(range)
  if (!Number.isFinite(count) || count <= 0 || series.length <= count) return [...series]
  return series.slice(series.length - count)
}

export function selectRecentKLineWindow(points, { hours = 24 } = {}) {
  const series = Array.isArray(points) ? points : []
  const windowMs = Number(hours) * 60 * 60 * 1000
  if (!Number.isFinite(windowMs) || windowMs <= 0 || !series.length) return [...series]

  const rows = series.map(point => ({
    point,
    timestamp: timestampFromKLineDate(point?.date ?? point?.time ?? point?.t),
  }))
  const datedRows = rows.filter(row => Number.isFinite(row.timestamp))
  if (!datedRows.length) return [...series]

  const latestTimestamp = Math.max(...datedRows.map(row => row.timestamp))
  const cutoff = latestTimestamp - windowMs
  const selected = rows
    .filter(row => Number.isFinite(row.timestamp) && row.timestamp >= cutoff)
    .map(row => row.point)

  return selected.length ? selected : [datedRows[datedRows.length - 1].point]
}

export function resolveKLinePlotRange({
  count,
  left,
  right,
  maxSlot = 56,
  fill = false,
} = {}) {
  const normalizedLeft = Number(left)
  const normalizedRight = Number(right)
  if (!Number.isFinite(normalizedLeft) || !Number.isFinite(normalizedRight) || normalizedRight <= normalizedLeft) {
    return { left: 0, right: 0 }
  }
  const normalizedCount = Number(count)
  if (fill || !Number.isFinite(normalizedCount) || normalizedCount <= 1) {
    return { left: normalizedLeft, right: normalizedRight }
  }

  const width = normalizedRight - normalizedLeft
  const slot = width / normalizedCount
  const cap = Number(maxSlot)
  if (!Number.isFinite(cap) || cap <= 0 || slot <= cap) {
    return { left: normalizedLeft, right: normalizedRight }
  }

  const usedWidth = normalizedCount * cap
  const pad = Math.max((width - usedWidth) / 2, 0)
  return { left: normalizedLeft + pad, right: normalizedRight - pad }
}

export function resolveKLineZoomWindow(points, {
  scale = 1,
  offset = 0,
  minPoints = 8,
  maxScale = 8,
} = {}) {
  const series = Array.isArray(points) ? points : []
  const total = series.length
  if (!total) {
    return { points: [], start: 0, end: 0, visibleCount: 0, total: 0, scale: 1, offset: 0, canPan: false }
  }

  const normalizedScale = clampNumber(scale, 1, Math.max(1, Number(maxScale) || 1))
  const minVisible = clampNumber(Math.round(Number(minPoints) || 1), 1, total)
  const visibleCount = clampNumber(Math.ceil(total / normalizedScale), minVisible, total)
  const maxOffset = Math.max(total - visibleCount, 0)
  const start = Math.round(clampNumber(offset, 0, maxOffset))
  const end = start + visibleCount

  return {
    points: series.slice(start, end),
    start,
    end,
    visibleCount,
    total,
    scale: total / visibleCount,
    offset: start,
    canPan: visibleCount < total,
  }
}

export function updateKLineZoomState({
  scale = 1,
  offset = 0,
  total = 0,
  deltaY = 0,
  anchorRatio = 0.5,
  minPoints = 8,
  maxScale = 8,
  zoomFactor = 1.25,
} = {}) {
  const totalCount = Math.max(Math.round(Number(total) || 0), 0)
  if (!totalCount) return { scale: 1, offset: 0, visibleCount: 0, canPan: false }

  const current = resolveKLineZoomWindow(Array.from({ length: totalCount }), {
    scale,
    offset,
    minPoints,
    maxScale,
  })
  const direction = Number(deltaY) < 0 ? 1 : -1
  const factor = Math.max(Number(zoomFactor) || 1.25, 1.01)
  const nextScale = direction > 0
    ? current.scale * factor
    : current.scale / factor
  const normalizedAnchor = clampNumber(anchorRatio, 0, 1)
  const anchorIndex = current.start + normalizedAnchor * Math.max(current.visibleCount - 1, 0)
  const next = resolveKLineZoomWindow(Array.from({ length: totalCount }), {
    scale: nextScale,
    offset: 0,
    minPoints,
    maxScale,
  })
  const nextOffset = anchorIndex - normalizedAnchor * Math.max(next.visibleCount - 1, 0)

  return resolveKLineZoomWindow(Array.from({ length: totalCount }), {
    scale: nextScale,
    offset: nextOffset,
    minPoints,
    maxScale,
  })
}

export function panKLineZoomState({
  scale = 1,
  offset = 0,
  total = 0,
  deltaSlots = 0,
  minPoints = 8,
  maxScale = 8,
} = {}) {
  const totalCount = Math.max(Math.round(Number(total) || 0), 0)
  if (!totalCount) return { scale: 1, offset: 0, visibleCount: 0, canPan: false }
  return resolveKLineZoomWindow(Array.from({ length: totalCount }), {
    scale,
    offset: Number(offset || 0) + Number(deltaSlots || 0),
    minPoints,
    maxScale,
  })
}

export function summarizeKLineSeries(points) {
  const series = (Array.isArray(points) ? points : [])
    .filter(point => [point.open, point.high, point.low, point.close].every(Number.isFinite))

  if (!series.length) {
    return {
      count: 0,
      first: null,
      latest: null,
      highPoint: null,
      lowPoint: null,
      totalVolume: 0,
      rangeChangePct: null,
      latestChangePct: null,
    }
  }

  const first = series[0]
  const latest = series[series.length - 1]
  const highPoint = series.reduce((best, point) => point.high > best.high ? point : best, first)
  const lowPoint = series.reduce((best, point) => point.low < best.low ? point : best, first)
  const totalVolume = series.reduce((sum, point) => sum + (Number.isFinite(point.volume) ? point.volume : 0), 0)

  return {
    count: series.length,
    first,
    latest,
    highPoint,
    lowPoint,
    totalVolume: round(totalVolume),
    rangeChangePct: percentChange(latest.close, first.close),
    latestChangePct: percentChange(latest.close, latest.open),
  }
}

function rollingAverage(points, index, windowSize, getter) {
  const size = Number(windowSize)
  if (!Number.isFinite(size) || size <= 0 || index + 1 < size) return null
  const slice = points.slice(index + 1 - size, index + 1)
  const values = slice.map(getter).filter(value => Number.isFinite(value))
  if (values.length !== size) return null
  return round(values.reduce((sum, value) => sum + value, 0) / size)
}

function numberOrNull(value) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(String(value).replace(/,/g, ''))
  return Number.isFinite(number) ? number : null
}

function clampNumber(value, min, max) {
  const number = Number(value)
  if (!Number.isFinite(number)) return min
  return Math.min(max, Math.max(min, number))
}

export function timestampFromKLineDate(value) {
  if (value === null || value === undefined || value === '') return NaN
  const text = String(value).trim()
  if (/^\d{13}$/.test(text)) return Number(text)
  if (/^\d{10}$/.test(text)) return Number(text) * 1000
  if (/^\d{8}$/.test(text)) {
    return Date.parse(`${text.slice(0, 4)}-${text.slice(4, 6)}-${text.slice(6, 8)}T00:00:00`)
  }

  const compactDateTime = text.match(/^(\d{4})(\d{2})(\d{2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?$/)
  if (compactDateTime) {
    const [, year, month, day, hour, minute, second = '0'] = compactDateTime
    return new Date(
      Number(year),
      Number(month) - 1,
      Number(day),
      Number(hour),
      Number(minute),
      Number(second),
    ).getTime()
  }

  const normalized = text.replace(' ', 'T')
  const parsed = Date.parse(normalized)
  return Number.isFinite(parsed) ? parsed : NaN
}

function round(value) {
  return Math.round(value * 10000) / 10000
}

function percentChange(current, base) {
  if (!Number.isFinite(current) || !Number.isFinite(base) || base === 0) return null
  return round(((current - base) / base) * 100)
}
