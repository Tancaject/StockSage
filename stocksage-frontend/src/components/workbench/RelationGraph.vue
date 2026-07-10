<template>
  <div class="panel relation-panel">
    <div class="panel-header">
      <div>
        <h2>{{ ticker }} 竞争 · 供应链</h2>
      </div>
      <div class="header-actions">
        <!-- 缩放控制浮窗 -->
        <div v-if="!loading && !error && !extracting && !isEmpty" class="canvas-controls">
          <button class="control-btn" type="button" title="放大" @click="zoomIn">
            <el-icon><Plus /></el-icon>
          </button>
          <button class="control-btn" type="button" title="缩小" @click="zoomOut">
            <el-icon><Minus /></el-icon>
          </button>
          <button class="control-btn" type="button" title="自适应复位" @click="resetZoom">
            <el-icon><Aim /></el-icon>
          </button>
        </div>
        <button class="quiet-button" type="button" :disabled="loading" @click="load()">
          <el-icon><Refresh /></el-icon>
          <span>{{ loading ? '读取中' : '刷新' }}</span>
        </button>
      </div>
    </div>

    <div v-if="loading" class="relation-placeholder">正在读取 {{ ticker }} 的关系图谱…</div>
    <div v-else-if="error" class="relation-placeholder relation-error">{{ error }}</div>
    <div v-else-if="extracting" class="relation-placeholder relation-extracting">
      <span class="extract-spinner"></span>
      <div class="extract-info">
        <span v-if="progressPhase === 'ingesting'">正在入库 {{ ticker }} 最新 10-K…</span>
        <span v-else-if="progressTotal > 0">
          正在抽取 {{ ticker }} 关系图谱（{{ progressProcessed }}/{{ progressTotal }} 切片，已发现 {{ progressAccepted }} 条关系）
        </span>
        <span v-else>正在从 {{ ticker }} 最新 10-K 抽取关系图谱…</span>
        <div v-if="progressTotal > 0" class="extract-progress-bar">
          <div class="extract-progress-fill" :style="{ width: progressPct + '%' }"></div>
        </div>
      </div>
    </div>
    <div v-else-if="isEmpty" class="relation-empty">
      <p class="empty-copy">
        {{ attempted
          ? `没能为 ${ticker} 生成关系图谱——该标的可能暂无可用的 SEC 10-K（仅支持美股），或抽取未产出可取证的关系。`
          : `还没有 ${ticker} 的关系图谱。点下方按钮，从最新 10-K 抽取竞争对手与供应链，每条边都能下钻到原文证据。` }}
      </p>
      <button class="primary-button" type="button" @click="generate">
        <el-icon><MagicStick /></el-icon>
        <span>{{ attempted ? '重试生成' : '生成关系图谱' }}</span>
      </button>
    </div>

    <template v-else>
      <div class="relation-legend">
        <span
          v-for="item in legend"
          :key="item.type"
          class="legend-item"
          :class="{ disabled: !activeTypes.includes(item.type) }"
          @click="toggleType(item.type)"
        >
          <i class="legend-dot" :style="{ background: item.color }"></i>
          <span class="legend-label">{{ item.label }}</span>
          <span class="legend-count">{{ item.count }}</span>
        </span>
        <button v-if="activeTypes.length < 4" class="legend-reset" @click="resetFilters">显示全部</button>
      </div>

      <!-- 画布容器 -->
      <div
        class="relation-canvas"
        :class="{ dragging: isDragging }"
        @mousedown="handleMouseDown"
        @mousemove="handleMouseMove"
        @mouseup="handleMouseUp"
        @mouseleave="handleMouseUp"
        @wheel="handleWheel"
      >
        <svg :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" class="relation-svg" @click="clearSelection">
          <!-- 背景网格底纹 -->
          <defs>
            <pattern id="grid" width="30" height="30" patternUnits="userSpaceOnUse">
              <path d="M 30 0 L 0 0 0 30" fill="none" stroke="var(--border-soft)" stroke-width="0.5" opacity="0.3" />
            </pattern>
            <!-- 渐变色定义 -->
            <radialGradient id="centerGlow" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stop-color="var(--accent)" stop-opacity="1" />
              <stop offset="100%" stop-color="var(--accent-dark)" stop-opacity="0.8" />
            </radialGradient>
          </defs>

          <!-- 绘制背景 -->
          <rect width="100%" height="100%" fill="url(#grid)" pointer-events="none" />

          <!-- 可缩放/平移的内容分组 -->
          <g :transform="`translate(${pan.x}, ${pan.y}) scale(${scale})`" class="canvas-content-group">
            
            <!-- 关系连线 (底线) -->
            <path
              v-for="node in positioned"
              :key="`edge-${node.id}`"
              class="relation-edge"
              :class="{ active: selectedId === node.id, dimmed: selectedId && selectedId !== node.id }"
              :d="getEdgePath(node)"
              :style="{
                stroke: node.color,
                strokeOpacity: activeTypes.includes(node.type) ? (selectedId === node.id ? 0.95 : node.opacity * 0.7) : 0,
                strokeWidth: selectedId === node.id ? 2.6 : 1.5,
                strokeDasharray: node.dashed ? '4 5' : null,
                pointerEvents: 'none'
              }"
            />

            <!-- 关系连线 (发光粒子流) -->
            <path
              v-for="node in positioned"
              :key="`edge-flow-${node.id}`"
              class="relation-edge-flow"
              :class="{ active: selectedId === node.id, dimmed: selectedId && selectedId !== node.id }"
              :d="getEdgePath(node)"
              :style="{
                stroke: node.color,
                strokeWidth: selectedId === node.id ? 3 : 1.8,
                strokeOpacity: activeTypes.includes(node.type) ? (selectedId === node.id ? 1 : 0.45) : 0,
                animationDuration: selectedId === node.id ? '0.7s' : '2s'
              }"
            />

            <!-- 中心节点 (多层科技感旋转盘) -->
            <g class="center-group">
              <!-- 最外圈虚线旋转刻度环 -->
              <circle :cx="CX" :cy="CY" :r="CENTER_R + 16" class="center-rotate" />
              <!-- 中间层半透明呼吸光晕 -->
              <circle :cx="CX" :cy="CY" :r="CENTER_R + 8" class="center-breathe" />
              <!-- 内层实心核心 -->
              <circle :cx="CX" :cy="CY" :r="CENTER_R" class="center-node" />
              <!-- Ticker 文字 -->
              <text :x="CX" :y="CY" class="center-label" text-anchor="middle" dominant-baseline="central">
                {{ center.ticker }}
              </text>
            </g>

            <!-- 子关系节点 -->
            <g
              v-for="node in positioned"
              :key="`node-${node.id}`"
              class="relation-node"
              :class="{
                active: selectedId === node.id,
                dimmed: selectedId && selectedId !== node.id
              }"
              :style="{
                transform: `translate(${animateIn ? node.x - CX : 0}px, ${animateIn ? node.y - CY : 0}px)`,
                opacity: activeTypes.includes(node.type) ? (animateIn ? 1 : 0) : 0,
                pointerEvents: activeTypes.includes(node.type) && animateIn ? 'auto' : 'none'
              }"
              @click.stop="select(node)"
            >
              <title>{{ node.label }} (双击或悬浮查看详情)</title>
              
              <!-- 节点小圆圈，根据各自类型上色 -->
              <circle
                :cx="CX"
                :cy="CY"
                :r="selectedId === node.id ? 8 : 6.2"
                :style="{ fill: node.color, color: node.color }"
                class="node-circle"
              />
              
              <!-- 节点名称标签 -->
              <text
                :x="node.labelX - node.x + CX"
                :y="CY"
                :text-anchor="node.labelAnchor"
                dominant-baseline="central"
                class="node-label"
              >
                {{ truncate(shortName(node.label), 22) }}
              </text>
            </g>
          </g>
        </svg>
      </div>

      <!-- 底部证据卡片 -->
      <div v-if="selectedNode" class="relation-evidence animate-card">
        <div class="evidence-body">
          <div class="evidence-main-info">
            <div class="evidence-head">
              <span class="status-pill" :class="toneOf(selectedNode.type)">{{ metaLabel(selectedNode.type) }}</span>
              <strong>{{ selectedNode.label }}</strong>
            </div>
            <p class="evidence-snippet">“{{ selectedNode.snippet }}”</p>
            <div class="evidence-source">
              <span v-if="selectedNode.section" class="source-tag">{{ selectedNode.section }}</span>
              <span v-if="selectedNode.accession" class="source-tag">ACCESSION: {{ selectedNode.accession }}</span>
              <span v-if="selectedNode.filingDate" class="source-tag">DATE: {{ selectedNode.filingDate }}</span>
            </div>
          </div>

          <!-- 置信度环形图 -->
          <div v-if="selectedNode.confidence != null" class="evidence-confidence-card">
            <div class="confidence-ring-container">
              <svg class="confidence-ring" width="56" height="56" viewBox="0 0 36 36">
                <!-- 灰色背景圆环 -->
                <circle class="ring-bg" cx="18" cy="18" r="15" />
                <!-- 发光指示圈 -->
                <circle
                  class="ring-indicator"
                  :style="{ color: selectedNode.color }"
                  cx="18"
                  cy="18"
                  r="15"
                  stroke-dasharray="94.2"
                  :stroke-dashoffset="94.2 - (94.2 * selectedNode.confidence)"
                />
              </svg>
              <span class="confidence-pct">{{ Math.round(selectedNode.confidence * 100) }}%</span>
            </div>
            <span class="confidence-label">置信度</span>
          </div>
        </div>
      </div>
      <p v-else class="relation-hint">点击任一节点，查看支撑该竞争与供应链关系的 10-K 原文片段与出处。</p>
    </template>
  </div>
