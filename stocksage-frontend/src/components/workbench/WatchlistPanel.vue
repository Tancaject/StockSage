<template>
  <div class="panel watchlist-panel">
    <div class="panel-header">
      <div>
        <h2>观察列表</h2>
      </div>
    </div>

    <!-- 市场分类选择器 -->
    <el-tabs v-model="activeMarketTab" class="market-filter-tabs">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="美港股" name="us_hk" />
      <el-tab-pane label="A股" name="a_share" />
    </el-tabs>



    <TransitionGroup name="watchlist-flip" tag="div" class="watchlist">
      <button
        v-for="(item, index) in filteredItems"
        :key="item.ticker"
        class="watch-row"
        :class="{ 
          active: selected === item.ticker,
          'is-dragging': dragIndex === index
        }"
        type="button"
        draggable="true"
        @click="emit('select', item.ticker)"
        @dragstart="handleDragStart(index, $event)"
        @dragenter="handleDragEnter(index, $event)"
        @dragover.prevent
        @dragend="handleDragEnd"
      >
        <div class="watch-row-indicator"></div>
        <span class="watch-main">
          <strong>{{ item.ticker }}</strong>
          <small v-if="item.name">{{ item.name }}</small>
        </span>
        <span class="watch-quote num">
          <strong v-if="hasQuote(item)">{{ formatRowPrice(item.price) }}</strong>
          <small
            v-if="hasQuote(item)"
            :class="Number(item.changePercent) >= 0 ? 'quote-up' : 'quote-down'"
          >{{ formatRowChange(item.changePercent) }}</small>
          <small v-else class="quote-pending">—</small>
        </span>
        <span class="remove-btn" draggable="false" @click.stop="emit('remove', item.ticker)">
          <el-icon><Close /></el-icon>
        </span>
      </button>
    </TransitionGroup>
  </div>
</template>

<script setup>
/**
 * WatchlistPanel — 观察列表面板
 *
 * props:
 *   items    {Array<{ticker, status, lastAction}>}  watchlist 行
 *   selected {String}                               当前选中 ticker
 *
 * emits:
 *   select(ticker)   — 用户点击列表行
 *   add(ticker)      — 用户提交加标的表单（传原始字符串，父级负责 normalizeTicker）
 *   remove(ticker)   — 移除列表中的指定股票
 */
import { computed, ref } from 'vue'
import { Close } from '@element-plus/icons-vue'

const props = defineProps({
  items: {
    type: Array,
    default: () => [],
  },
  selected: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['select', 'remove', 'update:items'])

// 拖拽排序逻辑
const dragIndex = ref(null)
const isDragging = ref(false)

const handleDragStart = (index, event) => {
  if (event.target.closest('.remove-btn')) {
    event.preventDefault()
    return
  }
  
  dragIndex.value = index
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData('text/plain', filteredItems.value[index].ticker)
  
  // 延迟设置 isDragging，以使浏览器生成的拖动镜像保持原样而非半透明占位
  setTimeout(() => {
    isDragging.value = true
  }, 0)
}

const handleDragEnter = (targetIndex, event) => {
  if (dragIndex.value === null || dragIndex.value === targetIndex) return

  const draggedTicker = filteredItems.value[dragIndex.value].ticker
  const targetTicker = filteredItems.value[targetIndex].ticker

  const fromIdx = props.items.findIndex(item => item.ticker === draggedTicker)
  const toIdx = props.items.findIndex(item => item.ticker === targetTicker)

  if (fromIdx !== -1 && toIdx !== -1) {
    const newItems = [...props.items]
    const [removed] = newItems.splice(fromIdx, 1)
    newItems.splice(toIdx, 0, removed)

    dragIndex.value = targetIndex
    emit('update:items', newItems)
  }
}

const handleDragEnd = () => {
  dragIndex.value = null
  isDragging.value = false
}

const activeMarketTab = ref('all')

/** 行内行情:批量行情接口就绪前 item 无 price 字段,显示占位"—",绝不编造数字 */
function hasQuote(item) {
  return Number.isFinite(Number(item?.price))
}

function formatRowPrice(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return '—'
  return num >= 1000 ? num.toFixed(1) : num.toFixed(2)
}

function formatRowChange(value) {
  const num = Number(value)
  if (!Number.isFinite(num)) return ''
  return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`
}

const filteredItems = computed(() => {
  if (activeMarketTab.value === 'all') return props.items
  return props.items.filter(item => {
    const ticker = String(item.ticker || '').toUpperCase()
    const isAShare = /^\d{6}$/.test(ticker) || ticker.endsWith('SH') || ticker.endsWith('SZ')
    if (activeMarketTab.value === 'a_share') {
      return isAShare
    } else {
      return !isAShare
    }
  })
})

</script>

<style scoped>
.market-filter-tabs :deep(.el-tabs__header) {
  margin: 0 0 12px 0;
}
.market-filter-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background-color: var(--border-soft);
}
.market-filter-tabs :deep(.el-tabs__item) {
  font-size: 12px;
  font-weight: 600;
  height: 32px;
  line-height: 32px;
  color: var(--text-secondary);
  padding: 0 8px;
}
.market-filter-tabs :deep(.el-tabs__item.is-active) {
  color: var(--accent);
}
.market-filter-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--accent);
  height: 2px;
}

/* Micro-Grid 自选股列表 */
.watchlist {
  display: grid;
  gap: 8px;
  margin-top: 4px;
}

.watch-row {
  position: relative;
  width: 100%;
  min-height: 36px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  cursor: grab;
  text-align: left;
  transition: all 0.15s ease;
  overflow: hidden;
}

.watch-row:active {
  cursor: grabbing;
}

.watch-row.is-dragging {
  opacity: 0.4;
  border-style: dashed;
  border-color: var(--border-strong);
  background: var(--surface-raised);
  transform: scale(0.98);
}

/* 列表项位置重排过渡（FLIP 动画） */
.watchlist-flip-move {
  transition: transform 0.4s cubic-bezier(0.2, 0.8, 0.2, 1);
}

.watch-row-indicator {
  position: absolute;
  left: 0;
  top: 15%;
  bottom: 15%;
  width: 3px;
  border-radius: 0 4px 4px 0;
  background: transparent;
  transition: background-color 0.2s ease;
}

.watch-row:hover {
  background: var(--surface-raised);
  border-color: var(--border-strong);
}

.watch-row.active {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.watch-row.active .watch-row-indicator {
  background: var(--accent);
}

.watch-main {
  min-width: 0;
  display: grid;
  gap: 1px;
}

.watch-main strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.watch-main small {
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.watch-quote {
  display: grid;
  gap: 1px;
  justify-items: end;
  text-align: right;
  transition: opacity 0.15s ease;
}

.watch-quote strong {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}

.watch-quote small {
  font-size: 11px;
  font-weight: 600;
}

.watch-quote .quote-up {
  color: var(--up);
}

.watch-quote .quote-down {
  color: var(--down);
}

.watch-quote .quote-pending {
  color: var(--text-muted);
  font-weight: 500;
}

/* hover 时行情列让位给移除按钮 */
.watch-row:hover .watch-quote {
  opacity: 0;
}

.watch-row .remove-btn {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-muted);
  opacity: 0;
  transition: all 0.2s ease;
}

.watch-row:hover .remove-btn {
  opacity: 1;
}

.watch-row .remove-btn:hover {
  background: var(--danger);
  color: #ffffff;
}

@media (max-width: 640px) {
  .ticker-search {
    width: 100%;
  }
}
</style>

