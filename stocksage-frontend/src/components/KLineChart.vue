<template>
  <section v-if="visiblePoints.length" class="kline-panel">
    <header class="panel-hero">
      <div class="identity-block">
        <span class="panel-eyebrow">K 线分析</span>
        <div class="symbol-row">
          <strong>{{ chartSymbol }}</strong>
          <span v-if="periodLabel">{{ periodLabel }}</span>
        </div>
        <p>{{ chartTitle }}</p>
      </div>

      <div class="price-summary" :class="trendClass">
        <span>最新收盘</span>
        <strong>{{ latestCloseLabel }}</strong>
        <em>{{ rangeReturnLabel }}</em>
      </div>
    </header>

    <div class="panel-controls">
      <div class="range-tabs" aria-label="K线区间">
        <button
          v-for="option in rangeOptions"
          :key="option.value"
          type="button"
          :class="{ active: selectedRange === option.value }"
          @click="setRange(option.value)"
        >
          {{ option.label }}
        </button>
      </div>

      <div class="indicator-legend" aria-label="图例">
        <span><i class="legend-dot candle-up"></i>上涨</span>
        <span><i class="legend-dot candle-down"></i>下跌</span>
        <span><i class="legend-line ma-5"></i>MA5</span>
        <span><i class="legend-line ma-20"></i>MA20</span>
        <span><i class="legend-line ma-60"></i>MA60</span>
      </div>
    </div>

    <div v-if="isIntraday && intradayLoading" class="chart-placeholder intraday-loading">
      <span>正在加载日内 5 分钟 K 线…</span>
    </div>
    <div v-else-if="isIntraday && intradayError" class="chart-placeholder intraday-error">
      <span>{{ intradayError }}</span>
    </div>
    <div class="chart-stage" v-else>
      <svg
        class="chart-svg"
        :class="{ zoomable: canPanChart, panning: isPanning }"
        :viewBox="`0 0 ${viewBox.width} ${viewBox.height}`"
        role="img"
        :aria-label="chartTitle"
        @wheel.prevent="handleChartWheel"
        @pointerdown="handleChartPointerDown"
        @pointermove="handleChartPointerMove"
        @pointerup="finishChartPan"
        @pointercancel="finishChartPan"
        @mouseleave="handleChartMouseLeave"
      >
        <defs>
          <linearGradient id="klinePanelPaper" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="var(--surface)" />
            <stop offset="100%" stop-color="var(--surface-raised)" />
          </linearGradient>
          <linearGradient id="klineVolumeFade" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stop-color="var(--border-strong)" stop-opacity="0.12" />
            <stop offset="100%" stop-color="var(--border-strong)" stop-opacity="0.4" />
          </linearGradient>
        </defs>

        <rect class="plot-paper" :x="0" :y="0" :width="viewBox.width" :height="viewBox.height" rx="8" />
        <rect
          class="volume-band"
          :x="layout.left"
          :y="layout.volumeTop"
          :width="innerWidth"
          :height="layout.volumeBottom - layout.volumeTop"
          rx="6"
        />

        <g class="chart-grid">
          <g v-for="tick in yTicks" :key="tick.value">
            <line class="grid-line" :x1="layout.left" :x2="layout.right" :y1="tick.y" :y2="tick.y" />
            <text class="y-label" :x="layout.right + 10" :y="tick.y + 4">{{ tick.label }}</text>
          </g>
          <line class="axis-line" :x1="layout.left" :x2="layout.right" :y1="layout.priceBottom" :y2="layout.priceBottom" />
          <line class="axis-line" :x1="layout.left" :x2="layout.right" :y1="layout.volumeBottom" :y2="layout.volumeBottom" />
          <line
            v-for="tick in verticalTicks"
            :key="`v-${tick.index}`"
            class="grid-line vertical"
            :x1="tick.x"
            :x2="tick.x"
            :y1="layout.priceTop"
            :y2="layout.volumeBottom"
          />
        </g>

        <g class="volume-bars">
          <rect
            v-for="bar in volumeBars"
            :key="`vol-${bar.index}`"
            class="volume-bar"
            :class="{ up: bar.isUp, down: !bar.isUp }"
            :x="bar.x"
            :y="bar.y"
            :width="bar.width"
            :height="bar.height"
          />
        </g>

        <path v-if="volumeAveragePath" class="volume-average-line" :d="volumeAveragePath" />

        <g class="candles">
          <line
            v-for="candle in candles"
            :key="`wick-${candle.index}`"
            class="candle-wick"
            :class="{ up: candle.isUp, down: !candle.isUp }"
            :x1="candle.x"
            :x2="candle.x"
            :y1="candle.highY"
            :y2="candle.lowY"
          />
          <rect
            v-for="candle in candles"
            :key="`body-${candle.index}`"
            class="candle-body"
            :class="{ up: candle.isUp, down: !candle.isUp }"
            :x="candle.bodyX"
            :y="candle.bodyY"
            :width="candle.bodyWidth"
            :height="candle.bodyHeight"
            rx="2"
          />
        </g>

        <g class="ma-lines">
          <path
            v-for="line in maLines"
            :key="line.name"
            class="ma-line"
            :class="line.className"
            :d="line.path"
          />
        </g>

        <g v-if="latestLine" class="latest-price">
          <line :x1="layout.left" :x2="layout.right" :y1="latestLine.y" :y2="latestLine.y" />
          <rect :x="layout.right - 72" :y="latestLine.y - 12" width="68" height="24" rx="6" />
          <text :x="layout.right - 38" :y="latestLine.y + 4" text-anchor="middle">{{ latestLine.label }}</text>
        </g>

        <g v-if="activeGuide" class="crosshair">
          <line :x1="activeGuide.x" :x2="activeGuide.x" :y1="layout.priceTop" :y2="layout.volumeBottom" />
          <line :x1="layout.left" :x2="layout.right" :y1="activeGuide.y" :y2="activeGuide.y" />
          <circle :cx="activeGuide.x" :cy="activeGuide.y" r="4.5" />
        </g>

        <g class="x-axis">
          <text
            v-for="tick in xTicks"
            :key="tick.index"
            class="x-label"
            :x="tick.x"
            :y="layout.volumeBottom + 28"
            text-anchor="middle"
          >
            {{ tick.label }}
          </text>
        </g>
      </svg>

      <aside v-if="activePoint" class="chart-tooltip" :class="activeTrendClass" :style="tooltipStyle">
        <strong>{{ formatFullDate(activePoint.date) }}</strong>
        <dl>
          <div><dt>开</dt><dd>{{ formatPrice(activePoint.open) }}</dd></div>
          <div><dt>高</dt><dd>{{ formatPrice(activePoint.high) }}</dd></div>
          <div><dt>低</dt><dd>{{ formatPrice(activePoint.low) }}</dd></div>
          <div><dt>收</dt><dd>{{ formatPrice(activePoint.close) }}</dd></div>
          <div><dt>涨跌</dt><dd class="change-value">{{ formatSignedPercent(activePointChangePct) }}</dd></div>
          <div><dt>量</dt><dd>{{ formatVolume(activePoint.volume) }}</dd></div>
        </dl>
      </aside>
    </div>

    <div class="stat-strip">
      <article v-for="item in statItems" :key="item.label" class="stat-item" :class="item.tone">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.note }}</small>
      </article>
    </div>

    <footer class="panel-footer">
      <span>{{ visiblePoints.length }} / {{ sourcePointCount }} 根K线</span>
      <span v-if="rangeDateLabel">{{ rangeDateLabel }}</span>
      <span v-if="sourceLabel">来源 {{ sourceLabel }}</span>
      <span v-if="indicatorText">{{ indicatorText }}</span>
      <span v-if="generatedAtLabel">{{ generatedAtLabel }}</span>
    </footer>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
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
  timestampFromKLineDate,
  updateKLineZoomState,
} from '../lib/chart.js'
import { fetchStockIntraday } from '../api/workbench.js'

