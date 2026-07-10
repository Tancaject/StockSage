<template>
  <aside class="cockpit-brief-panel">
    <div class="brief-toolbar">
      <div>
        <span class="brief-kicker">AI 投研简报</span>
        <h2>{{ digest.headline }}</h2>
      </div>
      <span class="brief-stance">{{ digest.recommendation }}</span>
    </div>

    <section class="brief-summary">
      <div class="brief-summary-title">
        <span>核心摘要</span>
        <strong>{{ digest.hasReport ? '已生成' : '待生成' }}</strong>
      </div>
      <p>{{ digest.summary }}</p>
    </section>

    <div class="brief-insight-grid">
      <section class="brief-section">
        <h3>证据线索</h3>
        <div v-if="digest.evidenceItems.length" class="brief-stack">
          <article v-for="item in digest.evidenceItems" :key="item.title" class="brief-row">
            <span>{{ item.title }}</span>
            <strong>{{ item.detail }}</strong>
          </article>
        </div>
        <p v-else class="empty-copy">还没有可展示的引用证据。</p>
      </section>

      <section class="brief-section">
        <h3>风险与缺口</h3>
        <div class="brief-stack">
          <article v-for="item in digest.riskItems" :key="item.title" class="brief-row warning">
            <span>{{ item.title }}</span>
            <strong>{{ item.detail }}</strong>
          </article>
        </div>
      </section>
    </div>

    <section class="brief-section">
      <h3>下一步动作</h3>
      <div class="brief-actions">
        <button
          v-for="action in digest.actions"
          :key="action.id"
          class="brief-action-button"
          type="button"
          @click="handleAction(action)"
        >
          <el-icon>
            <component :is="action.id === 'open-report' ? Document : Refresh" />
          </el-icon>
          <span>
            <strong>{{ action.label }}</strong>
            <small>{{ action.detail }}</small>
          </span>
        </button>
      </div>
    </section>

    <section v-if="digest.recentVersions.length" class="brief-section">
      <h3>最近版本</h3>
      <div class="brief-version-strip">
        <button
          v-for="row in digest.recentVersions"
          :key="row.id || `${row.ticker}-${row.versionLabel}`"
          type="button"
          @click="emit('open-report', row.id)"
        >
          <strong>{{ row.versionLabel }}</strong>
          <span>{{ row.recommendation || 'UNKNOWN' }}</span>
          <small>{{ formatDate(row.timeLabel) }}</small>
        </button>
      </div>
    </section>

    <section v-if="digest.provenance.length" class="brief-provenance">
      <div v-for="row in digest.provenance" :key="row.label">
        <span>{{ row.label }}</span>
        <strong>{{ row.label === '生成时间' ? formatDate(row.value) : row.value }}</strong>
      </div>
    </section>

    <section class="brief-focus">
      <div class="brief-focus-header">
        <span>下一轮研究焦点</span>
        <strong>{{ digest.selectedFocusLabels.join('、') || '未选择' }}</strong>
      </div>
      <div class="focus-grid">
        <label v-for="area in focusOptions" :key="area" class="check-chip">
          <input
            type="checkbox"
            :value="area"
            :checked="focusAreas.includes(area)"
            @change="handleFocusChange(area, $event.target.checked)"
          />
          <span>{{ focusOptionLabels[area] || area }}</span>
        </label>
      </div>
    </section>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { Document, Refresh } from '@element-plus/icons-vue'
import { buildLatestBriefDigest } from '../../lib/workbench.js'

const props = defineProps({
  ticker: {
    type: String,
    default: '',
  },
  latestReport: {
    type: Object,
    default: null,
  },
  reportVersions: {
    type: Array,
    default: () => [],
  },
  focusOptions: {
    type: Array,
    default: () => [],
  },
  focusOptionLabels: {
    type: Object,
    default: () => ({}),
  },
  focusAreas: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['update:focusAreas', 'open-report', 'start-research'])

const digest = computed(() => buildLatestBriefDigest({
  ticker: props.ticker,
  latestReport: props.latestReport,
  reportVersions: props.reportVersions,
  focusAreas: props.focusAreas,
}))

function handleAction(action) {
  if (action.id === 'open-report') {
    emit('open-report', action.versionId)
  } else if (action.id === 'start-research') {
    emit('start-research')
  }
}

function handleFocusChange(area, checked) {
  const current = props.focusAreas.slice()
  if (checked) {
    if (!current.includes(area)) current.push(area)
  } else {
    const idx = current.indexOf(area)
    if (idx !== -1) current.splice(idx, 1)
  }
  emit('update:focusAreas', current)
}

function formatDate(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString()
}
</script>

<style scoped>
.cockpit-brief-panel {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.brief-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.brief-kicker,
.brief-summary-title span,
.brief-section h3,
.brief-focus-header span,
.brief-provenance span,
.brief-row span {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.brief-toolbar h2 {
  margin: 4px 0 0;
  color: var(--text-primary);
  font-size: 17px;
  line-height: 1.2;
}

.brief-stance {
  min-height: 28px;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  padding: 0 12px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--positive);
  font-size: 12px;
  font-weight: 600;
}

.brief-summary {
  padding: 14px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
}

.brief-summary-title,
.brief-focus-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.brief-summary-title strong,
.brief-focus-header strong {
  color: var(--text-primary);
  font-size: 12px;
  line-height: 1.35;
  text-align: right;
}

.brief-summary p,
.empty-copy {
  margin: 10px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.62;
}

.brief-insight-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.brief-section {
  min-width: 0;
  display: grid;
  gap: 10px;
}

.brief-section h3 {
  margin: 0;
}

.brief-stack {
  display: grid;
  gap: 8px;
}

.brief-row {
  min-width: 0;
  display: grid;
  gap: 5px;
  padding: 11px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
}

.brief-row.warning {
  background: color-mix(in srgb, var(--surface-raised) 88%, var(--warning) 12%);
}

.brief-row strong {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.brief-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.brief-action-button {
  min-width: 0;
  min-height: 54px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.brief-action-button:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.brief-action-button .el-icon {
  flex: 0 0 auto;
  color: var(--accent);
  font-size: 17px;
}

.brief-action-button span {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.brief-action-button strong {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.2;
}

.brief-action-button small {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.35;
}

.brief-version-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.brief-version-strip button {
  min-width: 0;
  display: grid;
  gap: 4px;
  padding: 10px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
}

.brief-version-strip button:hover {
  border-color: var(--accent);
}

.brief-version-strip strong,
.brief-version-strip span,
.brief-version-strip small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brief-version-strip span,
.brief-version-strip small {
  color: var(--text-muted);
  font-size: 12px;
}

.brief-provenance {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  padding-top: 2px;
}

.brief-provenance div {
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
}

.brief-provenance strong {
  display: block;
  margin-top: 5px;
  color: var(--text-primary);
  font-size: 12px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.brief-focus {
  display: grid;
  gap: 10px;
  padding-top: 2px;
}

.focus-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.check-chip {
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 12px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  transition: all 0.15s ease;
}

.check-chip:hover {
  border-color: var(--border-strong);
  background: var(--surface-raised);
}

.check-chip:has(input:checked) {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--text-primary);
}

@media (max-width: 820px) {
  .brief-insight-grid,
  .brief-actions,
  .brief-version-strip,
  .brief-provenance {
    grid-template-columns: 1fr;
  }
}
</style>