</template>

<script setup>
/**
 * RelationGraph — 公司关系图谱（手写 SVG 径向 ego-graph 升级版）
 *
 * 在原有一圈两环对称布局基础上：
 * 1. 采用二次贝塞尔曲线代替生硬直线；
 * 2. 引入流动粒子特效 (stroke-dashoffset 虚线动画)；
 * 3. 引入首入场从中心“爆开”绽放的过渡动效；
 * 4. 优化中心节点为多层慢速旋转+呼吸刻度盘；
 * 5. 全面支持 Canvas 鼠标拖动与滚轮缩放，并提供悬浮控制面板；
 * 6. 支持 Legend 标签点击过滤，对应节点做淡入淡出过渡。
 *
 * props:
 *   ticker {String} 当前标的，组件自取 /api/workbench/stocks/{ticker}/relations
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { MagicStick, Refresh, Plus, Minus, Aim } from '@element-plus/icons-vue'
import { fetchStockRelations, refreshStockRelations } from '../../api/workbench.js'

const props = defineProps({
  ticker: { type: String, required: true },
})

const WIDTH = 820
const HEIGHT = 560
const CX = 410
const CY = 280
const CENTER_R = 30
const COL_GAP = 125
const BOW = 76
const TOP_Y = 46
const BOTTOM_Y = HEIGHT - 46

const TYPE_ORDER = ['COMPETITOR', 'SUPPLIER', 'CUSTOMER', 'PARTNER']
const TYPE_META = {
  COMPETITOR: { label: '竞争对手', color: 'var(--negative, #ef4444)', tone: 'danger' },
  SUPPLIER: { label: '供应商', color: 'var(--info, #3b82f6)', tone: 'running' },
  CUSTOMER: { label: '客户', color: 'var(--positive, #10b981)', tone: 'positive' },
  PARTNER: { label: '合作', color: 'var(--warning, #f59e0b)', tone: 'sample' },
}

const loading = ref(false)
const error = ref('')
const graph = ref(null)
const selectedId = ref('')

// 绽放式动画控制
const animateIn = ref(false)

// 缩放与平移 (Zoom & Pan)
const scale = ref(1)
const pan = ref({ x: 0, y: 0 })
const isDragging = ref(false)
const dragStart = ref({ x: 0, y: 0 })

// 分类筛选过滤器，默认显示全部
const activeTypes = ref(['COMPETITOR', 'SUPPLIER', 'CUSTOMER', 'PARTNER'])

// 异步抽取状态
const extracting = ref(false)
const attempted = ref(false)
const POLL_MS = 15000
const MAX_POLLS = 40
let pollTimer = null
let pollCount = 0

const center = computed(() => graph.value?.center || { ticker: props.ticker, label: props.ticker })
const isEmpty = computed(() => !graph.value || graph.value.empty)

const progressPhase = computed(() => graph.value?.progress?.phase || '')
const progressProcessed = computed(() => graph.value?.progress?.processed || 0)
const progressTotal = computed(() => graph.value?.progress?.total || 0)
const progressAccepted = computed(() => graph.value?.progress?.accepted || 0)
const progressPct = computed(() => progressTotal.value > 0 ? Math.round((progressProcessed.value / progressTotal.value) * 100) : 0)

// 计算贝塞尔曲线的 path 属性
function getEdgePath(node) {
  if (!animateIn.value) {
    return `M ${CX} ${CY} Q ${CX} ${CY} ${CX} ${CY}`
  }
  // 计算二次贝塞尔曲线的控制点：在 X 方向缩短，Y 方向靠向终点，形成先挺直再拐弯的优雅抛物线
  const ctrlX = CX + (node.x - CX) * 0.35
  const ctrlY = CY + (node.y - CY) * 0.85
  return `M ${CX} ${CY} Q ${ctrlX} ${ctrlY} ${node.x} ${node.y}`
}

// 左右两列垂直扇形布局
const positioned = computed(() => {
  const nodes = graph.value?.nodes || []
  if (!nodes.length) return []

  const bySide = { right: [], left: [] }
  for (const node of nodes) {
    const side = TYPE_ORDER.indexOf(node.type) % 2 === 0 ? 'right' : 'left'
    bySide[side].push(node)
  }

  const placed = []
  for (const side of ['right', 'left']) {
    const group = bySide[side].sort((a, b) => (b.confidence || 0) - (a.confidence || 0))
    const total = group.length
    group.forEach((node, index) => {
      const fraction = total === 1 ? 0.5 : (index + 0.5) / total
      const y = TOP_Y + fraction * (BOTTOM_Y - TOP_Y)
      const bow = Math.sin(fraction * Math.PI) * BOW
      const x = side === 'right' ? CX + COL_GAP + bow : CX - COL_GAP - bow
      const meta = TYPE_META[node.type] || { color: 'var(--text-muted)' }
      placed.push({
        ...node,
        x,
        y,
        labelX: side === 'right' ? x + 12 : x - 12,
        labelAnchor: side === 'right' ? 'start' : 'end',
        color: meta.color,
        opacity: Math.min(1, 0.45 + 0.5 * (node.confidence || 0)),
        dashed: (node.confidence || 0) < 0.6,
      })
    })
  }
  return placed
})

const legend = computed(() => {
  const counts = graph.value?.counts || {}
  return TYPE_ORDER
    .filter((type) => counts[type])
    .map((type) => ({
      type,
      label: TYPE_META[type]?.label || type,
      color: TYPE_META[type]?.color || 'var(--text-muted)',
      count: counts[type],
    }))
})

const selectedNode = computed(() => positioned.value.find((node) => node.id === selectedId.value) || null)

// Legend 过滤逻辑
function toggleType(type) {
  const idx = activeTypes.value.indexOf(type)
  if (idx > -1) {
    if (activeTypes.value.length > 1) {
      activeTypes.value.splice(idx, 1)
    }
  } else {
    activeTypes.value.push(type)
  }
}

function resetFilters() {
  activeTypes.value = ['COMPETITOR', 'SUPPLIER', 'CUSTOMER', 'PARTNER']
}

// 缩放平移交互逻辑
function handleMouseDown(e) {
  if (e.target.closest('.relation-node') || e.target.closest('.center-group')) {
    return
  }
  isDragging.value = true
  dragStart.value = { x: e.clientX - pan.value.x, y: e.clientY - pan.value.y }
}

function handleMouseMove(e) {
  if (!isDragging.value) return
  pan.value.x = e.clientX - dragStart.value.x
  pan.value.y = e.clientY - dragStart.value.y
}

function handleMouseUp() {
  isDragging.value = false
}

function handleWheel(e) {
  e.preventDefault()
  const zoomIntensity = 0.04
  const svgRect = e.currentTarget.getBoundingClientRect()
  const mouseX = e.clientX - svgRect.left
  const mouseY = e.clientY - svgRect.top
  const zoomFactor = e.deltaY < 0 ? (1 + zoomIntensity) : (1 - zoomIntensity)
  const nextScale = Math.max(0.4, Math.min(3, scale.value * zoomFactor))
  
  pan.value.x = mouseX - (mouseX - pan.value.x) * (nextScale / scale.value)
  pan.value.y = mouseY - (mouseY - pan.value.y) * (nextScale / scale.value)
  scale.value = nextScale
}

function zoomIn() {
  const prevScale = scale.value
  const nextScale = Math.min(3, prevScale + 0.15)
  pan.value.x = CX - (CX - pan.value.x) * (nextScale / prevScale)
  pan.value.y = CY - (CY - pan.value.y) * (nextScale / prevScale)
  scale.value = nextScale
}

function zoomOut() {
  const prevScale = scale.value
  const nextScale = Math.max(0.4, prevScale - 0.15)
  pan.value.x = CX - (CX - pan.value.x) * (nextScale / prevScale)
  pan.value.y = CY - (CY - pan.value.y) * (nextScale / prevScale)
  scale.value = nextScale
}

function resetZoom() {
  scale.value = 1
  pan.value = { x: 0, y: 0 }
}

function select(node) {
  selectedId.value = selectedId.value === node.id ? '' : node.id
}

function clearSelection() {
  selectedId.value = ''
}

function metaLabel(type) {
  return TYPE_META[type]?.label || type
}

function toneOf(type) {
  return TYPE_META[type]?.tone || 'pending'
}

function truncate(label, max = 18) {
  const text = String(label || '')
  return text.length > max ? `${text.slice(0, max - 1)}…` : text
}

const LEGAL_SUFFIX = /[,，]?\s*(Incorporated|Inc\.?|Corporation|Corp\.?|Co\.?\s*,?\s*Ltd\.?|Company\s+Limited|Company|Limited|Ltd\.?|LLC|PLC|Holdings)\.?\s*$/i

function shortName(name) {
  let text = String(name || '').trim()
  for (let i = 0; i < 2; i++) {
    const stripped = text.replace(LEGAL_SUFFIX, '').replace(/[,，]\s*$/, '').trim()
    if (stripped === text || stripped.length === 0) break
    text = stripped
  }
  return text || String(name || '')
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  pollCount = 0
}

function startPolling() {
  stopPolling()
  extracting.value = true
  pollTimer = setInterval(() => {
    pollCount += 1
    if (pollCount > MAX_POLLS) {
      stopPolling()
      extracting.value = false
      attempted.value = true
      return
    }
    load({ silent: true })
  }, POLL_MS)
}

async function load({ silent = false } = {}) {
  const symbol = String(props.ticker || '').trim()
  if (!symbol) return
  if (!silent) {
    loading.value = true
    error.value = ''
    selectedId.value = ''
    animateIn.value = false
  }
  try {
    const data = await fetchStockRelations(symbol)
    graph.value = data
    const hasNodes = (data.nodes || []).length > 0
    if (hasNodes) {
      stopPolling()
      extracting.value = false
      // 成功加载后激活动画
      setTimeout(() => {
        animateIn.value = true
      }, 50)
    } else if (data.extracting) {
      if (!pollTimer) startPolling()
      else extracting.value = true
    } else if (pollTimer) {
      stopPolling()
      extracting.value = false
      attempted.value = true
    } else {
      extracting.value = false
    }
  } catch (err) {
    if (!silent) {
      error.value = err.message || '读取关系图谱失败'
      graph.value = null
    }
  } finally {
    if (!silent) loading.value = false
  }
}

async function generate() {
  const symbol = String(props.ticker || '').trim()
  if (!symbol) return
  attempted.value = true
  error.value = ''
  try {
    await refreshStockRelations(symbol)
    startPolling()
    load({ silent: true })
  } catch (err) {
    error.value = err.message || '触发抽取失败'
    extracting.value = false
  }
}

function resetAndLoad() {
  stopPolling()
  extracting.value = false
  attempted.value = false
  graph.value = null
  selectedId.value = ''
  resetZoom()
  load()
}

onMounted(() => load())
watch(() => props.ticker, resetAndLoad)
onBeforeUnmount(stopPolling)
</script>

<style scoped>
/* 头部面板修饰 */
.header-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* Canvas 控制条样式 */
.canvas-controls {
  display: flex;
  align-items: center;
  gap: 4px;
  background: var(--surface-raised);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  padding: 2px;
}

