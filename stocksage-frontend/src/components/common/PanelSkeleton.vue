<template>
  <div class="panel-skeleton" :style="{ minHeight }" aria-hidden="true">
    <span v-for="i in lines" :key="i" class="skeleton-bar" :style="barStyle(i)"></span>
  </div>
</template>

<script setup>
/**
 * PanelSkeleton — 面板加载骨架屏
 * 替代文字占位("正在读取…"/巨字 ticker),加载中呈现内容轮廓。
 */
const props = defineProps({
  lines: { type: Number, default: 3 },
  minHeight: { type: String, default: '' },
})

const WIDTHS = ['92%', '74%', '85%', '62%', '88%', '70%']

function barStyle(index) {
  return { width: WIDTHS[(index - 1) % WIDTHS.length] }
}
</script>

<style scoped>
.panel-skeleton {
  display: grid;
  gap: 10px;
  align-content: center;
  padding: 10px 0;
}

.skeleton-bar {
  height: 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-raised);
  animation: skeleton-breathe 1.2s ease-in-out infinite;
}

@keyframes skeleton-breathe {
  0%, 100% { opacity: 0.55; }
  50% { opacity: 1; }
}
</style>
