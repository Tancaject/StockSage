<template>
  <section class="workspace-grid portfolio-grid">
    <div class="panel">
      <div class="panel-header"><div><h2>组合计算</h2><p class="description">使用你提供的持仓、价格或指标表，不会写入持仓账户。</p></div><button class="primary-button" :disabled="loading" @click="calculate">{{ loading ? '计算中…' : '计算' }}</button></div>
      <div class="mode-tabs" role="group" aria-label="计算类型"><button v-for="item in modes" :key="item.value" :class="{ active: mode === item.value }" :disabled="loading" @click="changeMode(item.value)">{{ item.label }}</button></div>
      <p class="description">{{ hints[mode] }}</p>
      <label class="field"><span>输入 JSON（示例数字仅供演示，请替换为你的数据）</span><textarea v-model="inputText" :disabled="loading" spellcheck="false" rows="16" aria-label="组合数据 JSON" /></label>
      <div class="input-actions"><label class="import-label">导入 JSON 文件<input type="file" accept="application/json,.json" :disabled="loading" @change="importFile" /></label><button :disabled="loading" @click="loadExample">载入示例</button></div>
      <p class="description">仅使用输入表计算，未连接个人券商账户。不同币种分开报告，未提供的数据保留为未知。</p>
      <p v-if="error" class="calculation-error" role="alert">{{ error }}</p>
    </div>
    <div v-if="result" class="panel" aria-live="polite">
      <div class="panel-header"><h2>计算结果</h2><button @click="downloadResult">下载 JSON</button></div>
      <p class="description">日期 {{ result.asOf }} · 用户提供数据 · {{ result.status === 'PARTIAL' ? '部分数据未知，见明细' : '计算完成' }}</p>
      <p class="description">{{ result.valuationBasis || result.scope }}</p>
      <section v-for="group in result.currencyGroups || []" :key="group.currency" class="currency-result">
        <h3>{{ group.currency }}</h3>
        <p v-if="resultMode === 'analysis'">净值 {{ number(group.netValue) }} · 绝对敞口 {{ number(group.grossValue) }} · 现金 {{ number(group.cashValue) }} · HHI {{ number(group.hhi, 4) }}</p>
        <p v-else>组合价值 {{ number(group.totalValue) }} · 披露覆盖 {{ percent(group.coverage) }} · 未知暴露 {{ number(group.uncoveredValue) }}</p>
        <div class="table-scroll"><table v-if="resultMode === 'analysis'">
          <thead><tr><th>代码</th><th>类型</th><th>市值</th><th>净值权重</th><th>绝对敞口权重</th></tr></thead>
          <tbody><tr v-for="row in group.positions" :key="row.symbol"><td>{{ row.symbol }}</td><td>{{ row.kind === 'CASH' ? '现金' : '证券' }}</td><td>{{ number(row.marketValue) }}</td><td>{{ percent(row.netWeight) }}</td><td>{{ percent(row.grossWeight) }}</td></tr></tbody>
        </table><table v-else><thead><tr><th>直接及穿透代码</th><th>已知暴露金额</th><th>占组合价值</th></tr></thead><tbody><tr v-for="row in group.exposures" :key="row.symbol"><td>{{ row.symbol }}</td><td>{{ number(row.marketValue) }}</td><td>{{ percent(row.portfolioWeight) }}</td></tr></tbody></table></div>
        <template v-if="group.scenarios?.length"><h4>价格情景</h4><ul><li v-for="scenario in group.scenarios" :key="scenario.name">{{ scenario.name }}：总损益 {{ number(scenario.totalPnl) }}；已知部分 {{ number(scenario.knownPnl) }}<span v-if="scenario.unknownSymbols.length">；未指定变化率 {{ scenario.unknownSymbols.join('、') }}</span></li></ul></template>
        <template v-if="group.fundCoverage?.length"><h4>成分表覆盖</h4><ul><li v-for="fund in group.fundCoverage" :key="fund.symbol">{{ fund.symbol }}：{{ fund.asOf || '未提供日期' }}，覆盖 {{ percent(fund.coverage) }}，未知暴露 {{ number(fund.uncoveredValue) }}</li></ul></template>
      </section>
      <template v-if="result.correlations?.length"><h3>共同日期收益相关性</h3><ul><li v-for="pair in result.correlations" :key="pair.symbolA + pair.symbolB">{{ pair.symbolA }} / {{ pair.symbolB }}：{{ number(pair.correlation, 4) }}（{{ pair.returnCount }} 个共同区间）{{ pair.reason || '' }}</li></ul><p class="description">共同收盘日期的本币价格收益率，不含分红或汇率调整。</p></template>
      <template v-if="result.fundOverlap?.length"><h3>ETF 重叠</h3><ul><li v-for="pair in result.fundOverlap" :key="pair.symbolA + pair.symbolB">{{ pair.symbolA }} / {{ pair.symbolB }}：{{ percent(pair.disclosedOverlap) }}；{{ pair.basis || pair.reason }}（{{ pair.asOfA || '未知日期' }} / {{ pair.asOfB || '未知日期' }}）</li></ul></template>
      <div v-if="result.results" class="table-scroll"><table><thead><tr><th>代码</th><th>结果</th><th>逐项判定</th></tr></thead><tbody><tr v-for="row in result.results" :key="row.symbol"><td>{{ row.symbol }}</td><td>{{ statusLabel(row.status) }}</td><td><div v-for="(rule, index) in row.rules" :key="index">{{ rule.rule.metric }}：{{ statusLabel(rule.status) }}，{{ rule.reason || ('实际值 ' + number(rule.actual)) }}<span v-if="rule.dataAsOf">（{{ rule.dataAsOf }}）</span></div></td></tr></tbody></table></div>
      <details><summary>完整计算输出与公式</summary><pre>{{ JSON.stringify(result, null, 2) }}</pre></details>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { calculatePortfolio } from '../../api/workbench.js'
