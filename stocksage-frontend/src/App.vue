<template>
  <div class="app-container">
    <router-view />
  </div>
</template>

<script setup>
import { onMounted } from 'vue'

// 根壳层承载路由与全局主题初始化;暗黑模式在对话页与工作台之间共享同一存取键。
onMounted(() => {
  const saved = window.localStorage.getItem('stocksage.theme')
  document.documentElement.classList.toggle('dark', saved === 'dark')
})
</script>

<style>
:root {
  /* ==========================================================================
     设计系统变量 - 默认明亮模式 (Modern Premium Light)
     ========================================================================== */
  --app-bg: #f8fafc;
  --surface: #ffffff;
  --surface-raised: #f1f5f9;
  --panel-bg: #f1f5f9;
  --panel-hover: #e2e8f0;
  --panel-active: #cbd5e1;
  --text-primary: #0f172a;
  --text-secondary: #475569;
  --text-muted: #94a3b8;
  --border-soft: #e2e8f0;
  --border-strong: #cbd5e1;
  
  /* 品牌色与状态色 */
  --accent: #059669;
  --accent-dark: #047857;
  --accent-soft: rgba(16, 185, 129, 0.08);
  
  --up: #089981;
  --down: #f23645;
  --positive: #089981;
  --positive-soft: rgba(8, 153, 129, 0.08);
  --negative: #f23645;
  --negative-soft: rgba(242, 54, 69, 0.08);
  --warning: #f59e0b;
  --warning-soft: rgba(245, 158, 11, 0.08);
  --info: #3b82f6;
  --info-soft: rgba(59, 130, 246, 0.08);
  --danger: #ef4444;

  /* 阴影与圆角 */
  --shadow-soft: 0 10px 30px -10px rgba(0, 0, 0, 0.04), 0 1px 3px rgba(0, 0, 0, 0.02);
  --shadow-command: 0 20px 40px -15px rgba(15, 23, 42, 0.08);
  --radius-lg: 10px;
  --radius-md: 8px;
  --radius-sm: 6px;
  --content-width: 860px;
}

.dark {
  /* ==========================================================================
     设计系统变量 - 暗黑模式覆盖 (Modern Premium Dark)
     ========================================================================== */
  --app-bg: #090d16;
  --surface: #111827;
  --surface-raised: #1f2937;
  --panel-bg: #1f2937;
  --panel-hover: #374151;
  --panel-active: #4b5563;
  --text-primary: #f9fafb;
  --text-secondary: #d1d5db;
  --text-muted: #6b7280;
  --border-soft: #273244;
  --border-strong: #374151;
  
  /* 品牌色与状态色 */
  --accent: #34d399;
  --accent-dark: #059669;
  --accent-soft: rgba(52, 211, 153, 0.12);
  
  --up: #16c784;
  --down: #ea3943;
  --positive: #16c784;
  --positive-soft: rgba(22, 199, 132, 0.1);
  --negative: #ea3943;
  --negative-soft: rgba(234, 57, 67, 0.1);
  --warning: #fbbf24;
  --warning-soft: rgba(251, 191, 36, 0.08);
  --info: #60a5fa;
  --info-soft: rgba(96, 165, 250, 0.08);
  --danger: #f87171;

  /* 阴影与圆角 */
  --shadow-soft: 0 10px 30px -10px rgba(0, 0, 0, 0.3), 0 1px 3px rgba(0, 0, 0, 0.1);
  --shadow-command: 0 20px 45px -15px rgba(0, 0, 0, 0.5);
}

html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
  background-color: var(--app-bg);
  color: var(--text-primary);
  font-family: "Inter", "Outfit", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  letter-spacing: -0.011em;
  transition: background-color 0.3s ease, color 0.3s ease;
}

body {
  overflow: hidden;
}

.app-container {
  height: 100%;
}

.primary-nav {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  background: var(--surface);
  box-shadow: var(--shadow-soft);
  transition: all 0.3s ease;
}

.primary-nav-link {
  min-height: 31px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 14px;
  border-radius: 999px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
  transition: background 0.2s ease, color 0.2s ease, box-shadow 0.2s ease;
}

.primary-nav-link:hover {
  color: var(--text-primary);
  background: var(--surface-raised);
}

.primary-nav-link.active {
  background: var(--accent);
  color: #ffffff;
  box-shadow: 0 4px 12px var(--accent-soft);
}

.primary-nav-link .el-icon {
  font-size: 14px;
}

button,
textarea,
input {
  font: inherit;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background-color 0.2s ease, color 0.2s ease;
}

button:focus-visible,
textarea:focus-visible,
input:focus-visible {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

* {
  box-sizing: border-box;
}

/* 数字单元格必挂:等宽数字,小数点对齐、刷新不跳动 */
.num {
  font-variant-numeric: tabular-nums;
}

*::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

*::-webkit-scrollbar-thumb {
  background: var(--border-strong);
  border-radius: 999px;
}

*::-webkit-scrollbar-thumb:hover {
  background: var(--text-muted);
}

*::-webkit-scrollbar-track {
  background: transparent;
}

a {
  color: inherit;
}

@media (max-width: 720px) {
  .primary-nav {
    gap: 2px;
    padding: 2px;
  }

  .primary-nav-link {
    min-width: 34px;
    padding: 0 8px;
  }

  .primary-nav-link span {
    display: none;
  }
}
</style>
