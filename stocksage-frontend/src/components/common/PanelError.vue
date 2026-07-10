<template>
  <div class="panel-error" :style="{ minHeight }">
    <el-icon class="panel-error-icon"><WarningFilled /></el-icon>
    <strong>{{ message }}</strong>
    <button v-if="retryable" class="panel-error-retry" type="button" @click="emit('retry')">
      <el-icon><Refresh /></el-icon>
      <span>重试</span>
    </button>
    <details v-if="detail" class="panel-error-detail">
      <summary>技术详情</summary>
      <code>{{ detail }}</code>
    </details>
  </div>
</template>

<script setup>
/**
 * PanelError — 面板错误态
 * 人话标题 + 重试按钮;原始错误(HTTP 码等)只允许出现在"技术详情"折叠里。
 */
import { Refresh, WarningFilled } from '@element-plus/icons-vue'

const props = defineProps({
  message: { type: String, required: true },
  detail: { type: String, default: '' },
  retryable: { type: Boolean, default: true },
  minHeight: { type: String, default: '160px' },
})

const emit = defineEmits(['retry'])
</script>

<style scoped>
.panel-error {
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 8px;
  padding: 16px;
  text-align: center;
}

.panel-error-icon {
  font-size: 24px;
  color: var(--warning);
}

.panel-error strong {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
}

.panel-error-retry {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  background: var(--surface);
  color: var(--text-secondary);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.panel-error-retry:hover {
  border-color: var(--border-strong);
  background: var(--surface-raised);
  color: var(--text-primary);
}

.panel-error-detail {
  max-width: 100%;
}

.panel-error-detail summary {
  color: var(--text-muted);
  font-size: 11px;
  cursor: pointer;
}

.panel-error-detail code {
  display: block;
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: var(--radius-sm);
  background: var(--surface-raised);
  color: var(--text-muted);
  font-size: 11px;
  overflow-wrap: anywhere;
  text-align: left;
}
</style>