const modes = [{ value: 'analysis', label: '持仓与情景' }, { value: 'etf', label: 'ETF 穿透' }, { value: 'screen', label: '条件筛选' }]
const hints = {
  analysis: 'quantity × price 得到市值。CASH 用本币金额作 quantity、price 为 1。情景变化率用小数表示（-0.1 为下跌 10%）；可附 prices 收盘价序列计算相关性。',
  etf: '提供持仓市值及带日期成分表。weight 为 0 到 1 的小数；未披露权重显示为未知，仅穿透一层。',
  screen: '只筛选 universe 中的证券。规则为 AND，支持 GT、GTE、LT、LTE、EQ、BETWEEN；缺失或日期超前的指标显示为未知。',
}
const examples = {
  analysis: { asOf: '2026-10-28', positions: [{ symbol: 'AAPL', currency: 'USD', quantity: 10, price: 100, kind: 'SECURITY' }, { symbol: 'MSFT', currency: 'USD', quantity: 5, price: 200, kind: 'SECURITY' }, { symbol: 'USD', currency: 'USD', quantity: 500, price: 1, kind: 'CASH' }], scenarios: [{ name: '均下跌 10%', returns: { AAPL: -0.1, MSFT: -0.1 } }], prices: { AAPL: [{ date: '2026-10-23', close: 95 }, { date: '2026-10-26', close: 98 }, { date: '2026-10-27', close: 97 }, { date: '2026-10-28', close: 100 }], MSFT: [{ date: '2026-10-23', close: 195 }, { date: '2026-10-26', close: 196 }, { date: '2026-10-27', close: 198 }, { date: '2026-10-28', close: 200 }] } },
  etf: { asOf: '2026-10-28', positions: [{ symbol: 'DEMO-ETF', currency: 'USD', marketValue: 1000, isEtf: true }, { symbol: 'AAPL', currency: 'USD', marketValue: 500, isEtf: false }], funds: [{ symbol: 'DEMO-ETF', asOf: '2026-10-27', holdings: [{ symbol: 'AAPL', weight: 0.4 }, { symbol: 'MSFT', weight: 0.35 }] }] },
  screen: { asOf: '2026-10-28', universe: ['AAPL', 'MSFT', 'NVDA'], rows: [{ symbol: 'AAPL', asOf: '2026-10-28', metrics: { PE: 25, revenueGrowth: 0.1 } }, { symbol: 'MSFT', asOf: '2026-10-28', metrics: { PE: 35, revenueGrowth: 0.15 } }], rules: [{ metric: 'PE', operator: 'LT', value: 30 }, { metric: 'revenueGrowth', operator: 'GTE', value: 0.1 }] },
}
const mode = ref('analysis'), inputText = ref(JSON.stringify(examples.analysis, null, 2))
const loading = ref(false), error = ref(''), result = ref(null), resultMode = ref('analysis')
const drafts = { analysis: inputText.value }
function changeMode(value) { drafts[mode.value] = inputText.value; mode.value = value; inputText.value = drafts[value] || JSON.stringify(examples[value], null, 2); error.value = ''; result.value = null }
function loadExample() { inputText.value = JSON.stringify(examples[mode.value], null, 2); error.value = ''; result.value = null }
async function importFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (file.size > 500000) { error.value = '输入文件超过 500 KB，请减少数据后重新导入。'; return }
  try { inputText.value = await file.text(); error.value = ''; result.value = null } catch { error.value = '读取文件失败，请重新选择 JSON 文件。' }
  event.target.value = ''
}
async function calculate() {
  error.value = ''; result.value = null
  let input
  try { input = JSON.parse(inputText.value) } catch { error.value = '输入不是有效 JSON，请检查引号、逗号和括号。'; return }
  loading.value = true
  try { result.value = await calculatePortfolio(mode.value, input); resultMode.value = mode.value }
  catch (cause) { error.value = cause.message || '计算请求失败，请检查服务连接后重试。' }
  finally { loading.value = false }
}
function number(value, digits = 2) { return value == null ? '未知' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: digits }) }
function percent(value) { return value == null ? '未知' : number(value * 100) + '%' }
function statusLabel(value) { return { PASS: '符合', FAIL: '不符合', UNKNOWN: '未知' }[value] || value }
function downloadResult() {
  const url = URL.createObjectURL(new Blob([JSON.stringify(result.value, null, 2)], { type: 'application/json;charset=utf-8' }))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'portfolio-' + resultMode.value + '-' + result.value.asOf + '.json'; anchor.click(); URL.revokeObjectURL(url)
}
</script>

<style scoped>
.portfolio-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
.description { color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
.mode-tabs, .input-actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0; }
button, .import-label { padding: 7px 12px; border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text-secondary); background: var(--surface-raised); cursor: pointer; font-size: 13px; }
button.active { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); }
button:disabled { cursor: wait; opacity: 0.6; }
.field textarea { width: 100%; box-sizing: border-box; padding: 12px; color: var(--text-primary); background: var(--surface-raised); border: 1px solid var(--border-soft); border-radius: 8px; font-family: monospace; font-size: 12px; line-height: 1.5; resize: vertical; }
.import-label input { display: none; }
.calculation-error { color: var(--danger, #dc4545); }
.currency-result { margin: 18px 0; border-bottom: 1px solid var(--border-soft); padding-bottom: 12px; }
.table-scroll { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 8px; border-bottom: 1px solid var(--border-soft); text-align: left; white-space: nowrap; }
li, .currency-result p { font-size: 13px; line-height: 1.8; }
details { margin-top: 16px; }
summary { cursor: pointer; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 460px; overflow-y: auto; font-size: 12px; }
</style>
