<template>
  <section
    class="workbench-run-panel"
    :class="{ collapsed: isCollapsed }"
    aria-live="polite"
  >
    <div class="panel-header">
      <div>
        <h2>{{ run.title }}</h2>
      </div>
      <div class="run-actions">
        <span class="status-pill" :class="runTone">{{ runStatusLabel }}</span>
        <button
          v-if="controls.canCollapse"
          class="quiet-button icon-only"
          type="button"
          :aria-label="isCollapsed ? '展开执行结果' : '收起执行结果'"
          :title="isCollapsed ? '展开' : '收起'"
          @click="emit('toggle')"
        >
          <el-icon><ArrowDown v-if="isCollapsed" /><ArrowUp v-else /></el-icon>
        </button>
        <button
          v-if="controls.canStop"
          class="quiet-button"
          type="button"
          @click="emit('stop')"
        >
          <el-icon><VideoPause /></el-icon>
          <span>停止</span>
        </button>
        <button
          v-if="controls.canDismiss"
          class="quiet-button icon-only"
          type="button"
          aria-label="关闭执行结果"
          title="关闭"
          @click="emit('dismiss')"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>

    <template v-if="!isCollapsed">
      <div class="run-meta">
        <span v-if="run.ticker">标的：{{ run.ticker }}</span>
        <span v-if="run.modelName">模型：{{ run.modelTier || '模型' }} / {{ run.modelName }}</span>
        <span v-if="run.conversationId">会话：#{{ run.conversationId }}</span>
        <span v-if="run.traceId">Trace：{{ run.traceId }}</span>
      </div>

      <div v-if="runTimeline.length" class="run-timeline">
        <div
          v-for="item in runTimeline.slice(-6)"
          :key="`${item.kind}-${item.label}-${item.detail}`"
          class="run-step"
        >
          <span>{{ item.label }}</span>
          <p>{{ item.detail }}</p>
          <small v-if="item.meta">{{ item.meta }}</small>
        </div>
      </div>

      <div class="run-answer">
        <strong>{{ run.status === 'running' && !run.answer ? '正在生成研究结果' : '研究结果' }}</strong>
        <div class="markdown-body" v-html="renderedAnswer"></div>
        <p v-if="run.error" class="run-error">{{ run.error }}</p>
      </div>
    </template>
  </section>
</template>

<script setup>
/**
 * WorkbenchRunPanel — 全局运行面板
 *
 * props:
 *   run       {Object}   useResearchRun 的 run ref 值（run.value），字段：
 *                        visible, status, title, ticker, answer, timeline,
 *                        modelTier, modelName, traceId, conversationId, error
 *   controls  {Object}   useResearchRun 的 controls computed 值，字段：
 *                        canStop, canDismiss, canCollapse
 *   collapsed {Boolean}  折叠态（由父组件持有并透传）
 *
 * emits:
 *   stop    — 用户点击停止
 *   dismiss — 用户点击关闭
 *   toggle  — 用户点击收起/展开
 */
import { computed } from 'vue'
import { ArrowDown, ArrowUp, Close, VideoPause } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  run: {
    type: Object,
    required: true,
  },
  controls: {
    type: Object,
    required: true,
  },
  collapsed: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['stop', 'dismiss', 'toggle'])

const isCollapsed = computed(() => props.collapsed)

const runTimeline = computed(() => props.run.timeline || [])

const runStatusLabel = computed(() => ({
  idle: '待启动',
  running: '运行中',
  completed: '已完成',
  failed: '失败',
  stopped: '已停止',
}[props.run.status] || '待启动'))

const runTone = computed(() => ({
  running: 'running',
  completed: 'positive',
  failed: 'danger',
  stopped: 'pending',
}[props.run.status] || 'pending'))

const renderedAnswer = computed(() => {
  const content = props.run.answer || '等待模型返回结论。运行中可在上方看到读取数据、检索证据和生成报告的进度。'
  try {
    const rawHtml = marked.parse(content)
    return DOMPurify.sanitize(rawHtml)
  } catch (error) {
    console.error('Markdown parsing failed:', error)
    return content
  }
})
</script>

