<template>
  <div class="cockpit-main">
    <div class="panel chart-cockpit-panel">
      <!-- 头部 -->
      <div class="panel-header">
        <div>
          <h2>
            {{ cockpit.ticker }} 行情与技术分析
            <button
              class="watchlist-star-btn"
              :class="{ active: inWatchlist }"
              type="button"
              :title="inWatchlist ? '移出自选' : '加入自选'"
              @click="emit('toggle-watchlist', cockpit.ticker)"
            >
              <el-icon><StarFilled v-if="inWatchlist" /><Star v-else /></el-icon>
              <span>{{ inWatchlist ? '已自选' : '加自选' }}</span>
            </button>
          </h2>
        </div>
        <button class="quiet-button" type="button" :disabled="loading" @click="emit('refresh')">
          <el-icon><Refresh /></el-icon>
          <span>{{ loading ? '读取中' : '刷新' }}</span>
        </button>
      </div>

      <!-- 行情报价卡片 -->
      <div v-if="cockpit.quote" class="ticker-quote-bar" :class="quoteTone">
        <div class="quote-price-box">
          <span class="label">当前价</span>
          <div class="price-val">
            <strong class="num">{{ cockpit.quote.currency }}{{ formatPrice(cockpit.quote.price) }}</strong>
            <span class="change-rate num">
              {{ cockpit.quote.change >= 0 ? '+' : '' }}{{ formatPrice(cockpit.quote.change) }}
              ({{ cockpit.quote.changePercent >= 0 ? '+' : '' }}{{ cockpit.quote.changePercent.toFixed(2) }}%)
            </span>
          </div>
        </div>
        <div class="quote-grid-details">
          <div class="detail-cell">
            <span class="label">市盈率 PE</span>
            <strong class="num">{{ cockpit.quote.pe || '--' }}</strong>
          </div>
          <div class="detail-cell">
            <span class="label">市净率 PB</span>
            <strong class="num">{{ cockpit.quote.pb || '--' }}</strong>
          </div>
          <div class="detail-cell">
            <span class="label">总市值</span>
            <strong class="num">{{ cockpit.quote.marketCap || '--' }}</strong>
          </div>
          <div class="detail-cell">
            <span class="label">换手率</span>
            <strong class="num">{{ cockpit.quote.turnoverRate ? cockpit.quote.turnoverRate + '%' : '--' }}</strong>
          </div>
          <div class="detail-cell">
            <span class="label">成交额</span>
            <strong class="num">{{ formatVolume(cockpit.quote.turnover) }}</strong>
          </div>
        </div>
      </div>

      <!-- K线加载状态与图表 -->
      <div class="chart-status-strip">
        <span class="status-pill" :class="cockpit.chartTone">{{ cockpit.chartStatusLabel }}</span>
        <span>{{ cockpit.chartMessage }}</span>
        <small v-if="cockpit.generatedAt">{{ formatDate(cockpit.generatedAt) }}</small>
      </div>

      <PanelSkeleton v-if="loading" :lines="7" min-height="360px" />
      <KLineChart v-else-if="cockpit.hasChart" :chart="cockpit.chart" />
      <PanelEmpty
        v-else
        :title="`${cockpit.ticker} 暂无行情数据`"
        :hint="cockpit.chartMessage"
        min-height="360px"
      />


      <!-- 行动工具栏与事件解读 -->
      <div class="cockpit-actions-panel" style="margin-top: 16px;">
        <div class="action-strip cockpit-actions">
          <button type="button" @click="emit('start-research', { mode: 'deep', ticker: cockpit.ticker })" title="发起深度AI研究">
            <el-icon><DataAnalysis /></el-icon>
            <span>发起研究</span>
          </button>
          <button type="button" @click="emit('start-research', { mode: 'kline', ticker: cockpit.ticker })" title="复核K线形态">
            <el-icon><TrendCharts /></el-icon>
            <span>K 线复核</span>
          </button>
          <button type="button" @click="emit('start-research', { mode: 'risk', ticker: cockpit.ticker })" title="调取风险评估">
            <el-icon><WarningFilled /></el-icon>
            <span>风险复核</span>
          </button>
          <button type="button" @click="emit('start-research', { mode: 'memo', ticker: cockpit.ticker })" title="记录备忘">
            <el-icon><Document /></el-icon>
            <span>备忘录</span>
          </button>
          <button type="button" :class="{ active: eventPanelOpen }" @click="toggleEventPanel" title="输入重大事件解读">
            <el-icon><Tickets /></el-icon>
            <span>事件解读</span>
          </button>
        </div>

        <div v-if="eventPanelOpen" class="event-input-panel">
          <label class="field">
            <span>事件来源</span>
            <input v-model="eventSource" placeholder="业绩会、10-Q、新闻或用户笔记" />
          </label>
          <label class="field">
            <span>事件文本</span>
            <textarea v-model="eventText" rows="4" placeholder="粘贴财报摘录、新闻或事件描述"></textarea>
          </label>
          <button
            class="primary-button"
            type="button"
            :disabled="!eventText.trim()"
            @click="submitEvent"
          >
            <el-icon><Tickets /></el-icon>
            <span>发起解读</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * StockCockpit — 行情与技术分析驾驶舱
 */
