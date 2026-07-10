import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildAssistantEvidenceSummary,
  buildReasoningActivity,
  buildResearchStackStatus,
  buildResearchTimeline,
  buildTickerDossier,
} from './researchUi.js'

test('buildResearchStackStatus summarizes required and optional data-source readiness', () => {
  const status = buildResearchStackStatus([
    { id: 'backend', label: '后端接口', required: true, ok: true, detail: '聊天接口已响应' },
    { id: 'rag', label: 'RAG 检索', required: true, ok: false, detail: '检索接口不可用' },
    { id: 'trace', label: '链路追踪', required: false, ok: true, detail: '消息链路可查看' },
  ])

  assert.equal(status.status, 'fail')
  assert.equal(status.readyLabel, '1/2 服务就绪')
  assert.deepEqual(status.sources.map(source => source.status), ['ready', 'blocked', 'ready'])
  assert.equal(status.sources[1].tone, 'danger')
})

test('buildAssistantEvidenceSummary exposes model, trace, charts, and source hints', () => {
  const summary = buildAssistantEvidenceSummary({
    role: 'assistant',
    modelTier: 'STRONG',
    modelName: 'qwen3.6-max-preview',
    traceId: 'trace-123',
    charts: [{ symbol: 'NVDA', sourceTool: 'getStockKLine' }],
    content: 'According to SEC 10-K risk factors and RAG evidence, NVDA has supply concentration risk.',
  })

  assert.equal(summary.visible, true)
  assert.deepEqual(
    summary.badges.map(badge => badge.label),
    ['模型', '链路', '图表', '财报', '知识库']
  )
  assert.equal(summary.badges[0].value, 'STRONG')
  assert.equal(summary.badges[2].value, 'NVDA')
})

test('buildResearchTimeline turns reasoning chunks and charts into research stages', () => {
  const timeline = buildResearchTimeline({
    reasoning: [
      { type: 'thought', content: 'Plan the valuation check.' },
      { type: 'action', content: 'getStockKLine: NVDA daily candles' },
      { type: 'observation', content: 'Retrieved 120 candles.' },
    ],
    charts: [{ symbol: 'NVDA', period: 'daily' }],
    traceSummary: { steps: 3, durationMs: 2250, tokens: 1800 },
  })

  assert.deepEqual(timeline.map(stage => stage.kind), ['plan', 'tool', 'evidence', 'chart', 'trace'])
  assert.equal(timeline[0].label, '分析步骤')
  assert.equal(timeline[1].label, '读取 K 线')
  assert.equal(timeline[1].detail, 'NVDA daily candles')
  assert.equal(timeline[2].label, '数据返回')
  assert.equal(timeline[3].label, '图表已生成')
  assert.equal(timeline.at(-1).meta, '3 步 · 2.3s · ~1,800 令牌')
})

test('buildResearchTimeline hides tool function names and raw argument arrays', () => {
  const timeline = buildResearchTimeline({
    reasoning: [
      { type: 'thought', content: 'Called tool: 获取K线数据' },
      {
        type: 'action',
        content: 'getStockKLine: ["meta这家 你认为有没有 的价值 目前看来meta在ai方面完全落后 还是只能靠社交软件","daily",60]',
        durationMs: 3,
      },
      {
        type: 'action',
        content: 'getTechnicalIndicators: ["meta这家 你认为有没有 的价值 目前看来meta在ai方面完全落后 还是只能靠社交软件","MA,MACD,RSI"]',
      },
    ],
  })

  assert.deepEqual(timeline.map(stage => stage.label), ['准备读取数据', '读取 K 线', '读取技术指标'])
  assert.equal(timeline[0].detail, '读取 K 线')
  assert.equal(timeline[1].detail, 'META · 日线 · 60 条')
  assert.equal(timeline[2].detail, 'META · MA · MACD · RSI')
  assert.doesNotMatch(
    timeline.map(stage => `${stage.label} ${stage.detail}`).join('\n'),
    /getStockKLine|getTechnicalIndicators|\["meta/
  )
})

test('buildReasoningActivity summarizes live tool activity without implementation details', () => {
  const activity = buildReasoningActivity({
    type: 'action',
    content: 'getFinancialMetrics: ["META"]',
  })

  assert.deepEqual(activity, {
    type: 'action',
    content: '读取财务指标：META',
  })
})

test('buildTickerDossier creates a workbench-ready summary for the selected ticker', () => {
  const dossier = buildTickerDossier({
    ticker: 'nvda',
    watchlistItem: { ticker: 'NVDA', status: '进行中', lastAction: '驾驶舱已更新' },
  })

  assert.equal(dossier.ticker, 'NVDA')
  assert.deepEqual(dossier.rows.map(row => row.label), ['覆盖范围'])
  assert.equal(dossier.rows[0].value, '进行中 · 驾驶舱已更新')
  assert.equal(dossier.rows[0].tone, 'info')
  assert.deepEqual(dossier.nextActions, [])
})

test('buildTickerDossier omits the separator when there is no last action', () => {
  const dossier = buildTickerDossier({
    ticker: 'AAPL',
    watchlistItem: { ticker: 'AAPL', status: '持仓', lastAction: '' },
  })

  assert.equal(dossier.rows[0].value, '持仓')
  assert.equal(dossier.rows[0].tone, 'positive')
})
