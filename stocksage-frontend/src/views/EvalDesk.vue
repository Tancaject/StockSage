<template>
  <div class="eval-shell">
    <header class="eval-topbar">
      <div>
        <p class="eyebrow">开发者工具</p>
        <h1>Agent / RAG 评估台</h1>
      </div>
      <div class="topbar-actions">
        <router-link class="quiet-button" to="/workbench">← 返回工作台</router-link>
      </div>
    </header>

    <main class="eval-main">
      <section class="workspace-grid eval-grid">
        <div class="panel">
          <div class="panel-header">
            <div>
              <span class="panel-kicker">经典质量指标</span>
              <h2>Agent / RAG Quality</h2>
            </div>
            <label class="file-button">
              <input type="file" accept="application/json,.json" @change="importEvalResult" />
              <el-icon><Upload /></el-icon>
              <span>导入</span>
            </label>
          </div>

          <p class="panel-note">
            只展示可由 Golden Set、抽样 Judge 或显式 quality 契约支撑的指标；缺标签时显示 NO_DATA。
          </p>
          <div class="score-strip quality-strip">
            <div v-for="metric in qualityMetrics" :key="metric.id">
              <span>{{ metric.label }}</span>
              <strong :class="metric.status.toLowerCase()">{{ formatQualityMetric(metric) }}</strong>
              <small>
                {{ metric.status === 'NO_DATA' ? `REQUIRES ${metric.requiredEvidenceKind}` : metric.evidenceKind }}
                · {{ formatSampleCount(metric.sampleCount) }}
                <template v-if="metric.k !== null"> · K={{ metric.k }}</template>
              </small>
              <p>{{ qualityMetricDetail(metric) }}</p>
            </div>
          </div>

          <div class="optional-strip">
            <span>{{ importedEvalResult ? 'IMPORTED' : 'HISTORICAL SNAPSHOT' }}</span>
            <span>{{ evalSummary.kind.toUpperCase() }} · {{ evalSummary.caseCount }} cases</span>
            <span>generated {{ evalSummary.createdAt || 'unknown' }}</span>
          </div>

          <div v-if="evalSummary.optionalSections" class="optional-strip">
            <span>RAG: {{ evalSummary.optionalSections.rag }}</span>
            <span>Trace: {{ evalSummary.optionalSections.trace }}</span>
            <span>E2E: {{ evalSummary.optionalSections.endToEnd }}</span>
            <span>Baseline: {{ evalSummary.optionalSections.baseline }}</span>
          </div>

          <div class="metric-section">
            <span class="panel-kicker">原始评测诊断（非经典指标口径）</span>
            <p v-if="evalSummary.kind === 'rag'" class="panel-note">
              门禁：{{ { pass: '通过', fail: '未通过', missing: '证据不足', unrated: '未评定' }[evalSummary.status] || '未评定' }}
              <template v-if="evalSummary.status === 'unrated'"> · 结果未提供门禁判定，仅展示原始指标。</template>
            </p>
            <div class="gate-list compact">
              <div v-for="gate in evalSummary.gates" :key="gate.metric" class="gate-row" :class="gate.status">
                <span>{{ gate.label }}</span>
                <strong>{{ formatMetric(gate.value) }}</strong>
                <small v-if="evalSummary.kind === 'rag' && gate.target">
                  {{ gate.target.operator }} {{ formatMetric(gate.target.threshold) }}
                  · {{ gate.required ? '必需' : '可选' }}
                  · {{ gate.status === 'missing' ? 'NO_DATA' : gate.status.toUpperCase() }}
                </small>
              </div>
            </div>
          </div>

          <div v-if="evalSummary.ragasMetrics.length" class="metric-section">
            <span class="panel-kicker">RAGAS</span>
            <div class="gate-list compact">
              <div
                v-for="metric in evalSummary.ragasMetrics"
                :key="metric.metric"
                class="gate-row"
                :class="metric.status"
              >
                <span>{{ metric.label }}</span>
                <strong>{{ formatMetric(metric.value) }}</strong>
              </div>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-header">
            <div>
              <span class="panel-kicker">失败项</span>
              <h2>最差用例</h2>
            </div>
          </div>
          <div class="case-list">
            <div v-for="item in evalSummary.worstCases" :key="item.id" class="case-row">
              <strong>{{ item.id }}</strong>
              <span>{{ item.category }}</span>
              <small>{{ formatMetric(item.score) }}</small>
              <p v-if="item.question">{{ item.question }}</p>
              <p v-if="item.answer">Answer: {{ item.answer }}</p>
              <p v-if="item.expectedAnswer">Expected: {{ item.expectedAnswer }}</p>
              <ul v-if="item.contexts.length" class="context-list">
                <li v-for="context in item.contexts" :key="`${item.id}-${context.rank}`">
                  #{{ context.rank }} {{ context.ticker }} {{ context.filingType }} {{ context.section }}
                </li>
              </ul>
              <ul v-if="item.citations.length" class="context-list">
                <li v-for="citation in item.citations" :key="`${item.id}-citation-${citation.rank || citation.claim}`">
                  citation {{ citation.rank || '-' }} {{ citation.claim || citation.source_id || '' }}
                </li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      <section class="admin-section">
        <div class="panel admin-toolbar">
          <div>
            <span class="panel-kicker">只读运行时</span>
            <h2>Skills / Capabilities / MCP</h2>
            <p>Admin Token 仅保存在当前页面内存，刷新即清除。</p>
          </div>
          <div class="admin-controls">
            <input
              v-model="adminToken"
              type="password"
              autocomplete="off"
              placeholder="Admin Token"
              @keyup.enter="loadAdminSnapshot"
            />
            <button class="quiet-button" :disabled="adminLoading" @click="loadAdminSnapshot">
              {{ adminLoading ? '加载中…' : '刷新状态' }}
            </button>
          </div>
          <p v-if="adminState.message" class="admin-message" :class="adminState.status">
            {{ adminState.message }}
          </p>
        </div>

        <div v-if="adminSnapshot" class="workspace-grid runtime-grid">
          <div class="panel">
            <div class="panel-header">
              <div>
                <span class="panel-kicker">Skills</span>
                <h2>已注册工作流</h2>
              </div>
            </div>
            <div class="case-list">
              <div v-for="skill in adminSnapshot.skills.skills" :key="skill.id" class="runtime-row">
                <div>
                  <strong>{{ skill.displayName }}</strong>
                  <small>{{ skill.id }} · v{{ skill.version }} · {{ skill.executionMode }}</small>
                </div>
                <span>{{ skill.routes.join(', ') }}</span>
                <p>
                  默认：{{ skill.currentDefault ? '是' : '否' }} · Tier：{{ skill.minimumModelTier }}
                  · Fallback：{{ skill.fallbackSkillIds.join(' → ') || '无' }}
                </p>
              </div>
            </div>
          </div>

          <div class="panel">
            <div class="panel-header">
              <div>
                <span class="panel-kicker">Runtime</span>
                <h2>Capabilities / MCP</h2>
              </div>
              <strong class="runtime-badge" :class="adminSnapshot.runtime.mcp.state.toLowerCase()">
                {{ mcpStateLabel(adminSnapshot.runtime.mcp.state) }}
              </strong>
            </div>
            <p class="runtime-note">
              approved tools {{ adminSnapshot.runtime.mcp.approvedToolCount }}
              · protocol {{ adminSnapshot.runtime.mcp.protocolVersions.join(', ') || '--' }}
              · {{ adminSnapshot.runtime.mcp.errorCode || 'READY' }}
            </p>
            <div class="score-strip live-strip">
              <div v-for="metric in runtimeMetrics" :key="metric.id">
                <span>{{ metric.label }}</span>
                <strong :class="metric.status.toLowerCase()">{{ formatRuntimeMetric(metric) }}</strong>
                <small>{{ metric.evidenceKind }} · {{ formatSampleCount(metric.sampleCount) }}</small>
                <p>{{ runtimeMetricDetail(metric) }}</p>
              </div>
            </div>
            <div class="optional-strip">
              <span>{{ adminSnapshot.runtime.window?.kind || 'UNKNOWN_WINDOW' }}</span>
              <span>{{ adminSnapshot.runtime.schemaVersion }}</span>
              <span>generated {{ adminSnapshot.runtime.generatedAt || 'unknown' }}</span>
            </div>
            <div class="case-list">
              <div
                v-for="capability in adminSnapshot.runtime.capabilities"
                :key="capability.id"
                class="runtime-row"
              >
                <div>
                  <strong>{{ capability.id }}</strong>
                  <small>{{ capability.providerType }} · {{ capability.riskLevel }}</small>
                </div>
                <span>{{ capability.metricsStatus }}</span>
                <p>
                  calls {{ capability.calls }}
                  · success {{ formatRate(capability.successRate) }}
                  · P95 {{ formatDuration(capability.p95DurationMs) }}
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { RECENT_RAG_EVAL_SNAPSHOT } from '../data/ragEvalSnapshot.js'
import { fetchAgentAdminSnapshot } from '../api/agentAdmin.js'
import {
  summarizeCanonicalQualityMetrics,
  summarizeEvalResult,
  summarizeRuntimeMetrics,
} from '../lib/workbench.js'

