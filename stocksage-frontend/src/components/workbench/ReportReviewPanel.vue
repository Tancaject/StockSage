<template>
  <section class="panel report-review-panel" :aria-busy="loading || saving">
    <div class="report-review-header">
      <div>
        <span class="report-review-kicker">完整报告 · 人工审核</span>
        <h2>{{ detailTitle }}</h2>
      </div>
      <span
        v-if="detail"
        class="report-review-status"
        :class="statusClass(summary.reviewStatus)"
      >
        {{ summary.reviewStatusLabel }}
      </span>
    </div>

    <div v-if="loading" class="report-review-state" role="status">
      <strong>正在读取完整报告</strong>
      <span>同步报告正文、证据与审核历史…</span>
    </div>

    <div v-else-if="error" class="report-review-state error" role="alert">
      <strong>完整报告读取失败</strong>
      <span>{{ error }}</span>
      <button class="report-review-button secondary" type="button" @click="$emit('retry')">
        重试
      </button>
    </div>

    <div v-else-if="!detail" class="report-review-state">
      <strong>选择一份历史报告</strong>
      <span>打开左侧版本后，可查看完整结论、证据链与人工审核记录。</span>
    </div>

    <div v-else class="report-review-content">
      <dl class="report-review-meta">
        <div>
          <dt>投资评级</dt>
          <dd>{{ report.recommendation || 'NOT_RATED' }}</dd>
        </div>
        <div>
          <dt>报告质量</dt>
          <dd>{{ report.qualityStatus || '未标注' }}</dd>
        </div>
        <div>
          <dt>报告版本</dt>
          <dd>v{{ summary.reportVersion ?? '-' }}</dd>
        </div>
        <div>
          <dt>数据时效</dt>
          <dd>{{ report.dataFreshness || '未说明' }}</dd>
        </div>
      </dl>

      <section class="report-review-section">
        <h3>报告摘要</h3>
        <p class="report-review-summary">{{ report.analystSummary || '报告没有返回摘要。' }}</p>
      </section>

      <div class="report-review-columns">
        <section
          v-for="section in reviewSections"
          :key="section.title"
          class="report-review-section report-review-list-section"
          :class="section.tone"
        >
          <h3>{{ section.title }}</h3>
          <ul v-if="section.items.length">
            <li v-for="item in section.items" :key="item">{{ item }}</li>
          </ul>
          <p v-else class="report-review-empty">{{ section.emptyText }}</p>
        </section>
      </div>

      <section class="report-review-section">
        <div class="report-review-section-title">
          <h3>证据链</h3>
          <span>{{ evidenceItems.length }} 条</span>
        </div>
        <div v-if="evidenceItems.length" class="report-review-evidence-list">
          <article v-for="item in evidenceItems" :key="item.id" class="report-review-evidence">
            <div>
              <strong>{{ item.dimension }}</strong>
              <span>{{ item.source }}</span>
            </div>
            <p>{{ item.evidence || '未提供证据文本。' }}</p>
            <small v-if="item.implication">{{ item.implication }}</small>
            <div class="report-review-evidence-ids">
              <span>Evidence IDs</span>
              <code v-for="evidenceId in item.sourceEvidenceIds" :key="evidenceId">
                {{ evidenceId }}
              </code>
              <em v-if="item.sourceEvidenceIds.length === 0">未绑定</em>
            </div>
          </article>
        </div>
        <p v-else class="report-review-empty">这份报告没有可展示的结构化证据。</p>
      </section>

      <section class="report-review-section">
        <div class="report-review-section-title">
          <h3>引用</h3>
          <span>{{ citations.length }} 条</span>
        </div>
        <ol v-if="citations.length" class="report-review-citations">
          <li v-for="(citation, index) in citations" :key="`${index}-${citation}`">
            {{ citation }}
          </li>
        </ol>
        <p v-else class="report-review-empty">这份报告没有可展示的引用。</p>
      </section>

      <section class="report-review-section review-decision-section">
        <div class="report-review-section-title">
          <h3>审核决策</h3>
          <span>锁版本 {{ summary.lockVersion ?? '-' }}</span>
        </div>
        <dl class="report-review-current">
          <div>
            <dt>当前状态</dt>
            <dd>{{ summary.reviewStatusLabel }}</dd>
          </div>
          <div>
            <dt>审核人</dt>
            <dd>{{ summary.reviewerUserId || '尚未审核' }}</dd>
          </div>
          <div>
            <dt>审核时间</dt>
            <dd>{{ formatDate(summary.reviewedAt || summary.updatedAt) }}</dd>
          </div>
          <div class="wide">
            <dt>当前意见</dt>
            <dd>{{ summary.reviewComment || '暂无审核意见' }}</dd>
          </div>
        </dl>

        <label v-if="actions.length" class="report-review-comment">
          <span>本次审核意见</span>
          <textarea
            v-model="comment"
            rows="3"
            :disabled="saving"
            placeholder="退回补研或驳回时必须说明原因；其他决策可选填。"
            @input="validationError = ''"
          />
        </label>

        <p v-if="validationError || saveError" class="report-review-save-error" role="alert">
          {{ validationError || saveError }}
        </p>

        <div v-if="actions.length" class="report-review-actions">
          <button
            v-for="action in actions"
            :key="action.status"
            class="report-review-button"
            :class="action.tone"
            type="button"
            :disabled="saving"
            @click="submitReview(action)"
          >
            {{ saving ? '保存中…' : action.label }}
          </button>
        </div>
      </section>

      <section class="report-review-section">
        <div class="report-review-section-title">
          <h3>审核历史</h3>
          <span>{{ reviewHistory.length }} 条</span>
        </div>
        <ol v-if="reviewHistory.length" class="report-review-history">
          <li v-for="entry in reviewHistory" :key="entry.id">
            <span class="report-review-status" :class="statusClass(entry.status)">
              {{ entry.statusLabel }}
            </span>
            <strong>{{ entry.reviewerUserId || '系统' }}</strong>
            <time>{{ formatDate(entry.timeLabel) }}</time>
            <small v-if="entry.fromStatus || entry.toStatus" class="report-review-transition">
              {{ entry.fromStatusLabel }} → {{ entry.toStatusLabel }}
            </small>
            <p>{{ entry.comment || '未填写审核意见' }}</p>
          </li>
        </ol>
        <p v-else class="report-review-empty">暂无人工审核历史。</p>
      </section>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getReportReviewActions } from '../../lib/workbench.js'

