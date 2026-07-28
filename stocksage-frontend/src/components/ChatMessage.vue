<template>
  <div class="message" :class="message.role">
    <div class="message-avatar">
      <el-icon v-if="message.role === 'user'"><UserFilled /></el-icon>
      <el-icon v-else><TrendCharts /></el-icon>
    </div>
    <div class="message-block">
      <div class="message-meta-row">
        <div class="message-name">{{ roleName }}</div>
        <el-tooltip v-if="modelBadgeText" :content="modelTooltip" placement="top">
          <span class="model-badge" :class="modelTierClass">
            <span class="model-tier">{{ modelTierLabel }}</span>
            <span class="model-id">{{ modelBadgeText }}</span>
          </span>
        </el-tooltip>
      </div>

      <section v-if="task" class="task-card" :class="taskTone">
        <div class="task-card-header">
          <span class="task-status-dot"></span>
          <strong>{{ task.ticker ? `${task.ticker} 后台研究` : '后台研究任务' }}</strong>
          <span>{{ taskStatusLabel }}</span>
        </div>
        <div class="task-card-progress">
          <span>当前阶段：{{ taskStageLabel }}</span>
          <small v-if="task.taskId">任务 #{{ task.taskId }}</small>
        </div>
        <p v-if="task.connection === 'reconnecting'" class="task-connection-note">连接中断，正在从上次事件位置恢复…</p>
        <p v-else-if="task.connection === 'paused'" class="task-connection-note">已停止观看，后台任务仍可继续运行。</p>
        <p v-if="task.errorMessage" class="task-error">{{ task.errorMessage }}</p>
      </section>

      <div v-if="assistantEvidence.visible" class="evidence-strip">
        <span
          v-for="badge in assistantEvidence.badges"
          :key="`${badge.label}-${badge.value}`"
          class="evidence-badge"
          :class="badge.tone"
          :title="badge.detail"
        >
          <span>{{ badge.label }}</span>
          <strong>{{ badge.value }}</strong>
        </span>
      </div>

      <!-- 思考过程折叠面板（点击展开，显示完整链路） -->
      <section v-if="hasReasoning" class="reasoning-panel" :class="{ open: reasoningOpen }">
        <button class="reasoning-toggle" type="button" @click="toggleReasoning">
          <el-icon class="reasoning-toggle-icon" :class="{ open: reasoningOpen }"><ArrowRight /></el-icon>
          <span>{{ reasoningOpen ? '收起思考过程' : '查看思考过程' }}</span>
          <span v-if="reasoningLoading" class="reasoning-status">加载中</span>
        </button>

        <div v-if="reasoningOpen" class="reasoning-content">
          <div v-if="reasoningLoading" class="reasoning-loading">
            <span class="loading-dot"></span>
            <span>正在整理思考过程...</span>
          </div>
          <p v-else-if="reasoningError" class="reasoning-error">{{ reasoningError }}</p>
          <div v-else-if="researchTimeline.length > 0" class="research-timeline">
            <div v-for="(item, idx) in researchTimeline" :key="idx" class="timeline-row" :class="item.kind">
              <span class="timeline-marker"></span>
              <span class="timeline-copy">
                <span class="timeline-label">{{ item.label }}</span>
                <span class="timeline-detail">{{ item.detail }}</span>
                <span v-if="item.meta" class="timeline-meta">{{ item.meta }}</span>
              </span>
            </div>
          </div>
          <p v-else class="reasoning-empty">暂无可展示的思考过程</p>
          <div v-if="traceSummary" class="trace-summary">
            <span>{{ traceSummary.steps }} 步</span>
            <span class="trace-sep">·</span>
            <span>{{ formatDuration(traceSummary.durationMs) }}</span>
            <template v-if="traceSummary.tokens > 0">
              <span class="trace-sep">·</span>
              <span>~{{ traceSummary.tokens.toLocaleString() }} tokens</span>
            </template>
          </div>
        </div>
      </section>

      <div v-if="messageCharts.length > 0" class="message-charts">
        <KLineChart
          v-for="(chart, idx) in messageCharts"
          :key="chart.signature || idx"
          :chart="chart"
        />
      </div>

      <!-- 用户消息：普通展示 / 编辑模式 -->
      <div v-if="editing" class="edit-area">
        <textarea
          ref="editTextarea"
          v-model="editText"
          class="edit-input"
          rows="3"
          @keydown.enter.exact.prevent="submitEdit"
          @keydown.escape="cancelEdit"
        ></textarea>
        <div class="edit-actions">
          <button class="edit-btn edit-btn-primary" type="button" @click="submitEdit">发送</button>
          <button class="edit-btn" type="button" @click="cancelEdit">取消</button>
        </div>
      </div>

      <div v-else-if="imageAttachments.length > 0" class="message-attachments">
        <img
          v-for="image in imageAttachments"
          :key="image.dataUrl"
          class="message-attachment-image"
          :src="image.dataUrl"
          :alt="image.name || '上传图片'"
        />
      </div>

      <article
        v-if="!editing && message.content"
        class="message-content"
        v-html="renderedContent"
      ></article>

      <!-- 编辑按钮（用户消息） -->
      <button
        v-if="showEdit && !editing"
        class="edit-message-button"
        type="button"
        @click="startEdit"
      >
        <el-icon><EditPen /></el-icon>
      </button>

      <button
        v-if="showCopy"
        class="retry-button copy-button"
        type="button"
        @click="copyMarkdown"
      >
        <el-icon><DocumentCopy /></el-icon>
        <span>复制 Markdown</span>
      </button>

      <button
        v-if="showRetry"
        class="retry-button"
        type="button"
        @click="$emit('retry')"
      >
        <el-icon><RefreshRight /></el-icon>
        <span>重新生成</span>
      </button>
    </div>
  </div>