const props = defineProps({
  chart: { type: Object, required: true },
})

const viewBox = {
  width: 860,
  height: 430,
}

const layout = {
  left: 58,
  right: 798,
  priceTop: 34,
  priceBottom: 284,
  volumeTop: 314,
  volumeBottom: 372,
}

const innerWidth = layout.right - layout.left
const selectedRange = ref('120')
const activeIndex = ref(null)
const zoomScale = ref(1)
const zoomOffset = ref(0)
const isPanning = ref(false)
const panStart = ref({ x: 0, offset: 0 })

const MIN_ZOOM_POINTS = 8
const MAX_ZOOM_SCALE = 8

const intradayRaw = ref(null)
const intradayLoading = ref(false)
const intradayError = ref('')

async function loadIntraday() {
  const symbol = chartSymbol.value
  if (!symbol || intradayRaw.value || intradayLoading.value) return
  intradayLoading.value = true
  intradayError.value = ''
  try {
    const data = await fetchStockIntraday(symbol)
    if (data?.error) {
      intradayError.value = data.message || '日内数据不可用'
    } else {
      intradayRaw.value = data
    }
  } catch {
    intradayError.value = '日内数据加载失败'
  } finally {
    intradayLoading.value = false
  }
}

watch(() => props.chart?.symbol, () => {
  intradayRaw.value = null
  intradayError.value = ''
  resetZoom()
})