.control-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.control-btn:hover {
  background: var(--surface);
  color: var(--text-primary);
  box-shadow: var(--shadow-soft);
}

/* Legend 美化与交互 */
.relation-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 4px 2px 12px;
  user-select: none;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-primary);
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--border-soft);
  padding: 4px 10px;
  border-radius: 99px;
  font-weight: 500;
  transition: all 0.15s ease;
}

.legend-item:hover {
  border-color: var(--border-strong);
}

.legend-item.disabled {
  opacity: 0.45;
  background: transparent;
  border-color: var(--border-soft);
  color: var(--text-muted);
}

.legend-item.disabled .legend-dot {
  background: var(--text-muted) !important;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.legend-count {
  font-size: 10px;
  background: var(--border-soft);
  color: var(--text-secondary);
  border-radius: 8px;
  padding: 1px 6px;
  font-weight: 600;
}

.legend-item.disabled .legend-count {
  background: transparent;
}

.legend-reset {
  font-size: 11px;
  color: var(--accent);
  background: transparent;
  border: none;
  cursor: pointer;
  font-weight: 600;
  padding: 2px 6px;
  transition: color 0.15s ease;
}

.legend-reset:hover {
  color: var(--accent-dark);
}

/* 画布容器 */
.relation-canvas {
  width: 100%;
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  background: var(--surface);
  box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.02);
  overflow: hidden;
  position: relative;
  cursor: grab;
}