const importedEvalResult = ref(null)
const adminToken = ref('')
const adminSnapshot = ref(null)
const adminLoading = ref(false)
const adminState = ref({ status: 'idle', message: '' })

const activeEvalResult = computed(() => importedEvalResult.value || RECENT_RAG_EVAL_SNAPSHOT)
const evalSummary = computed(() => summarizeEvalResult(activeEvalResult.value))
const qualityMetrics = computed(() => summarizeCanonicalQualityMetrics(activeEvalResult.value))
const runtimeMetrics = computed(() => summarizeRuntimeMetrics(adminSnapshot.value?.runtime))

async function importEvalResult(event) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    importedEvalResult.value = JSON.parse(await file.text())
    ElMessage.success('评估结果已导入')
  } catch (error) {
    ElMessage.error(`导入失败：${error.message}`)
  } finally {
    event.target.value = ''
  }
}

function formatMetric(value) {
  if (value === null || value === undefined || String(value).trim() === '') return 'NO_DATA'
  const number = Number(value)
  if (!Number.isFinite(number)) return '--'
  if (Math.abs(number) <= 1) return number.toFixed(3)
  return number.toFixed(1)
}

function formatQualityMetric(metric) {
  return metric.value === null ? 'NO_DATA' : `${(Number(metric.value) * 100).toFixed(1)}%`
}

