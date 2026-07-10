<template>
  <div class="eval-shell">
    <header class="eval-topbar">
      <div>
        <p class="eyebrow">开发者工具</p>
        <h1>RAG 评估台</h1>
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
              <span class="panel-kicker">质量门禁</span>
              <h2>RAG 评估</h2>
            </div>
            <label class="file-button">
              <input type="file" accept="application/json,.json" @change="importEvalResult" />
              <el-icon><Upload /></el-icon>
              <span>导入</span>
            </label>
          </div>

          <div class="score-strip">
            <div>
              <span>状态</span>
              <strong :class="evalSummary.status">{{ evalSummary.status }}</strong>
            </div>
            <div>
              <span>用例</span>
              <strong>{{ evalSummary.caseCount }}</strong>
            </div>
            <div>
              <span>召回</span>
              <strong>{{ formatMetric(evalSummary.averages.context_recall) }}</strong>
            </div>
            <div>
              <span>引用</span>
              <strong>{{ formatMetric(evalSummary.averages.citation_precision) }}</strong>
            </div>
          </div>

          <div class="gate-list">
            <div v-for="gate in evalSummary.gates" :key="gate.metric" class="gate-row" :class="gate.status">
              <span>{{ gate.label }}</span>
              <strong>{{ formatMetric(gate.value) }}</strong>
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
    </main>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { RECENT_RAG_EVAL_SNAPSHOT } from '../data/ragEvalSnapshot.js'
import { summarizeRagEval } from '../lib/workbench.js'

const importedEvalResult = ref(null)

const evalSummary = computed(() => summarizeRagEval(importedEvalResult.value || RECENT_RAG_EVAL_SNAPSHOT))

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
  const number = Number(value)
  if (!Number.isFinite(number)) return '--'
  if (Math.abs(number) <= 1) return number.toFixed(3)
  return number.toFixed(1)
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
  overflow: hidden;
}

.workspace-grid {
  min-height: 0;
  height: 100%;
  display: grid;
  gap: 14px;
  padding: 18px;
  overflow: auto;
}

.eval-grid {
  grid-template-columns: minmax(320px, 0.86fr) minmax(420px, 1.14fr);
  align-items: start;
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

.score-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
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
  .eval-grid {
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
}
</style>
