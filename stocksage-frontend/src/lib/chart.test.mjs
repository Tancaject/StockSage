import test from 'node:test'
import assert from 'node:assert/strict'

import {
  KLINE_RANGE_OPTIONS,
  buildChartProvenance,
  enrichKLineSeries,
  panKLineZoomState,
  resolveKLinePlotRange,
  resolveKLineZoomWindow,
  selectKLineRange,
  selectRecentKLineWindow,
  summarizeKLineSeries,
  updateKLineZoomState,
} from './chart.js'

test('enrichKLineSeries adds moving averages and volume average', () => {
  const series = enrichKLineSeries([
    { date: '2026-05-01', open: 10, high: 12, low: 9, close: 11, volume: 100 },
    { date: '2026-05-02', open: 11, high: 13, low: 10, close: 12, volume: 200 },
    { date: '2026-05-03', open: 12, high: 14, low: 11, close: 13, volume: 300 },
    { date: '2026-05-04', open: 13, high: 15, low: 12, close: 14, volume: 400 },
    { date: '2026-05-05', open: 14, high: 16, low: 13, close: 15, volume: 500 },
  ], { maWindows: [3], volumeWindow: 3 })

  assert.equal(series.at(-1).ma.MA3, 14)
  assert.equal(series.at(-1).volumeAverage, 400)
  assert.equal(series[0].ma.MA3, null)
})

test('buildChartProvenance records ticker, range, indicators, and generation time', () => {
  const provenance = buildChartProvenance({
    symbol: 'NVDA',
    period: 'daily',
    bar: '1d',
    indicators: ['MA5', 'MA20', 'VOL_MA5'],
    generatedAt: '2026-05-30T10:00:00.000Z',
    sourceTool: 'getStockKLine',
  })

  assert.equal(provenance.symbol, 'NVDA')
  assert.equal(provenance.period, 'daily')
  assert.equal(provenance.bar, '1d')
  assert.deepEqual(provenance.indicators, ['MA5', 'MA20', 'VOL_MA5'])
  assert.equal(provenance.generatedAt, '2026-05-30T10:00:00.000Z')
  assert.equal(provenance.sourceTool, 'getStockKLine')
})

test('selectKLineRange returns the requested trailing candles without mutating input', () => {
  const points = Array.from({ length: 5 }, (_, index) => ({ date: `2026-05-0${index + 1}`, close: index + 1 }))

  const selected = selectKLineRange(points, 3)

  assert.deepEqual(selected.map(point => point.close), [3, 4, 5])
  assert.equal(points.length, 5)
  assert.deepEqual(selectKLineRange(points, 'all').map(point => point.close), [1, 2, 3, 4, 5])
})

test('K-line range options start with a 1D intraday view before longer trailing windows', () => {
  assert.deepEqual(KLINE_RANGE_OPTIONS.slice(0, 3), [
    { value: '1d', label: '1D' },
    { value: '10', label: '10D' },
    { value: '30', label: '30D' },
  ])
})

test('selectRecentKLineWindow keeps the latest 24 hours of timestamped intraday points', () => {
  const points = [
    { date: '2026-07-01T15:55:00Z', close: 1 },
    { date: '2026-07-01T16:00:00Z', close: 2 },
    { date: '2026-07-02T15:55:00Z', close: 3 },
    { date: '2026-07-02T16:00:00Z', close: 4 },
  ]

  const selected = selectRecentKLineWindow(points, { hours: 24 })

  assert.deepEqual(selected.map(point => point.close), [2, 3, 4])
})

test('resolveKLinePlotRange lets intraday sparse candles use the full chart width', () => {
  const normal = resolveKLinePlotRange({ count: 5, left: 58, right: 798, maxSlot: 56 })
  const filled = resolveKLinePlotRange({ count: 5, left: 58, right: 798, maxSlot: 56, fill: true })

  assert.ok(normal.left > 58)
  assert.ok(normal.right < 798)
  assert.deepEqual(filled, { left: 58, right: 798 })
})

test('resolveKLineZoomWindow slices the active visible candle window', () => {
  const points = Array.from({ length: 100 }, (_, index) => ({ close: index }))

  const all = resolveKLineZoomWindow(points, { scale: 1, offset: 20, minPoints: 8 })
  const zoomed = resolveKLineZoomWindow(points, { scale: 4, offset: 30, minPoints: 8 })

  assert.equal(all.start, 0)
  assert.equal(all.end, 100)
  assert.equal(all.canPan, false)
  assert.equal(zoomed.start, 30)
  assert.equal(zoomed.end, 55)
  assert.equal(zoomed.points[0].close, 30)
  assert.equal(zoomed.points.at(-1).close, 54)
  assert.equal(zoomed.canPan, true)
})

test('updateKLineZoomState zooms around the cursor anchor and pan clamps to data bounds', () => {
  const zoomed = updateKLineZoomState({
    scale: 1,
    offset: 0,
    total: 100,
    deltaY: -120,
    anchorRatio: 0.75,
    minPoints: 10,
    maxScale: 8,
  })
  const panned = panKLineZoomState({
    ...zoomed,
    total: 100,
    deltaSlots: 4,
    minPoints: 10,
    maxScale: 8,
  })
  const clamped = panKLineZoomState({
    ...zoomed,
    total: 100,
    deltaSlots: -999,
    minPoints: 10,
    maxScale: 8,
  })

  assert.ok(zoomed.scale > 1)
  assert.ok(zoomed.visibleCount < 100)
  assert.ok(zoomed.offset > 0)
  assert.equal(panned.offset, zoomed.offset + 4)
  assert.equal(clamped.offset, 0)
})

test('summarizeKLineSeries calculates range stats for the K-line panel', () => {
  const summary = summarizeKLineSeries([
    { date: '2026-05-01', open: 10, high: 12, low: 9, close: 11, volume: 100 },
    { date: '2026-05-02', open: 11, high: 13, low: 10, close: 12, volume: 200 },
    { date: '2026-05-03', open: 12, high: 14, low: 8, close: 13, volume: 300 },
  ])

  assert.equal(summary.count, 3)
  assert.equal(summary.highPoint.date, '2026-05-03')
  assert.equal(summary.lowPoint.date, '2026-05-03')
  assert.equal(summary.totalVolume, 600)
  assert.equal(summary.rangeChangePct, 18.1818)
  assert.equal(summary.latestChangePct, 8.3333)
})