.relation-canvas.dragging {
  cursor: grabbing;
}

.relation-svg {
  width: 100%;
  height: auto;
  display: block;
}

/* 绘图内容的分组样式，加上平滑过渡支持缩放与拖拽 */
.canvas-content-group {
  transition: transform 0.1s cubic-bezier(0.1, 0.8, 0.3, 1);
}

/* 关系连线底色线 */
.relation-edge {
  fill: none;
  transition: d 0.8s cubic-bezier(0.34, 1.56, 0.64, 1), stroke-opacity 0.3s ease, stroke-width 0.2s;
}

.relation-edge.dimmed {
  stroke-opacity: 0.05 !important;
}

/* 发光粒子流特效线 */
.relation-edge-flow {
  fill: none;
  stroke-dasharray: 6 20;
  stroke-linecap: round;
  animation: flow-run 2s linear infinite;
  pointer-events: none;
  transition: d 0.8s cubic-bezier(0.34, 1.56, 0.64, 1), stroke-opacity 0.3s ease, stroke-width 0.2s;
}

.relation-edge-flow.dimmed {
  stroke-opacity: 0.03 !important;
}

@keyframes flow-run {
  to {
    stroke-dashoffset: -26;
  }
}

/* 科技感中心节点环 */
.center-rotate {
  fill: none;
  stroke: var(--accent);
  stroke-opacity: 0.35;
  stroke-width: 1.5;
  stroke-dasharray: 4 6;
  transform-origin: 410px 280px; /* 以中心 CX, CY 旋转 */
  animation: rotate-clockwise 24s linear infinite;
}