function formatSampleCount(value) {
  return value === null || value === undefined ? 'n=unknown' : `n=${value}`
}

function qualityMetricDetail(metric) {
  if (metric.value === null) return metric.requirement
  return [
    `dataset ${metric.datasetVersion || 'unknown'}`,
    `evaluator ${metric.evaluatorVersion || 'unknown'}`,
    metric.judgeModel && `judge ${metric.judgeModel}`,
    `generated ${metric.generatedAt || 'unknown'}`,
  ].filter(Boolean).join(' · ')
}

function formatRuntimeMetric(metric) {
  if (metric.value === null) return 'NO_DATA'
  return metric.format === 'duration'
    ? formatDuration(metric.value)
    : formatRate(metric.value)
}

function runtimeMetricDetail(metric) {
  const scope = {
    TRACE_LINKED_EXECUTIONS: 'Trace 关联工具执行',
    SUCCESSFUL_TRACES: '成功 Agent 链路',
    SUCCESSFUL_TRACES_IN_RECENT_500: '最近 500 条中的成功 Agent 链路',
  }[metric.scope] || metric.scope
  const window = metric.window || {}
  return `${scope} · ${window.kind || 'unknown window'} · ${window.startedAt || 'unknown'} → ${window.endedAt || 'unknown'}`
}

async function loadAdminSnapshot() {
  if (!adminToken.value) {
    adminState.value = { status: 'forbidden', message: '请输入 Admin Token。' }
    return
  }
  adminLoading.value = true
  try {
    adminSnapshot.value = await fetchAgentAdminSnapshot(adminToken.value)
    adminState.value = { status: 'ready', message: '只读运行时快照已更新。' }
  } catch (error) {
    adminSnapshot.value = null
    adminState.value = error.status === 403
      ? { status: 'forbidden', message: 'Admin Token 无效或缺失（403）。' }
      : { status: 'unavailable', message: `后端状态不可用：${error.message}` }
  } finally {
    adminLoading.value = false
  }
}

function mcpStateLabel(state) {
  return {
    DISABLED: '已关闭',
    UNCONFIGURED: '未配置',
    READY: '就绪',
    DEGRADED: '已降级',
  }[state] || state
}

function formatRate(value) {
  return value === null || value === undefined ? 'NO_DATA' : `${(Number(value) * 100).toFixed(1)}%`
}

function formatDuration(value) {
  return value === null || value === undefined ? 'NO_DATA' : `${Number(value).toFixed(1)}ms`
}
</script>

<style scoped>
.eval-shell {
  height: 100vh;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background:
    linear-gradient(90deg, rgba(24, 47, 40, 0.06) 1px, transparent 1px) 0 0 / 36px 36px,
    linear-gradient(0deg, rgba(24, 47, 40, 0.04) 1px, transparent 1px) 0 0 / 36px 36px,
    #f4f7f6;
  color: var(--text-primary);
}

.eval-topbar {
  min-height: 76px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 18px 24px;
  border-bottom: 1px solid var(--border-soft);
  background: rgba(255, 255, 255, 0.78);
}

.eyebrow,
.panel-kicker {
  margin: 0;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 800;
  text-transform: uppercase;
}

.eval-topbar h1 {
  margin: 3px 0 0;
  font-size: 24px;
  line-height: 1.2;
  letter-spacing: 0;
}

.topbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.quiet-button,
.file-button {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  font-weight: 700;
  letter-spacing: 0;
  cursor: pointer;
  text-decoration: none;
  padding: 0 12px;
}

.quiet-button {
  background: #fff;
  color: var(--text-secondary);
  border-color: var(--border-soft);
}

