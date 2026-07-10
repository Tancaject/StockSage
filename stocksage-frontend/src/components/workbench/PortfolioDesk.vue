/**
 * PortfolioDesk.vue
 * 持仓诊断次级入口：账户 ID 输入 + 只读边界提示 + 发起诊断。
 * 不暴露 prompt 原文。
 * emit: start-diagnosis({ accountId: string })
 */
<template>
  <section class="workspace-grid portfolio-grid">
    <div class="panel">
      <div class="panel-header">
        <div>
          <h2>持仓诊断</h2>
        </div>
        <button class="primary-button" type="button" @click="handleSubmit">
          <el-icon><Wallet /></el-icon>
          <span>诊断</span>
        </button>
      </div>

      <label class="field">
        <span>账户 ID</span>
        <input v-model="accountId" placeholder="可选" @keydown.enter="handleSubmit" />
      </label>
      <div class="preset-links">
        <span class="preset-label">快捷载入：</span>
        <button
          v-for="preset in PORTFOLIO_PRESETS"
          :key="preset.id"
          class="preset-tag"
          type="button"
          @click="accountId = preset.id"
        >
          {{ preset.name }}
        </button>
      </div>

      <div class="read-only-banner">
        <el-icon><Lock /></el-icon>
        <span>仅读取账户状态、账户摘要、持仓、报价和历史 K 线；不允许任何交易动作。</span>
      </div>

      <div class="insight-grid">
        <span>集中度</span>
        <span>单股风险</span>
        <span>行业/主题暴露</span>
        <span>市场敏感持仓</span>
        <span>公告/新闻跟踪</span>
      </div>

      <div class="settings-summary flat panel-footer-meta">
        <div class="summary-row">
          <span>账户</span>
          <strong>{{ accountSummary }}</strong>
        </div>
        <div class="summary-row">
          <span>数据源</span>
          <strong>IBKR 只读 API</strong>
        </div>
        <div class="summary-row">
          <span>分析维度</span>
          <strong>集中度 · 单股风险 · 行业暴露 · 敏感持仓 · 新闻跟踪</strong>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Lock, Wallet } from '@element-plus/icons-vue'

const PORTFOLIO_PRESETS = [
  { name: '科技成长组合', id: 'TECH-GROWTH' },
  { name: '高红利防守', id: 'DIVIDEND-DEF' },
]

const emit = defineEmits(['start-diagnosis'])

const accountId = ref('')

const accountSummary = computed(() =>
  accountId.value.trim() ? accountId.value.trim() : '默认账户'
)

function handleSubmit() {
  emit('start-diagnosis', { accountId: accountId.value.trim() })
}
</script>

<style scoped>
.read-only-banner {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--accent-soft);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 4px;
  transition: all 0.15s ease;
}

.read-only-banner .el-icon {
  flex-shrink: 0;
  margin-top: 1px;
  color: var(--accent);
}

.insight-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.insight-grid span {
  padding: 4px 12px;
  background: var(--surface-raised);
  border: 1px solid var(--border-soft);
  border-radius: 20px;
  font-size: 12px;
  color: var(--text-secondary);
  transition: all 0.15s ease;
}

.settings-summary.flat {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.panel-footer-meta {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--border-soft);
}

.summary-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12.5px;
  padding: 4px 0;
  border-bottom: 1px solid var(--border-soft);
}

.summary-row:last-child {
  border-bottom: none;
}

.summary-row span {
  color: var(--text-muted);
  flex-shrink: 0;
}

.summary-row strong {
  text-align: right;
  word-break: break-word;
}

.preset-links {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.preset-label {
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 700;
}

.preset-tag {
  background: var(--accent-soft);
  border: 1px solid transparent;
  color: var(--text-secondary);
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s ease;
  font-weight: 500;
}

.preset-tag:hover {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
</style>