</template>

<script setup>
/**
 * 单条对话消息渲染器。
 *
 * 职责：
 * - 使用 marked + DOMPurify 安全渲染助手 Markdown。
 * - 回答进行中时展示内联流式推理数据块。
 * - 仅在推理面板打开时懒加载持久化链路详情。
 * - 将重试/编辑动作发送回拥有会话状态的父组件。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { ElMessage } from 'element-plus'
import { ArrowRight, DocumentCopy, EditPen, RefreshRight, TrendCharts, UserFilled } from '@element-plus/icons-vue'
import { getTrace } from '../api/chat.js'
import { markdownToPlainText, normalizeMarkdownEmphasis } from '../lib/markdown.js'
import { buildAssistantEvidenceSummary, buildResearchTimeline } from '../lib/researchUi.js'
import KLineChart from './KLineChart.vue'

const props = defineProps({
  message: { type: Object, required: true },
  showRetry: { type: Boolean, default: false },
  showEdit: { type: Boolean, default: false },
})

const emit = defineEmits(['retry', 'edit'])

const editing = ref(false)
const editText = ref('')
const editTextarea = ref(null)

function startEdit() {
  editText.value = props.message.content
  editing.value = true
  nextTick(() => editTextarea.value?.focus())
}

function cancelEdit() {
  editing.value = false
}

function submitEdit() {
  const text = editText.value.trim()
  if (!text) return
  editing.value = false
  emit('edit', text)
}

const roleName = computed(() => {
  if (props.message.role === 'user') return '你'
  return props.message.isTaskFinal ? 'StockSage · 研究报告' : 'StockSage'
})
const imageAttachments = computed(() => {
  if (!Array.isArray(props.message.images)) return []
  return props.message.images.filter(image => image?.dataUrl)
})
const modelTier = computed(() => String(props.message.modelTier || '').toUpperCase())
const modelBadgeText = computed(() => props.message.role === 'assistant' ? (props.message.modelName || modelTier.value) : '')
const modelTierLabel = computed(() => ({
  FAST: '轻量',
  STANDARD: '标准',
  STRONG: '深度',
}[modelTier.value] || '模型'))
const modelTierClass = computed(() => `tier-${modelTier.value.toLowerCase() || 'unknown'}`)
const modelTooltip = computed(() => {
  const tier = modelTier.value ? `层级：${modelTier.value}` : '层级：未知'
  const model = props.message.modelName ? `模型：${props.message.modelName}` : '模型：未返回'
  return `${tier}，${model}`
})
const showCopy = computed(() => props.message.role === 'assistant' && Boolean(props.message.content?.trim()))
const messageCharts = computed(() => Array.isArray(props.message.charts) ? props.message.charts : [])
const assistantEvidence = computed(() => buildAssistantEvidenceSummary(props.message))
const task = computed(() => props.message.role === 'assistant' ? (props.message.task || null) : null)
const taskStatusLabel = computed(() => ({
  PENDING: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}[String(task.value?.status || '').toUpperCase()] || '处理中'))
const taskStageLabel = computed(() => ({
  CREATED: '已创建',
  DATA_PREFETCH: '读取研究数据',
  AGENT_DEBATE: '多空辩论',
  REPORT_SYNTHESIS: '综合研究结论',
  REPORT_PERSIST: '保存报告',
  COMPLETE: '完成',
  FAILED: '失败',
}[String(task.value?.stage || '').toUpperCase()] || String(task.value?.stage || '准备中').replaceAll('_', ' ')))
const taskTone = computed(() => {
  const status = String(task.value?.status || '').toUpperCase()
  if (status === 'FAILED') return 'danger'
  if (status === 'SUCCEEDED') return 'positive'
  return task.value?.connection === 'reconnecting' ? 'reconnecting' : 'running'
})
// Markdown 是模型生成内容，进入 v-html 前必须先消毒。
const renderedContent = computed(() => DOMPurify.sanitize(
  marked.parse(normalizeMarkdownEmphasis(props.message.content || ''), { async: false }),
))

async function copyMarkdown() {
  try {
    await navigator.clipboard.writeText(props.message.content || '')
    ElMessage.success('已复制 Markdown')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

const reasoningOpen = ref(false)
const reasoningLoading = ref(false)
const reasoningError = ref(null)
const traceReasoning = ref([])
const traceTokens = ref(0)
let traceFetched = false

const inlineReasoning = computed(() => normalizeReasoning(props.message.reasoning || []))
const displayReasoning = computed(() => [...inlineReasoning.value, ...traceReasoning.value])
const hasReasoning = computed(() => {
  return props.message.role === 'assistant'
    && (inlineReasoning.value.length > 0 || Boolean(props.message.traceId) || messageCharts.value.length > 0)
})
const researchTimeline = computed(() => buildResearchTimeline({
  reasoning: displayReasoning.value,
  charts: messageCharts.value,
  hasAnswer: Boolean(String(props.message.content || '').trim()),
}))

// 流式推理（含多空辩论）首次到达时自动展开一次。后台任务可能先返回受理文本，
// 因此不能用“回答尚未开始”作为展开条件。
let reasoningAutoOpened = false
watch(
  () => inlineReasoning.value.length,
  (len) => {
    if (len > 0 && !reasoningAutoOpened) {
      reasoningOpen.value = true
      reasoningAutoOpened = true
    }
  }
)

watch(
  () => props.message.traceId,
  () => {
    traceFetched = false
    traceReasoning.value = []
    traceTokens.value = 0
    reasoningError.value = null
  }
)

async function toggleReasoning() {
  reasoningOpen.value = !reasoningOpen.value
  if (reasoningOpen.value && props.message.traceId && !traceFetched) {
    // 链路详情按需加载，因为长对话中多数用户不会打开每个推理面板。
    await loadTraceReasoning()
  }
}

async function loadTraceReasoning() {
  reasoningLoading.value = true
  reasoningError.value = null
  try {
    const trace = await getTrace(props.message.traceId)
    traceTokens.value = trace.totalTokens || 0
    traceReasoning.value = traceToReasoning(trace)
    traceFetched = true
  } catch (e) {
    reasoningError.value = `加载失败：${e.message}`
  } finally {
    reasoningLoading.value = false
  }
}

function normalizeReasoning(items) {
  return items
    .map(item => ({
      type: item.type || 'thought',
      // 多空辩论流式块带有展示标题（如“看多方 · 第 1 轮”），优先于按 type 推断的通用标签。
      label: item.label || null,
      content: textFrom(item.content),
      metadata: item.metadata || null,
    }))
    .filter(item => item.content)
}

function traceToReasoning(trace) {
  let steps = []
  try {
    steps = JSON.parse(trace.steps || '[]')
  } catch {
    return []
  }

  // 持久化链路步骤比流式数据块更丰富；这里压平成实时界面共用的 thought/action/observation 形态。
  return steps.flatMap((step) => {
    const rows = []
    const dur = step.durationMs || 0
    if (step.attributes?.kind === 'routing-decision') {
      rows.push({
        type: 'route_decision',
        content: formatRouteDecision(step.attributes),
        metadata: step.attributes,
        durationMs: dur,
      })
      return rows
    }
    if (step.thought) rows.push({
      type: 'thought',
      content: textFrom(step.thought),
      durationMs: 0,
      metadata: step.attributes || null,
    })
    if (step.action) rows.push({
      type: 'action',
      content: formatAction(step),
      durationMs: dur,
      metadata: step.attributes || null,
    })
    if (step.observation) rows.push({
      type: 'observation',
      content: textFrom(step.observation),
      durationMs: 0,
      metadata: step.attributes || null,
    })
    return rows
  })
}

const traceSummary = computed(() => {
  if (!traceFetched || traceReasoning.value.length === 0) return null
  // 重新解析链路步骤，拿到摘要需要的原始数据。
  // 这里用闭包安全的方式访问最近加载的链路。
  const allItems = traceReasoning.value
  const steps = allItems.filter(i => i.type === 'action').length || allItems.length
  const totalDuration = allItems.reduce((sum, i) => sum + (i.durationMs || 0), 0)
  // 令牌数来自加载期间保存的链路对象。
  const tokens = traceTokens.value
  return { steps, durationMs: totalDuration, tokens }
})

function formatAction(step) {
  const actionInput = textFrom(step.actionInput)
  return actionInput ? `${step.action}：${actionInput}` : textFrom(step.action)
}

function formatRouteDecision(metadata = {}) {
  const lines = [
    `意图理解：${metadata.intentSummary || '未提供'}`,
    `选择路由：${metadata.route || 'DIRECT'}`,
    `决策来源：${metadata.source || 'UNKNOWN'}`,
    `置信度：${Number(metadata.confidence || 0).toFixed(2)}`,
    `依据：${metadata.rationale || '未提供'}`,
    `RAG 命中：${Number(metadata.ragHitCount || 0)}`,
  ]
  if (metadata.fallbackReason) lines.push(`降级原因：${metadata.fallbackReason}`)
  return lines.join('\n')
}

function textFrom(value) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return markdownToPlainText(value)
  return JSON.stringify(value, null, 2)
}

function formatDuration(ms) {
  if (!ms || ms <= 0) return ''
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

</script>

<style scoped>
.message {
  display: flex;
  gap: 16px;
  width: min(var(--content-width), 100%);
  margin: 0 auto 26px;
  animation: message-in 0.22s ease both;
}

.message.user {
  flex-direction: row-reverse;
  justify-content: flex-start;
}

.message-avatar {
  width: 34px;
  height: 34px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
  border: 1px solid var(--border-soft);
  color: var(--text-secondary);
  background: var(--surface);
}

.message-avatar .el-icon {
  font-size: 16px;
}

.message.user .message-avatar {
  background: var(--text-primary);
  border-color: var(--text-primary);
  color: var(--surface);
}

.message.assistant .message-avatar {
  background: var(--accent);
  border-color: var(--accent);
  color: #ffffff;
}

.message-block {
  min-width: 0;
  max-width: 800px;
  color: var(--text-primary);
}

.message.user .message-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.message-meta-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 24px;
  margin-bottom: 8px;
}

.message-name {
  margin-bottom: 8px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 720;
}

.message-meta-row .message-name {
  margin-bottom: 0;
}

.model-badge {
  display: inline-flex;
  align-items: center;
  max-width: min(340px, 100%);
  overflow: hidden;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 720;
  line-height: 1.35;
  box-shadow: var(--shadow-soft);
}

.model-tier {
  flex: 0 0 auto;
  padding: 3px 7px;
  background: rgba(32, 106, 89, 0.1);
  color: var(--accent-dark);
}

.model-id {
  min-width: 0;
  padding: 3px 8px 3px 7px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-badge.tier-fast .model-tier {
  background: rgba(61, 122, 191, 0.12);
  color: var(--info);
}

.model-badge.tier-strong .model-tier {
  background: rgba(176, 106, 46, 0.13);
  color: #9a5d29;
}

.task-card {
  display: grid;
  gap: 7px;
  margin: 0 0 12px;
  padding: 11px 13px;
  border: 1px solid rgba(36, 95, 157, 0.22);
  border-radius: 10px;
  background: var(--info-soft);
}

.task-card.reconnecting {
  border-color: rgba(154, 101, 0, 0.28);
  background: var(--warning-soft);
}

.task-card.positive {
  border-color: rgba(8, 127, 91, 0.24);
  background: var(--positive-soft);
}

.task-card.danger {
  border-color: rgba(185, 55, 55, 0.25);
  background: var(--negative-soft);
}

.task-card-header,
.task-card-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-card-header strong {
  color: var(--text-primary);
  font-size: 13px;
}

.task-card-header > span:last-child {
  margin-left: auto;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.task-status-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--info);
  box-shadow: 0 0 0 4px rgba(36, 95, 157, 0.12);
}

.task-card.reconnecting .task-status-dot {
  background: var(--warning);
  animation: pulse 1.2s ease-in-out infinite;
}

.task-card.positive .task-status-dot { background: var(--positive); }
.task-card.danger .task-status-dot { background: var(--danger); }

.task-card-progress {
  justify-content: space-between;
  color: var(--text-secondary);
  font-size: 12px;
}

.task-card-progress small {
  color: var(--text-muted);
}

.task-connection-note,
.task-error {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.55;
}

.task-error { color: var(--danger); }

.evidence-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: -2px 0 10px;
}

.evidence-badge {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1;
}

.evidence-badge span {
  font-weight: 760;
  text-transform: uppercase;
}

.evidence-badge strong {
  max-width: 130px;
  overflow: hidden;
  color: var(--text-primary);
  font-weight: 760;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-badge.info {
  border-color: rgba(36, 95, 157, 0.2);
  background: var(--info-soft);
}

.evidence-badge.market {
  border-color: rgba(154, 101, 0, 0.22);
  background: var(--warning-soft);
}

.evidence-badge.filing {
  border-color: rgba(36, 95, 157, 0.22);
}

.evidence-badge.knowledge {
  border-color: rgba(15, 125, 99, 0.22);
  background: var(--accent-soft);
}

/* 思考过程面板 */
.reasoning-panel {
  margin: 0 0 12px;
  color: var(--text-muted);
}