.file-button {
  position: relative;
  overflow: hidden;
  background: var(--text-primary);
  color: #fff;
  border-color: var(--text-primary);
}

.file-button input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.eval-main {
  min-height: 0;
  flex: 1;
  overflow: auto;
}

.workspace-grid {
  min-height: 0;
  height: 100%;
  display: grid;
  gap: 14px;
  padding: 18px;
}

.eval-grid {
  grid-template-columns: minmax(320px, 0.86fr) minmax(420px, 1.14fr);
  align-items: start;
}

.admin-section {
  padding: 0 18px 18px;
}

.admin-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
}

.admin-toolbar p,
.runtime-note {
  margin: 5px 0 0;
  color: var(--text-muted);
  font-size: 12px;
}

.admin-controls {
  display: flex;
  gap: 8px;
}

.admin-controls input {
  width: 220px;
  min-height: 38px;
  padding: 0 11px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: #fff;
}

.admin-message {
  grid-column: 1 / -1;
}

.admin-message.ready {
  color: var(--positive);
}

.admin-message.forbidden,
.admin-message.unavailable {
  color: var(--negative);
}

.runtime-grid {
  height: auto;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 14px 0 0;
}

.runtime-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px 10px;
  padding: 10px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: #fff;
}

.runtime-row div {
  min-width: 0;
}

.runtime-row strong,
.runtime-row small {
  display: block;
}

.runtime-row small,
.runtime-row span,
.runtime-row p {
  color: var(--text-muted);
  font-size: 12px;
}

.runtime-row p {
  grid-column: 1 / -1;
  margin: 0;
}

.runtime-badge {
  padding: 5px 8px;
  border-radius: 999px;
  font-size: 12px;
}

.runtime-badge.ready {
  color: var(--positive);
  background: rgba(32, 128, 88, 0.1);
}

.runtime-badge.disabled,
.runtime-badge.unconfigured {
  color: var(--text-muted);
  background: rgba(93, 108, 101, 0.1);
}

.runtime-badge.degraded {
  color: var(--negative);
  background: rgba(180, 64, 48, 0.1);
}

.panel {
  min-width: 0;
  align-self: start;
  padding: 16px;
  border: 1px solid rgba(203, 215, 209, 0.9);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 16px 36px rgba(23, 32, 29, 0.06);
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
}

.panel h2 {
  margin: 3px 0 0;
  font-size: 18px;
  line-height: 1.2;
  letter-spacing: 0;
}

.panel-note {
  margin: 0 0 12px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.score-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 8px;
}

.score-strip div {
  min-height: 70px;
  padding: 11px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: #fff;
}

.score-strip span,
.gate-row small {
  display: block;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 800;
  text-transform: uppercase;
}

.score-strip strong {
  display: block;
  margin-top: 8px;
  font-size: 20px;
}

.score-strip small,
.score-strip p {
  display: block;
  margin: 7px 0 0;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.4;
}

.quality-strip div {
  min-height: 146px;
}

.live-strip {
  margin-top: 12px;
}

.live-strip div {
  min-height: 124px;
}

.score-strip strong.observed {
  color: var(--positive);
}

.score-strip strong.no_data {
  color: var(--text-muted);
  font-size: 16px;
}

.score-strip strong.pass,
.score-strip strong.fail,
.gate-row.pass strong,
.gate-row.fail strong {
  text-transform: uppercase;
}

.score-strip strong.pass,
.gate-row.pass strong {
  color: var(--positive);
}

.score-strip strong.fail,
.gate-row.fail strong {
  color: var(--negative);
}

.gate-list,
.case-list {
  display: grid;
  gap: 7px;
  margin-top: 14px;
}

.metric-section {
  margin-top: 16px;
}

.optional-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  color: var(--text-muted);
  font-size: 12px;
}

.optional-strip span {
  padding: 5px 8px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid var(--border-soft);
}

.gate-list.compact {
  margin-top: 8px;
}

.gate-row,
.case-row {
  min-height: 44px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  padding: 9px 11px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: #fff;
}

.gate-row small {
  grid-column: 1 / -1;
  text-transform: none;
}

.gate-row.missing {
  opacity: 0.72;
}

.case-row {
  grid-template-columns: minmax(0, 1fr) auto auto;
}

.case-row p,
.context-list {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.context-list {
  padding-left: 18px;
}

.case-row strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-row span,
.case-row small {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
}

@media (max-width: 1080px) {
  .eval-grid,
  .runtime-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .score-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .case-row {
    grid-template-columns: 1fr;
  }

  .panel {
    padding: 12px;
  }

  .admin-toolbar {
    grid-template-columns: 1fr;
  }

  .admin-controls {
    flex-direction: column;
  }

  .admin-controls input {
    width: 100%;
  }
}
</style>
