/**
 * CompareDesk.vue
 * 多标的对比次级入口：标的输入 + 维度勾选 + 发起对比研究。
 * 不暴露 prompt 原文；轻量摘要徽章展示当前设置。
 * emit: start-compare({ tickers: string[], dimensions: string[] })
 */
<template>
  <section class="workspace-grid compare-grid">
    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>对比设置</h2>
        </div>
        <button class="primary-button" type="button" :disabled="!canSubmit" @click="handleSubmit">
          <el-icon><Switch /></el-icon>
          <span>比较</span>
        </button>
      </div>

      <label class="field">
        <span>标的（逗号分隔）</span>
        <input
          v-model="draft"
          placeholder="MSFT, AMZN, GOOG"
          @keydown.enter="handleSubmit"
        />
      </label>
      <div class="preset-links">
        <span class="preset-label">快捷预设：</span>
        <button
          v-for="preset in PRESETS"
          :key="preset.name"
          class="preset-tag"
          type="button"
          @click="draft = preset.tickers"
        >
          {{ preset.name }}
        </button>
      </div>

      <div class="focus-grid">
        <label v-for="dim in DIMENSION_OPTIONS" :key="dim" class="check-chip">
          <input v-model="activeDimensions" type="checkbox" :value="dim" />
          <span>{{ DIMENSION_LABELS[dim] || dim }}</span>
        </label>
      </div>

      <div class="settings-summary flat panel-footer-meta">
        <div class="summary-row">
          <span>标的</span>
          <strong>{{ tickerSummary }}</strong>
        </div>
        <div class="summary-row">
          <span>比较维度</span>
          <strong>{{ dimensionSummary }}</strong>
        </div>
        <div class="summary-row">
          <span>输出</span>
          <strong>并列表格、强弱项、数据缺口和每个标的的多空理由</strong>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Switch } from '@element-plus/icons-vue'
import { normalizeTicker } from '../../lib/workbench.js'

/** @type {string[]} */
const DIMENSION_OPTIONS = ['business mix', 'growth', 'margin', 'valuation', 'risk', 'capital allocation']

const DIMENSION_LABELS = {
  'business mix': '业务结构',
  growth: '增长',
  margin: '利润率',
  valuation: '估值',
  risk: '风险',
  'capital allocation': '资本配置',
}

const PRESETS = [
  { name: 'AI巨头', tickers: 'MSFT, GOOG, AMZN' },
  { name: '芯片双雄', tickers: 'NVDA, AMD' },
  { name: '智能出行', tickers: 'TSLA, BYD' },
  { name: '社交媒体', tickers: 'META, SNAP' },
]

const emit = defineEmits(['start-compare'])

const draft = ref('MSFT, AMZN')
const activeDimensions = ref(['business mix', 'growth', 'risk'])

const parsedTickers = computed(() => {
  const list = draft.value.split(',').map(normalizeTicker).filter(Boolean)
  return [...new Set(list)]
})

const canSubmit = computed(() => parsedTickers.value.length >= 2 && activeDimensions.value.length > 0)

const tickerSummary = computed(() =>
  parsedTickers.value.length > 0 ? parsedTickers.value.join(' vs ') : '未选择'
)

const dimensionSummary = computed(() =>
  activeDimensions.value.length > 0
    ? activeDimensions.value.map(d => DIMENSION_LABELS[d] || d).join('、')
    : '未选择'
)

function handleSubmit() {
  if (!canSubmit.value) return
  // 回填去重格式化后的字符串
  draft.value = parsedTickers.value.join(', ')
  emit('start-compare', {
    tickers: parsedTickers.value,
    dimensions: activeDimensions.value,
  })
}
</script>

<style scoped>
.settings-summary.flat {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.panel-footer-meta {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--border-soft);
}

.summary-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12.5px;
  padding: 4px 0;
  border-bottom: 1px solid var(--border-soft);
}

.summary-row:last-child {
  border-bottom: none;
}

.summary-row span {
  color: var(--text-muted);
  flex-shrink: 0;
}

.summary-row strong {
  text-align: right;
  word-break: break-word;
}

.preset-links {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.preset-label {
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 700;
}

.preset-tag {
  background: var(--accent-soft);
  border: 1px solid transparent;
  color: var(--text-secondary);
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s ease;
  font-weight: 500;
}

.preset-tag:hover {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
</style>