const intradayPoints = computed(() => {
  if (!intradayRaw.value) return []
  const source = Array.isArray(intradayRaw.value.points) ? intradayRaw.value.points : []
  return source
    .map((point) => ({
      date: point.date ?? point.time ?? point.t ?? '',
      open: toNumber(point.open ?? point.o),
      high: toNumber(point.high ?? point.h),
      low: toNumber(point.low ?? point.l),
      close: toNumber(point.close ?? point.c),
      volume: toNumber(point.volume ?? point.v) || 0,
    }))
    .filter(point => [point.open, point.high, point.low, point.close].every(Number.isFinite))
})

const recentIntradayPoints = computed(() => selectRecentKLineWindow(intradayPoints.value, { hours: 24 }))

const enrichedIntraday = computed(() => enrichKLineSeries(recentIntradayPoints.value, {
  maWindows: [5, 20],
  volumeWindow: 5,
}))

const isIntraday = computed(() => selectedRange.value === '1d')

const rangeOptions = KLINE_RANGE_OPTIONS

const rawPoints = computed(() => {
  const source = Array.isArray(props.chart?.points) ? props.chart.points : []
  return source
    .map((point) => ({
      date: point.date ?? point.Date ?? point.time ?? point.t ?? point.tradeDate ?? '',
      open: toNumber(point.open ?? point.Open ?? point.o),
      high: toNumber(point.high ?? point.High ?? point.h),
      low: toNumber(point.low ?? point.Low ?? point.l),
      close: toNumber(point.close ?? point.Close ?? point.c),
      volume: toNumber(point.volume ?? point.Volume ?? point.v) || 0,
    }))
    .filter(point => [point.open, point.high, point.low, point.close].every(Number.isFinite))
})

const enrichedPoints = computed(() => enrichKLineSeries(rawPoints.value, {
  maWindows: [5, 20, 60],
  volumeWindow: 5,
}))

const baseRangePoints = computed(() =>
  isIntraday.value ? enrichedIntraday.value : selectKLineRange(enrichedPoints.value, selectedRange.value)
)

const zoomWindow = computed(() => resolveKLineZoomWindow(baseRangePoints.value, {
  scale: zoomScale.value,
  offset: zoomOffset.value,
  minPoints: MIN_ZOOM_POINTS,
  maxScale: MAX_ZOOM_SCALE,
}))

const visiblePoints = computed(() => zoomWindow.value.points)
const canPanChart = computed(() => zoomWindow.value.canPan)
const sourcePointCount = computed(() => baseRangePoints.value.length)
watch(() => sourcePointCount.value, () => resetZoom())
const summary = computed(() => summarizeKLineSeries(visiblePoints.value))

const chartProvenance = computed(() => buildChartProvenance({
  ...props.chart,
  indicators: props.chart?.indicators || ['MA5', 'MA20', 'MA60', 'VOL_MA5'],
}))

const chartSymbol = computed(() => props.chart?.symbol || props.chart?.ticker || 'KLINE')
const chartTitle = computed(() => props.chart?.title || `${chartSymbol.value} K线走势`)
const periodLabel = computed(() => {
  if (isIntraday.value) return '过去24h · 5分钟'
  const period = String(props.chart?.period || '').toLowerCase()
  return {
    daily: '日线',
    weekly: '周线',
    monthly: '月线',
    '1d': '1日',
    '1w': '1周',
    '1m': '1月',
    '1h': '1小时',
  }[period] || props.chart?.period || ''
})

const trendClass = computed(() => toneClass(summary.value.rangeChangePct))
const latestCloseLabel = computed(() => formatPrice(summary.value.latest?.close))
const rangeReturnLabel = computed(() => formatSignedPercent(summary.value.rangeChangePct))
const sourceLabel = computed(() => chartProvenance.value.sourceTool || props.chart?.source || '')
const indicatorText = computed(() => chartProvenance.value.indicators.length
  ? chartProvenance.value.indicators.join(' / ')
  : '')