const props = defineProps({
  detail: {
    type: Object,
    default: null,
  },
  loading: {
    type: Boolean,
    default: false,
  },
  error: {
    type: String,
    default: '',
  },
  saving: {
    type: Boolean,
    default: false,
  },
  saveError: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['retry', 'submit-review'])
const comment = ref('')
const validationError = ref('')
const summary = computed(() => props.detail?.summary || {})
const report = computed(() => props.detail?.report || {})
const evidenceItems = computed(() => props.detail?.evidenceItems || [])
const citations = computed(() => props.detail?.citations || [])
const reviewHistory = computed(() => props.detail?.reviewHistory || [])
const actions = computed(() => getReportReviewActions(summary.value.reviewStatus))
const reviewSections = computed(() => [
  {
    title: '核心理由',
    items: report.value.rationale || [],
    emptyText: '暂无结构化理由。',
    tone: '',
  },
  {
    title: '风险因素',
    items: report.value.riskFactors || [],
    emptyText: '暂无结构化风险。',
    tone: 'risk',
  },
  {
    title: '未知项',
    items: report.value.unknowns || [],
    emptyText: '暂无待确认未知项。',
    tone: 'unknown',
  },
])
const detailTitle = computed(() => {
  if (!props.detail) return '报告详情'
  const ticker = summary.value.ticker || report.value.ticker || 'UNKNOWN'
  const version = summary.value.reportVersion ?? '-'
  return `${ticker} v${version}`
})

watch(
  () => `${summary.value.id ?? ''}:${summary.value.lockVersion ?? ''}`,
  () => {
    comment.value = ''
    validationError.value = ''
  },
  { immediate: true },
)

function submitReview(action) {
  const reviewComment = comment.value.trim()
  const lockVersion = summary.value.lockVersion
  if (
    lockVersion === null
    || lockVersion === undefined
    || String(lockVersion).trim() === ''
    || !Number.isInteger(Number(lockVersion))
  ) {
    validationError.value = '报告缺少锁版本，请刷新详情后重试。'
    return
  }
  if (action.requiresComment && !reviewComment) {
    validationError.value = `${action.label}必须填写审核意见。`
    return
  }
  validationError.value = ''
  emit('submit-review', {
    status: action.status,
    comment: reviewComment,
    expectedLockVersion: summary.value.lockVersion,
  })
}

function formatDate(value) {
  if (!value) return '--'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

function statusClass(status) {
  return String(status || 'unknown').trim().toLowerCase().replaceAll('_', '-')
}
</script>

<style scoped>
.report-review-panel {
  display: grid;
  gap: 14px;
}

.report-review-header,
.report-review-section-title,
.report-review-evidence > div:first-child {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.report-review-header h2,
.report-review-section h3 {
  margin: 0;
}

.report-review-kicker,
.report-review-section-title span,
.report-review-meta dt,
.report-review-current dt,
.report-review-comment span,
.report-review-evidence-ids > span {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.report-review-header h2 {
  margin-top: 4px;
  font-size: 17px;
}

.report-review-status {
  min-height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 9px;
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.report-review-status.in-review {
  background: var(--accent-soft);
  color: var(--accent);
}

.report-review-status.approved {
  background: rgba(37, 160, 105, 0.14);
  color: var(--positive);
}

.report-review-status.needs-research {
  background: rgba(217, 142, 35, 0.14);
  color: #b36d0d;
}

.report-review-status.rejected {
  background: rgba(207, 70, 70, 0.13);
  color: var(--danger);
}

.report-review-state {
  min-height: 180px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  padding: 24px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--surface-raised);
  color: var(--text-muted);
  text-align: center;
}

.report-review-state strong {
  color: var(--text-primary);
}

.report-review-state.error {
  border-color: rgba(207, 70, 70, 0.35);
}

.report-review-content {
  display: grid;
  gap: 12px;
}

.report-review-meta,
.report-review-current {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
}

.report-review-meta div,
.report-review-current div {
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
}

.report-review-meta dd,
.report-review-current dd {
  margin: 5px 0 0;
  overflow-wrap: anywhere;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 650;
  line-height: 1.45;
}

.report-review-current .wide {
  grid-column: 1 / -1;
}

.report-review-section {
  display: grid;
  gap: 9px;
  padding: 12px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  background: var(--surface);
}

.report-review-section h3 {
  font-size: 13px;
}

.report-review-summary,
.report-review-empty,
.report-review-evidence p,
.report-review-evidence small,
.report-review-history p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12.5px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.report-review-summary {
  white-space: pre-wrap;
}

.report-review-columns {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.report-review-list-section ul {
  display: grid;
  gap: 7px;
  margin: 0;
  padding-left: 18px;
  color: var(--text-secondary);
  font-size: 12.5px;
  line-height: 1.5;
}

.report-review-list-section.risk {
  border-left: 3px solid var(--danger);
}

.report-review-list-section.unknown {
  border-left: 3px solid #b36d0d;
}

.report-review-evidence-list {
  display: grid;
  gap: 8px;
}

.report-review-evidence {
  display: grid;
  gap: 7px;
  padding: 10px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
}

.report-review-evidence > div:first-child strong {
  color: var(--text-primary);
  font-size: 12px;
}

.report-review-evidence > div:first-child span,
.report-review-evidence small {
  color: var(--text-muted);
}

.report-review-evidence-ids {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.report-review-evidence-ids code {
  padding: 3px 6px;
  border-radius: 5px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 11px;
}

.report-review-evidence-ids em {
  color: var(--text-muted);
  font-size: 11px;
  font-style: normal;
}

.report-review-citations,
.report-review-history {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 22px;
  color: var(--text-secondary);
  font-size: 12.5px;
  line-height: 1.55;
}

.report-review-citations li {
  overflow-wrap: anywhere;
}

.review-decision-section {
  background: var(--surface-raised);
}

.report-review-comment {
  display: grid;
  gap: 7px;
}

.report-review-comment textarea {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  padding: 10px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-primary);
  font: inherit;
  font-size: 12.5px;
  line-height: 1.5;
}

.report-review-comment textarea:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
  outline: none;
}

.report-review-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.report-review-button {
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.report-review-button.secondary {
  border-color: var(--border-soft);
  background: var(--surface);
  color: var(--text-secondary);
}

.report-review-button.positive {
  background: var(--positive);
}

.report-review-button.warning {
  background: #b36d0d;
}

.report-review-button.danger {
  background: var(--danger);
}

.report-review-button:disabled {
  cursor: wait;
  opacity: 0.58;
}

.report-review-save-error {
  margin: 0;
  color: var(--danger);
  font-size: 12px;
  font-weight: 650;
}

.report-review-history li {
  display: grid;
  grid-template-columns: auto minmax(100px, 1fr) auto;
  gap: 6px 10px;
  align-items: center;
  padding: 9px 0;
  border-bottom: 1px solid var(--border-soft);
}

.report-review-history li:last-child {
  border-bottom: 0;
}

.report-review-history strong,
.report-review-history time,
.report-review-transition {
  font-size: 11.5px;
}

.report-review-history time,
.report-review-transition {
  color: var(--text-muted);
}

.report-review-transition,
.report-review-history p {
  grid-column: 1 / -1;
}

@media (max-width: 760px) {
  .report-review-meta,
  .report-review-current,
  .report-review-columns {
    grid-template-columns: 1fr;
  }

  .report-review-current .wide {
    grid-column: auto;
  }

  .report-review-history li {
    grid-template-columns: 1fr;
  }

  .report-review-history p {
    grid-column: auto;
  }

  .report-review-transition {
    grid-column: auto;
  }
}
</style>
