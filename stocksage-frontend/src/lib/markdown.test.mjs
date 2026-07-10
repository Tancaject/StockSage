import assert from 'node:assert/strict'
import test from 'node:test'
import { marked } from 'marked'
import { normalizeMarkdownEmphasis, markdownToPlainText } from './markdown.js'

test('normalizes emphasis wrapped around Chinese quote punctuation', () => {
  const source = '- **长期策略建议：** 采取**“核心底仓长期持有 + 分批定投”**。'
  const html = marked.parse(normalizeMarkdownEmphasis(source), { async: false })

  assert.match(html, /<strong>长期策略建议：<\/strong>/)
  assert.match(html, /“<strong>核心底仓长期持有 \+ 分批定投<\/strong>”/)
  assert.doesNotMatch(html, /\*\*“核心底仓/)
})

test('strips markdown controls from reasoning text while preserving readable content', () => {
  const source = [
    '---',
    '',
    '## 🐂 本轮新增看多论据 (Round 2)',
    '',
    '### 1. 规模经济触发运营杠杆拐点',
    '- **证据引用**：`getFinancialReports` 显示营收突破。',
    '- **逻辑推演**：固定成本摊薄，估值从**周期广告股**切换。',
  ].join('\n')

  const plain = markdownToPlainText(source)

  assert.equal(plain, [
    '🐂 本轮新增看多论据 (Round 2)',
    '',
    '1. 规模经济触发运营杠杆拐点',
    '证据引用：getFinancialReports 显示营收突破。',
    '逻辑推演：固定成本摊薄，估值从周期广告股切换。',
  ].join('\n'))
  assert.doesNotMatch(plain, /(^|\n)\s*#{1,6}\s/)
  assert.doesNotMatch(plain, /\*\*|`/)
})

test('converts markdown tables in reasoning text into readable plain text rows', () => {
  const source = [
    '🔍 需要验证的关键假设',
    '',
    '| 假设项 | 验证路径/观察指标 |',
    '|:---|:---|',
    '| LTAs的实际履约与定价韧性 | 跟踪下一季度财报中“合约收入占比”、“ASP环比变动”及管理层对长协重新定价条款的指引 |',
    '| EPS>$100的可持续性 | 验证FY26-FY27资本开支计划是否因产能过剩担忧而削减；观察HBM3E/HBM4良率爬坡与出货节奏 |',
    '| 15x NTM P/E能否形成机构共识 | 监控卖方评级分布变化、机构持仓集中度，以及市场是否将MU从“半导体周期板块”重分类至“AI基础设施板块” |',
  ].join('\n')

  const plain = markdownToPlainText(source)

  assert.equal(plain, [
    '🔍 需要验证的关键假设',
    '',
    '假设项：验证路径/观察指标',
    'LTAs的实际履约与定价韧性：跟踪下一季度财报中“合约收入占比”、“ASP环比变动”及管理层对长协重新定价条款的指引',
    'EPS>$100的可持续性：验证FY26-FY27资本开支计划是否因产能过剩担忧而削减；观察HBM3E/HBM4良率爬坡与出货节奏',
    '15x NTM P/E能否形成机构共识：监控卖方评级分布变化、机构持仓集中度，以及市场是否将MU从“半导体周期板块”重分类至“AI基础设施板块”',
  ].join('\n'))
  assert.doesNotMatch(plain, /\|/)
  assert.doesNotMatch(plain, /:--/)
})