const generatedAtLabel = computed(() => formatGeneratedAt(chartProvenance.value.generatedAt))
const rangeDateLabel = computed(() => {
  if (!summary.value.first || !summary.value.latest) return ''
  return `${formatFullDate(summary.value.first.date)} - ${formatFullDate(summary.value.latest.date)}`
})

const priceRange = computed(() => {
  const points = visiblePoints.value
  if (!points.length) {
    return { min: 0, max: 1 }
  }

  const values = points.flatMap(point => [
    point.low,
    point.high,
    point.ma?.MA5,
    point.ma?.MA20,
    point.ma?.MA60,
  ]).filter(Number.isFinite)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const padding = Math.max((max - min) * 0.08, max === min ? Math.max(max * 0.02, 1) : 0)
  return { min: min - padding, max: max + padding }
})

const maxVolume = computed(() => Math.max(...visiblePoints.value.map(point => point.volume), 0))

const MAX_SLOT = 56

const effectiveRange = computed(() => {
  const count = visiblePoints.value.length
  return resolveKLinePlotRange({
    count,
    left: layout.left,
    right: layout.right,
    maxSlot: MAX_SLOT,
    fill: isIntraday.value || canPanChart.value,
  })
})

const effectiveWidth = computed(() => effectiveRange.value.right - effectiveRange.value.left)

const candleWidth = computed(() => {
  const count = Math.max(visiblePoints.value.length, 1)
  const slot = effectiveWidth.value / count
  return Math.min(slot * 0.58, Math.max(2.4, 10))
})

const candles = computed(() => visiblePoints.value.map((point, index) => {
  const x = xForIndex(index)
  const openY = priceY(point.open)
  const closeY = priceY(point.close)
  return {
    index,
    x,
    highY: priceY(point.high),
    lowY: priceY(point.low),
    bodyX: x - candleWidth.value / 2,
    bodyY: Math.min(openY, closeY),
    bodyWidth: candleWidth.value,
    bodyHeight: Math.max(Math.abs(openY - closeY), 1.3),
    isUp: point.close >= point.open,
  }
}))

const volumeBars = computed(() => visiblePoints.value.map((point, index) => {
  const height = maxVolume.value > 0
    ? Math.max((point.volume / maxVolume.value) * (layout.volumeBottom - layout.volumeTop), point.volume > 0 ? 1 : 0)
    : 0
  return {
    index,
    x: xForIndex(index) - candleWidth.value / 2,
    y: layout.volumeBottom - height,
    width: candleWidth.value,
    height,
    isUp: point.close >= point.open,
  }
}))

const yTicks = computed(() => {
  const ticks = []
  const { min, max } = priceRange.value
  for (let i = 0; i < 5; i += 1) {
    const ratio = i / 4
    const value = max - (max - min) * ratio
    ticks.push({ value: value.toFixed(4), y: priceY(value), label: formatPrice(value) })
  }
  return ticks
})

const xTicks = computed(() => {
  const points = visiblePoints.value
  if (!points.length) return []
  return uniqueIndexes([
    0,
    Math.round((points.length - 1) * 0.25),
    Math.round((points.length - 1) * 0.5),
    Math.round((points.length - 1) * 0.75),
    points.length - 1,
  ]).map(index => ({ index, x: xForIndex(index), label: formatDate(points[index].date) }))
})

const verticalTicks = computed(() => xTicks.value.slice(1, -1))

const latestLine = computed(() => {
  const latest = summary.value.latest
  if (!latest) return null
  return { y: priceY(latest.close), label: formatPrice(latest.close) }
})

const maLines = computed(() => {
  const count = visiblePoints.value.length
  const windows = count <= 10 ? [] : count <= 30 ? [5, 20] : [5, 20, 60]
  return windows
    .map(windowSize => {
      const name = `MA${windowSize}`
      return {
        name,
        className: `ma-${windowSize}`,
        path: pathFromSeries(visiblePoints.value
          .map((point, index) => ({ index, value: point.ma?.[name] }))
          .filter(item => Number.isFinite(item.value)), priceY),
      }
    })
    .filter(line => line.path)
})

const volumeAveragePath = computed(() => pathFromSeries(visiblePoints.value
  .map((point, index) => ({ index, value: point.volumeAverage }))
  .filter(item => Number.isFinite(item.value)), volumeY))