.center-breathe {
  fill: var(--accent);
  fill-opacity: 0.15;
  transform-origin: 410px 280px;
  animation: pulse-glow 3s infinite ease-in-out;
}

.center-node {
  fill: url(#centerGlow);
  stroke: var(--surface);
  stroke-width: 3.5;
  filter: drop-shadow(0 4px 10px rgba(5, 150, 105, 0.25));
}

.center-label {
  fill: #ffffff;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.5px;
}

@keyframes rotate-clockwise {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@keyframes pulse-glow {
  0% { transform: scale(1); fill-opacity: 0.15; }
  50% { transform: scale(1.18); fill-opacity: 0.06; }
  100% { transform: scale(1); fill-opacity: 0.15; }
}

/* 子关系节点 */
.relation-node {
  cursor: pointer;
  transform-origin: 410px 280px; /* 原点位于中心，方便入场弹射动画计算 */
  transition: transform 0.8s cubic-bezier(0.34, 1.56, 0.64, 1), opacity 0.4s ease;
}

.node-circle {
  stroke: var(--surface);
  stroke-width: 2.2;
  transition: r 0.2s ease, filter 0.2s ease, stroke-width 0.2s;
}

.relation-node:hover .node-circle,
.relation-node.active .node-circle {
  stroke-width: 2.8;
  filter: drop-shadow(0 0 7px currentColor);
}

.relation-node .node-label {
  fill: var(--text-secondary);
  font-size: 11px;
  font-weight: 500;
  transition: fill 0.15s ease, font-size 0.15s ease;
}

.relation-node:hover .node-label,
.relation-node.active .node-label {
  fill: var(--text-primary);
  font-size: 11.5px;
  font-weight: 600;
}

.relation-node.dimmed {
  opacity: 0.25 !important;
}

/* 占位符与加载状态 */
.relation-placeholder {
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 13px;
}

.relation-error {
  color: var(--negative, #ef4444);
}

.relation-extracting {
  gap: 10px;
  color: var(--text-secondary);
  flex-direction: column;
  align-items: center;
}

.extract-info {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  max-width: 400px;
  text-align: center;
}

.extract-progress-bar {
  width: 100%;
  height: 4px;
  background: var(--border-soft);
  border-radius: 2px;
  overflow: hidden;
}

.extract-progress-fill {
  height: 100%;
  background: var(--accent);
  border-radius: 2px;
  transition: width 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

.extract-spinner {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--border-soft);
  border-top-color: var(--accent);
  animation: relation-spin 0.8s linear infinite;
}

@keyframes relation-spin {
  to { transform: rotate(360deg); }
}

.relation-empty {
  min-height: 120px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  text-align: center;
  padding: 8px 12px;
}

.relation-empty .empty-copy {
  margin: 0;
  max-width: 440px;
  font-size: 13px;
  color: var(--text-secondary);
}

/* 底部证据卡片优化 (Glassmorphism 毛玻璃卡片) */
.relation-evidence {
  margin-top: 16px;
  padding: 16px 20px;
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(14px) saturate(125%);
  -webkit-backdrop-filter: blur(14px) saturate(125%);
  box-shadow: var(--shadow-soft);
  position: relative;
  transition: all 0.15s ease;
}

.dark .relation-evidence {
  background: rgba(17, 24, 39, 0.72);
  backdrop-filter: blur(14px) saturate(120%);
  -webkit-backdrop-filter: blur(14px) saturate(120%);
}

.animate-card {
  animation: card-slide-in 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes card-slide-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.evidence-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.evidence-main-info {
  flex: 1;
  min-width: 0;
}

.evidence-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.evidence-head strong {
  color: var(--text-primary);
  font-size: 15px;
  letter-spacing: -0.01em;
}

.evidence-snippet {
  margin: 0 0 10px;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
  font-style: italic;
}

.evidence-source {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.source-tag {
  font-size: 11px;
  color: var(--text-secondary);
  background: var(--surface-raised);
  border: 1px solid var(--border-soft);
  padding: 2px 8px;
  border-radius: 6px;
  font-weight: 500;
}

/* 置信度圆环 */
.evidence-confidence-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 10px 14px;
  background: var(--surface-raised);
  border-radius: 12px;
  border: 1px solid var(--border-soft);
  min-width: 82px;
}

.confidence-ring-container {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.confidence-ring {
  transform: rotate(-90deg);
}

.ring-bg {
  fill: none;
  stroke: var(--border-soft);
  stroke-width: 3.5;
}

.ring-indicator {
  fill: none;
  stroke: currentColor;
  stroke-width: 3.5;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.8s cubic-bezier(0.16, 1, 0.3, 1);
  filter: drop-shadow(0 0 3px currentColor);
}

.confidence-pct {
  position: absolute;
  font-size: 13px;
  font-weight: 700;
  color: var(--text-primary);
}

.confidence-label {
  font-size: 10px;
  color: var(--text-muted);
  font-weight: 700;
  margin-top: 6px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.relation-hint {
  margin: 16px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}
</style>