.reasoning-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 9px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-muted);
  cursor: pointer;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.5;
}

.reasoning-toggle:hover {
  background: var(--surface);
  color: var(--text-primary);
}

.reasoning-toggle-icon {
  font-size: 13px;
  transition: transform 0.16s ease;
}

.reasoning-toggle-icon.open {
  transform: rotate(90deg);
}

.reasoning-status {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}

.reasoning-content {
  margin: 8px 0 0;
  padding: 12px 14px;
  border: 1px solid var(--border-soft);
  border-left: 3px solid var(--info);
  border-radius: 10px;
  background: var(--surface-raised);
  animation: reasoning-in 0.16s ease both;
}

.research-timeline {
  display: grid;
  gap: 9px;
}

.timeline-row {
  position: relative;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 9px;
}

.timeline-row:not(:last-child)::after {
  content: '';
  position: absolute;
  left: 8px;
  top: 19px;
  bottom: -10px;
  width: 1px;
  background: var(--border-soft);
}

.timeline-marker {
  width: 17px;
  height: 17px;
  margin-top: 3px;
  border: 3px solid var(--surface);
  border-radius: 50%;
  background: var(--info);
  box-shadow: 0 0 0 1px rgba(36, 95, 157, 0.22);
}

.timeline-row.analysis .timeline-marker {
  background: var(--warning);
  box-shadow: 0 0 0 1px rgba(154, 101, 0, 0.24);
}