const activePoint = computed(() => {
  if (activeIndex.value === null) return null
  return visiblePoints.value[activeIndex.value] || null
})

const activePointChangePct = computed(() => activePoint.value
  ? percentChange(activePoint.value.close, activePoint.value.open)
  : null)

const activeTrendClass = computed(() => toneClass(activePointChangePct.value))

const activeGuide = computed(() => {
  if (!activePoint.value || activeIndex.value === null) return null
  return {
    x: xForIndex(activeIndex.value),
    y: priceY(activePoint.value.close),
  }
})

const tooltipStyle = computed(() => {
  if (!activeGuide.value) return {}
  const xPct = activeGuide.value.x / viewBox.width * 100
  const yPct = activeGuide.value.y / viewBox.height * 100
  return {
    left: `${Math.min(76, Math.max(12, xPct + 2))}%`,
    top: `${Math.min(68, Math.max(8, yPct - 8))}%`,
  }
})

const statItems = computed(() => {
  const data = summary.value
  return [
    {
      label: '区间涨跌',
      value: formatSignedPercent(data.rangeChangePct),
      note: `${data.count} 根K线`,
      tone: toneClass(data.rangeChangePct),
    },
    {
      label: '区间最高',
      value: formatPrice(data.highPoint?.high),
      note: data.highPoint ? formatDate(data.highPoint.date) : '--',
      tone: 'up',
    },
    {
      label: '区间最低',
      value: formatPrice(data.lowPoint?.low),
      note: data.lowPoint ? formatDate(data.lowPoint.date) : '--',
      tone: 'down',
    },
    {
      label: '成交量',
      value: formatVolume(data.totalVolume),
      note: '区间合计',
      tone: '',
    },
    {
      label: '当日涨跌',
      value: formatSignedPercent(data.latestChangePct),
      note: data.latest ? formatDate(data.latest.date) : '--',
      tone: toneClass(data.latestChangePct),
    },
  ]
})

function setRange(value) {
  selectedRange.value = value
  resetZoom()
  if (value === '1d') loadIntraday()
}

function resetZoom() {
  zoomScale.value = 1
  zoomOffset.value = 0
  isPanning.value = false
  panStart.value = { x: 0, offset: 0 }
  activeIndex.value = null
}

function handleChartWheel(event) {
  const total = sourcePointCount.value
  if (total <= MIN_ZOOM_POINTS) return
  const bounds = event.currentTarget.getBoundingClientRect()
  const anchorRatio = clamp((event.clientX - bounds.left) / bounds.width, 0, 1)
  const next = updateKLineZoomState({
    scale: zoomScale.value,
    offset: zoomOffset.value,
    total,
    deltaY: event.deltaY,
    anchorRatio,
    minPoints: MIN_ZOOM_POINTS,
    maxScale: MAX_ZOOM_SCALE,
  })
  zoomScale.value = next.scale
  zoomOffset.value = next.offset
  activeIndex.value = null
}

function handleChartPointerDown(event) {
  if (event.button !== 0 || !canPanChart.value) return
  event.preventDefault()
  isPanning.value = true
  panStart.value = { x: event.clientX, offset: zoomOffset.value }
  activeIndex.value = null
  event.currentTarget.setPointerCapture?.(event.pointerId)
}

function handleChartPointerMove(event) {
  if (isPanning.value) {
    event.preventDefault()
    const bounds = event.currentTarget.getBoundingClientRect()
    const slotWidth = bounds.width / Math.max(zoomWindow.value.visibleCount, 1)
    const deltaSlots = -(event.clientX - panStart.value.x) / Math.max(slotWidth, 1)
    const next = panKLineZoomState({
      scale: zoomScale.value,
      offset: panStart.value.offset,
      total: sourcePointCount.value,
      deltaSlots,
      minPoints: MIN_ZOOM_POINTS,
      maxScale: MAX_ZOOM_SCALE,
    })
    zoomScale.value = next.scale
    zoomOffset.value = next.offset
    activeIndex.value = null
    return
  }
  handleChartMouseMove(event)
}

function finishChartPan(event) {
  if (!isPanning.value) return
  isPanning.value = false
  event.currentTarget.releasePointerCapture?.(event.pointerId)
}

function handleChartMouseLeave() {
  if (!isPanning.value) clearActivePoint()
}