import { ref, computed } from 'vue'
import { DataAnalysis, Document, Refresh, Star, StarFilled, Tickets, TrendCharts, WarningFilled } from '@element-plus/icons-vue'
import KLineChart from '../KLineChart.vue'
import PanelSkeleton from '../common/PanelSkeleton.vue'
import PanelEmpty from '../common/PanelEmpty.vue'

const props = defineProps({
  cockpit: {
    type: Object,
    required: true,
  },
  loading: {
    type: Boolean,
    default: false,
  },
  inWatchlist: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['refresh', 'start-research', 'toggle-watchlist'])

const eventPanelOpen = ref(false)
const eventText = ref('')
const eventSource = ref('')

function toggleEventPanel() {
  eventPanelOpen.value = !eventPanelOpen.value
}

function submitEvent() {
  if (!eventText.value.trim()) return
  emit('start-research', {
    mode: 'event',
    ticker: props.cockpit.ticker,
    eventText: eventText.value,
    eventSource: eventSource.value || undefined,
  })
}

function formatDate(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString()
}

function formatPrice(val) {
  const num = Number(val)
  if (!Number.isFinite(num)) return '--'
  if (num >= 1000) return num.toFixed(1)
  return num.toFixed(2)
}

function formatVolume(val) {
  const num = Number(val)
  if (!Number.isFinite(num)) return '--'
  if (num >= 1e9) return (num / 1e9).toFixed(2) + 'B'
  if (num >= 1e6) return (num / 1e6).toFixed(2) + 'M'
  if (num >= 1e4) return (num / 1e4).toFixed(1) + '万'
  return num.toFixed(0)
}

const quoteTone = computed(() => {
  const change = props.cockpit.quote?.change ?? 0
  return change >= 0 ? 'up' : 'down'
})
</script>

<style scoped>
/* 个股行情报价栏 */
.ticker-quote-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  padding: 12px 16px;
  background: var(--surface-raised);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-soft);
  margin: 10px 0 12px;
  transition: all 0.15s ease;
}

.ticker-quote-bar.up {
  border-left: 4px solid var(--positive);
}

.ticker-quote-bar.down {
  border-left: 4px solid var(--negative);
}

.quote-price-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-right: 20px;
  border-right: 1px solid var(--border-soft);
  min-width: 160px;
}

.quote-price-box .label,
.detail-cell .label {
  font-size: 11px;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
}

.price-val {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.price-val strong {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1;
}

.price-val .change-rate {
  font-size: 12px;
  font-weight: 700;
}

.ticker-quote-bar.up .change-rate {
  color: var(--positive);
}

.ticker-quote-bar.down .change-rate {
  color: var(--negative);
}

.quote-grid-details {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  flex: 1;
  min-width: 250px;
}

.detail-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-cell strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.cockpit-actions-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 自选星标按钮 */
.watchlist-star-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  vertical-align: middle;
  margin-left: 10px;
  padding: 3px 10px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  line-height: 1;
}

.watchlist-star-btn .el-icon {
  font-size: 14px;
}

.watchlist-star-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}

.watchlist-star-btn.active {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}
</style>
