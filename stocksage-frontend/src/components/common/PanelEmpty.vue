<template>
  <div class="panel-empty" :style="{ minHeight }">
    <el-icon class="panel-empty-icon">
      <slot name="icon"><FolderOpened /></slot>
    </el-icon>
    <strong>{{ title }}</strong>
    <p v-if="hint">{{ hint }}</p>
    <slot name="action"></slot>
  </div>
</template>

<script setup>
/**
 * PanelEmpty — 面板空态
 * 图标 + 标题 + 一句引导,可通过 action slot 附带动作按钮。
 */
import { FolderOpened } from '@element-plus/icons-vue'

const props = defineProps({
  title: { type: String, required: true },
  hint: { type: String, default: '' },
  minHeight: { type: String, default: '160px' },
})
</script>

<style scoped>
.panel-empty {
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 6px;
  padding: 16px;
  text-align: center;
}

.panel-empty-icon {
  font-size: 26px;
  color: var(--text-muted);
  margin-bottom: 2px;
}

.panel-empty strong {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
}

.panel-empty p {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
  max-width: 320px;
}
</style>