function handleChartMouseMove(event) {
  const count = visiblePoints.value.length
  if (!count) return
  const bounds = event.currentTarget.getBoundingClientRect()
  const viewX = ((event.clientX - bounds.left) / bounds.width) * viewBox.width
  const { left, right } = effectiveRange.value
  const ratio = (viewX - left) / (right - left)
  activeIndex.value = clamp(Math.round(ratio * (count - 1)), 0, count - 1)
}

function clearActivePoint() {
  activeIndex.value = null
}

function xForIndex(index) {
  const count = visiblePoints.value.length
  if (count <= 1) return layout.left + innerWidth / 2
  const { left, right } = effectiveRange.value
  return left + (index / (count - 1)) * (right - left)
}

function priceY(value) {
  const { min, max } = priceRange.value
  const span = max - min || 1
  return layout.priceBottom - ((value - min) / span) * (layout.priceBottom - layout.priceTop)
}

function volumeY(value) {
  const max = maxVolume.value || 1
  return layout.volumeBottom - (value / max) * (layout.volumeBottom - layout.volumeTop)
}

function pathFromSeries(series, yMapper) {
  if (!series.length) return ''
  return series
    .map((item, pathIndex) => {
      const command = pathIndex === 0 ? 'M' : 'L'
      return `${command}${xForIndex(item.index).toFixed(2)},${yMapper(item.value).toFixed(2)}`
    })
    .join(' ')
}

function toNumber(value) {
  if (value === null || value === undefined || value === '') return NaN
  const number = Number(String(value).replace(/,/g, ''))
  return Number.isFinite(number) ? number : NaN
}

function percentChange(current, base) {
  if (!Number.isFinite(current) || !Number.isFinite(base) || base === 0) return null
  return ((current - base) / base) * 100
}

function formatPrice(value) {
  if (!Number.isFinite(value)) return '--'
  if (Math.abs(value) >= 1000) return value.toFixed(0)
  if (Math.abs(value) >= 100) return value.toFixed(1)
  return value.toFixed(2)
}

function formatSignedPercent(value) {
  if (!Number.isFinite(value)) return '--'
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

function formatVolume(value) {
  if (!Number.isFinite(value)) return '--'
  const abs = Math.abs(value)
  if (abs >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2)}B`
  if (abs >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`
  if (abs >= 10_000) return `${(value / 10_000).toFixed(1)}万`
  return value.toFixed(0)
}

function formatDate(value) {
  const text = String(value || '')
  if (isIntraday.value) {
    const timestamp = timestampFromKLineDate(text)
    if (Number.isFinite(timestamp)) return timeFromTimestamp(timestamp)
  }
  if (/^\d{13}$/.test(text)) {
    if (isIntraday.value) return timeFromTimestamp(Number(text))
    return dateFromTimestamp(Number(text), false)
  }
  if (/^\d{10}$/.test(text)) {
    if (isIntraday.value) return timeFromTimestamp(Number(text) * 1000)
    return dateFromTimestamp(Number(text) * 1000, false)
  }
  if (/^\d{4}-\d{2}-\d{2}/.test(text)) return text.slice(5, 10)
  if (/^\d{8}$/.test(text)) return `${text.slice(4, 6)}-${text.slice(6, 8)}`
  return text.length > 10 ? text.slice(0, 10) : text || '--'
}