.timeline-row.evidence .timeline-marker,
.timeline-row.chart .timeline-marker,
.timeline-row.conclusion .timeline-marker {
  background: var(--positive);
  box-shadow: 0 0 0 1px rgba(8, 127, 91, 0.22);
}

.timeline-copy {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.timeline-label {
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0;
  text-transform: none;
}

.timeline-detail {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.timeline-meta {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
}

.reasoning-empty,
.reasoning-error,
.reasoning-loading {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.65;
}

.reasoning-row {
  white-space: pre-wrap;
}

.reasoning-label {
  margin-right: 8px;
  color: var(--accent-dark);
  font-weight: 700;
}

.reasoning-text {
  overflow-wrap: anywhere;
}

.reasoning-duration {
  margin-left: 8px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
  opacity: 0.75;
}

.trace-summary {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--border-soft);
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 600;
}

.trace-sep {
  margin: 0 4px;
  opacity: 0.5;
}

.reasoning-loading {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.reasoning-error {
  color: var(--danger);
}

.loading-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1.2s ease-in-out infinite;
}

.message-content {
  width: fit-content;
  max-width: 100%;
  line-height: 1.74;
  font-size: 15px;
  overflow-wrap: anywhere;
}

.message-charts {
  width: min(800px, 100%);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.message-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 100%;
  margin: 0 0 8px;
}

.message.user .message-attachments {
  justify-content: flex-end;
}

.message-attachment-image {
  display: block;
  max-width: min(320px, 100%);
  max-height: 260px;
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  background: var(--surface);
  object-fit: contain;
  box-shadow: var(--shadow-soft);
}

.message.user .message-attachment-image {
  border-color: var(--border-strong);
  border-radius: 16px 16px 5px 16px;
}

.message.user .message-content {
  padding: 10px 15px;
  border-radius: 16px 16px 5px 16px;
  background: var(--text-primary);
  color: var(--surface);
  line-height: 1.6;
  box-shadow: var(--shadow-soft);
}

.message.assistant .message-content {
  padding-top: 2px;
}

.message-content :deep(p) { margin: 0 0 12px; }
.message-content :deep(p:last-child),
.message-content :deep(ul:last-child),
.message-content :deep(ol:last-child) { margin-bottom: 0; }
.message-content :deep(ul),
.message-content :deep(ol) { margin: 8px 0 14px; padding-left: 22px; }
.message-content :deep(li) { margin: 4px 0; }
.message-content :deep(a) { color: var(--accent-dark); text-decoration: none; }
.message-content :deep(a:hover) { text-decoration: underline; }

.message-content :deep(table) {
  display: block;
  max-width: 100%;
  width: max-content;
  border: 1px solid var(--border-soft);
  border-collapse: separate;
  border-spacing: 0;
  margin: 14px 0;
  overflow: hidden;
  border-radius: 10px;
  background: var(--surface);
  box-shadow: var(--shadow-soft);
  overflow-x: auto;
}

.message-content :deep(th),
.message-content :deep(td) {
  border: 0;
  border-bottom: 1px solid var(--border-soft);
  padding: 10px 13px;
  text-align: left;
}

.message-content :deep(th) {
  background: var(--surface-raised);
  color: var(--text-primary);
  font-weight: 760;
}

.message-content :deep(tr:last-child td) {
  border-bottom: 0;
}

.message-content :deep(tbody tr:nth-child(even) td) {
  background: color-mix(in srgb, var(--surface-raised) 45%, var(--surface));
}

.message-content :deep(code) {
  background: var(--accent-soft);
  padding: 2px 5px;
  border-radius: 5px;
  color: var(--accent-dark);
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 13px;
}

.message-content :deep(pre) {
  margin: 12px 0;
  padding: 14px;
  border: 1px solid #25352f;
  border-radius: 10px;
  background: #111a17;
  color: #f4f4f5;
  overflow-x: auto;
}

.message-content :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.message-content :deep(blockquote) {
  margin: 12px 0;
  padding: 7px 12px;
  border-left: 3px solid var(--warning);
  border-radius: 8px;
  background: var(--warning-soft);
  color: var(--text-secondary);
}

/* 编辑按钮（用户消息悬浮显示） */
.message.user .message-block {
  position: relative;
}

.edit-message-button {
  position: absolute;
  left: -32px;
  bottom: 0;
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease, background 0.15s ease;
}

.message.user:hover .edit-message-button {
  opacity: 1;
}

.edit-message-button:hover {
  background: var(--panel-bg);
  color: var(--text-primary);
}

.edit-message-button .el-icon {
  font-size: 14px;
}

/* 编辑区域 */
.edit-area {
  width: 100%;
  max-width: 500px;
}

.edit-input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--accent);
  border-radius: 12px;
  background: var(--panel-bg);
  color: var(--text-primary);
  font-size: 14px;
  font-family: inherit;
  line-height: 1.6;
  resize: vertical;
  outline: none;
}

.edit-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  justify-content: flex-end;
}

.edit-btn {
  padding: 5px 14px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.edit-btn:hover {
  background: var(--panel-bg);
}

.edit-btn-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.edit-btn-primary:hover {
  opacity: 0.9;
  background: var(--accent);
}

/* 重新生成按钮 */
.retry-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: 10px;
  padding: 5px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.retry-button:hover {
  background: var(--surface-raised);
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.retry-button .el-icon {
  font-size: 14px;
}

@keyframes reasoning-in {
  from { opacity: 0; transform: translateY(-2px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes message-in {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes pulse {
  0%, 100% { transform: scale(0.88); opacity: 0.45; }
  50% { transform: scale(1); opacity: 1; }
}

@media (max-width: 720px) {
  .message { gap: 10px; margin-bottom: 20px; }
  .message-avatar { width: 28px; height: 28px; }
  .message-block { max-width: calc(100% - 38px); }
  .message-content { font-size: 14px; }
}
</style>