<style scoped>
.workbench-run-panel {
  position: fixed;
  z-index: 35;
  bottom: 24px;
  right: 24px;
  left: 272px;
  max-height: min(560px, calc(100vh - 140px));
  overflow: auto;
  padding: 20px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  background: rgba(255, 255, 255, 0.85);
  box-shadow: var(--shadow-command);
  backdrop-filter: blur(16px);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.dark .workbench-run-panel {
  background: rgba(17, 24, 39, 0.85);
}

.workbench-run-panel.collapsed {
  max-height: none;
  overflow: visible;
  padding: 12px 16px;
}

.workbench-run-panel.collapsed .panel-header {
  margin-bottom: 0;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-kicker {
  margin: 0;
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.panel-header h2 {
  margin: 3px 0 0;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
}

.run-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.status-pill {
  min-height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 8px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  white-space: nowrap;
}

.status-pill.positive {
  border-color: transparent;
  background: var(--positive-soft);
  color: var(--positive);
}

.status-pill.danger {
  border-color: transparent;
  background: var(--negative-soft);
  color: var(--negative);
}

.status-pill.running {
  border-color: transparent;
  background: var(--info-soft);
  color: var(--info);
  animation: pulse-glow 2s infinite ease-in-out;
}

@keyframes pulse-glow {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.status-pill.pending {
  border-color: transparent;
  background: var(--surface-raised);
  color: var(--text-muted);
}

.quiet-button {
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-secondary);
  font-weight: 600;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.quiet-button:hover {
  background: var(--surface-raised);
  color: var(--text-primary);
  border-color: var(--border-strong);
}

.quiet-button.icon-only {
  width: 34px;
  padding: 0;
}

.run-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
}

.run-meta span {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface-raised);
}

.run-timeline {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 14px;
}

.run-step {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-raised);
}

.run-step span {
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
}

.run-step p {
  margin: 4px 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.run-step small {
  color: var(--text-muted);
  font-weight: 500;
}

.run-answer {
  margin-top: 14px;
}

.run-answer strong {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}

.markdown-body {
  max-height: 280px;
  margin: 0;
  overflow: auto;
  padding: 16px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface);
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
}

.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4 {
  margin-top: 14px;
  margin-bottom: 6px;
  font-weight: 700;
  color: var(--text-primary);
}

.markdown-body h1 { font-size: 1.25em; }
.markdown-body h2 { font-size: 1.15em; border-bottom: 1px solid var(--border-soft); padding-bottom: 3px; }
.markdown-body h3 { font-size: 1.05em; }

.markdown-body p {
  margin-top: 0;
  margin-bottom: 6px;
}

.markdown-body ul,
.markdown-body ol {
  margin-top: 0;
  margin-bottom: 6px;
  padding-left: 20px;
}

.markdown-body li {
  margin-bottom: 3px;
}

.markdown-body code {
  padding: 2px 4px;
  background: var(--surface-raised);
  border-radius: 4px;
  font-family: Consolas, monospace;
  font-size: 0.9em;
}

.markdown-body pre {
  margin: 8px 0;
  padding: 12px;
  background: var(--surface-raised);
  border-radius: 6px;
  overflow: auto;
}

.markdown-body pre code {
  padding: 0;
  background: transparent;
  font-size: 0.85em;
}

.markdown-body table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 6px;
  margin-bottom: 12px;
}

.markdown-body th,
.markdown-body td {
  border: 1px solid var(--border-soft);
  padding: 6px 10px;
  text-align: left;
}

.markdown-body th {
  background: var(--accent-soft);
  font-weight: 700;
}

.markdown-body blockquote {
  margin: 8px 0;
  padding-left: 12px;
  border-left: 3px solid var(--accent);
  color: var(--text-secondary);
  background: var(--accent-soft);
}

.run-error {
  margin: 8px 0 0;
  color: var(--danger);
  font-weight: 700;
  font-size: 13px;
}

@media (max-width: 1080px) {
  .workbench-run-panel {
    right: 12px;
    left: 12px;
    bottom: 12px;
    max-height: min(560px, calc(100vh - 72px));
  }

  .run-timeline {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .workbench-run-panel {
    right: 10px;
    left: 10px;
    bottom: 10px;
    max-height: min(520px, calc(100vh - 36px));
  }

  .run-actions {
    width: 100%;
  }

  .markdown-body {
    max-height: 220px;
  }
}
</style>