function timeFromTimestamp(timestamp) {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return '--'
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${hours}:${minutes}`
}

function formatFullDate(value) {
  const text = String(value || '')
  if (isIntraday.value) {
    const timestamp = timestampFromKLineDate(text)
    if (Number.isFinite(timestamp)) return dateTimeFromTimestamp(timestamp)
  }
  if (/^\d{13}$/.test(text)) {
    const d = new Date(Number(text))
    if (isIntraday.value) return `${d.getFullYear()}-${p2(d.getMonth()+1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`
    return dateFromTimestamp(Number(text), true)
  }
  if (/^\d{10}$/.test(text)) {
    const d = new Date(Number(text) * 1000)
    if (isIntraday.value) return `${d.getFullYear()}-${p2(d.getMonth()+1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`
    return dateFromTimestamp(Number(text) * 1000, true)
  }
  if (/^\d{4}-\d{2}-\d{2}/.test(text)) return text.slice(0, 10)
  if (/^\d{8}$/.test(text)) return `${text.slice(0, 4)}-${text.slice(4, 6)}-${text.slice(6, 8)}`
  return text || '--'
}

function p2(n) { return String(n).padStart(2, '0') }

function dateTimeFromTimestamp(timestamp) {
  const d = new Date(timestamp)
  if (Number.isNaN(d.getTime())) return '--'
  return `${d.getFullYear()}-${p2(d.getMonth()+1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`
}

function dateFromTimestamp(timestamp, includeYear) {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return '--'
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  if (!includeYear) return `${month}-${day}`
  return `${date.getFullYear()}-${month}-${day}`
}

function formatGeneratedAt(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return `生成 ${date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })}`
}

function toneClass(value) {
  if (!Number.isFinite(value) || value === 0) return 'neutral'
  return value > 0 ? 'up' : 'down'
}

function uniqueIndexes(indexes) {
  return [...new Set(indexes.filter(index => index >= 0))]
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}
</script>

<style scoped>
.kline-panel {
  width: min(800px, 100%);
  margin: 0 0 16px;
  overflow: hidden;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.panel-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: start;
  padding: 16px 18px 14px;
  border-bottom: 1px solid var(--border-soft);
  background: var(--surface-raised);
}

.identity-block {
  min-width: 0;
  display: grid;
  gap: 5px;
}

.panel-eyebrow {
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 820;
  letter-spacing: 0;
  text-transform: uppercase;
}

.symbol-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.symbol-row strong {
  color: #1f2924;
  font-size: 25px;
  font-weight: 820;
  line-height: 1;
}

.symbol-row span {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
  border: 1px solid rgba(15, 125, 99, 0.22);
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
  font-weight: 760;
}

.identity-block p {
  max-width: 560px;
  margin: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price-summary {
  min-width: 134px;
  display: grid;
  justify-items: end;
  gap: 3px;
  padding-left: 16px;
  border-left: 1px solid var(--border-soft);
}

.price-summary span {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 720;
}

.price-summary strong {
  color: var(--text-primary);
  font-family: "Aptos", "Segoe UI", sans-serif;
  font-size: 29px;
  font-weight: 820;
  line-height: 1;
}

.price-summary em {
  font-style: normal;
  font-size: 12px;
  font-weight: 780;
}

.price-summary.up strong,
.price-summary.up em,
.stat-item.up strong,
.chart-tooltip.up .change-value {
  color: var(--positive);
}

.price-summary.down strong,
.price-summary.down em,
.stat-item.down strong,
.chart-tooltip.down .change-value {
  color: var(--negative);
}

.panel-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 18px 6px;
}

.range-tabs {
  display: inline-flex;
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
}

.range-tabs button {
  min-width: 48px;
  min-height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 760;
}

.range-tabs button:hover {
  background: var(--panel-hover);
  color: var(--accent);
}

.range-tabs button.active {
  background: var(--accent);
  color: #ffffff;
  box-shadow: 0 8px 16px rgba(15, 125, 99, 0.18);
}

.indicator-legend {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 720;
}

.indicator-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.legend-dot.candle-up {
  background: var(--positive);
}

.legend-dot.candle-down {
  background: var(--negative);
}

.legend-line {
  width: 18px;
  height: 0;
  border-top: 2px solid currentColor;
}

.legend-line.ma-5 {
  color: #2b6f9e;
}

.legend-line.ma-20 {
  color: #1f9d8e;
}

.legend-line.ma-60 {
  color: #6f5ca8;
}

.chart-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 180px;
  margin: 6px 12px 0;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-muted);
  font-size: 13px;
}

.intraday-error {
  color: var(--negative, #ef4444);
}

.chart-stage {
  position: relative;
  margin: 6px 12px 0;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  overflow: hidden;
}

.chart-svg {
  display: block;
  width: 100%;
  aspect-ratio: 860 / 430;
  cursor: crosshair;
  touch-action: none;
  user-select: none;
}

.chart-svg.zoomable {
  cursor: grab;
}

.chart-svg.panning {
  cursor: grabbing;
}

.plot-paper {
  fill: url(#klinePanelPaper);
}

.volume-band {
  fill: url(#klineVolumeFade);
}

.grid-line {
  stroke: var(--border-soft);
  stroke-width: 1;
  opacity: 0.8;
}

.grid-line.vertical {
  stroke-dasharray: 3 8;
  opacity: 0.4;
}

.axis-line {
  stroke: var(--border-strong);
  stroke-width: 1;
  opacity: 0.6;
}

.y-label,
.x-label {
  fill: var(--text-muted);
  font-size: 11px;
  font-weight: 500;
}

.candle-wick {
  stroke-width: 1.45;
  stroke-linecap: round;
}

.candle-body.up,
.candle-wick.up {
  fill: var(--positive);
  stroke: var(--positive);
}

.candle-body.down,
.candle-wick.down {
  fill: var(--negative);
  stroke: var(--negative);
}

.volume-bar {
  opacity: 0.4;
}

.volume-bar.up {
  fill: var(--positive);
}

.volume-bar.down {
  fill: var(--negative);
}

.ma-line,
.volume-average-line {
  fill: none;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.ma-line.ma-5 {
  stroke: #3b82f6; /* 统一使用科技蓝 */
}

.ma-line.ma-20 {
  stroke: #10b981; /* 统一使用翡翠绿 */
}

.ma-line.ma-60 {
  stroke: #8b5cf6; /* 统一使用科技紫 */
}

.volume-average-line {
  stroke: var(--text-muted);
  stroke-dasharray: 3 5;
  opacity: 0.5;
}

.latest-price line {
  stroke: var(--accent);
  stroke-dasharray: 5 5;
  stroke-width: 1;
}

.latest-price rect {
  fill: var(--accent);
}

.latest-price text {
  fill: #ffffff;
  font-size: 11px;
  font-weight: 600;
}

.crosshair line {
  stroke: var(--text-muted);
  stroke-dasharray: 5 5;
  stroke-width: 1;
  opacity: 0.6;
}

.crosshair circle {
  fill: var(--surface);
  stroke: var(--accent);
  stroke-width: 2;
}

.chart-tooltip {
  position: absolute;
  z-index: 10;
  min-width: 190px;
  padding: 10px 12px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  background: var(--surface);
  box-shadow: var(--shadow-command);
  color: var(--text-secondary);
  pointer-events: none;
  transform: translate(0, -4px);
  backdrop-filter: blur(8px);
  transition: background-color 0.3s ease, border-color 0.3s ease;
}

.chart-tooltip strong {
  display: block;
  margin-bottom: 7px;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
}

.chart-tooltip dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px 12px;
  margin: 0;
}

.chart-tooltip div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.chart-tooltip dt,
.chart-tooltip dd {
  margin: 0;
  font-size: 11px;
  line-height: 1.2;
}

.chart-tooltip dt {
  color: var(--text-muted);
  font-weight: 500;
}

.chart-tooltip dd {
  color: var(--text-primary);
  font-weight: 600;
}

.stat-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin: 16px 0 0;
  border-top: 1px solid var(--border-soft);
  border-bottom: 1px solid var(--border-soft);
  background: var(--surface-raised);
  transition: all 0.3s ease;
}

.stat-item {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid var(--border-soft);
}

.stat-item:last-child {
  border-right: 0;
}

.stat-item span,
.stat-item small {
  display: block;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat-item strong {
  display: block;
  margin: 4px 0 3px;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 17px;
  font-weight: 700;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.panel-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  padding: 12px 14px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.4;
}

.panel-footer span {
  min-width: 0;
  overflow-wrap: anywhere;
}

@media (max-width: 760px) {
  .kline-panel {
    width: 100%;
  }

  .panel-hero {
    grid-template-columns: 1fr;
    gap: 12px;
    padding: 14px;
  }

  .price-summary {
    justify-items: start;
    padding: 10px 0 0;
    border-top: 1px solid var(--border-soft);
    border-left: 0;
  }

  .identity-block p {
    white-space: normal;
  }

  .panel-controls {
    align-items: stretch;
    flex-direction: column;
    padding: 10px 12px 6px;
  }

  .range-tabs {
    width: 100%;
  }

  .range-tabs button {
    flex: 1;
    min-width: 0;
  }

  .indicator-legend {
    justify-content: flex-start;
  }

  .chart-stage {
    margin: 6px 8px 0;
  }

  .chart-svg {
    min-height: 310px;
  }

  .stat-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .stat-item:nth-child(2n) {
    border-right: 0;
  }
}

@media (max-width: 520px) {
  .stat-strip {
    grid-template-columns: 1fr;
  }

  .stat-item {
    border-right: 0;
    border-bottom: 1px solid var(--border-soft);
  }

  .stat-item:last-child {
    border-bottom: 0;
  }

  .chart-tooltip {
    min-width: 168px;
  }
}
</style>
