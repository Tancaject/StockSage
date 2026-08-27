import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildAssistantEvidenceSummary,
  buildResearchTimeline,
} from './researchUi.js'

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

test('buildResearchTimeline collapses detailed reasoning into key research stages', () => {
  const timeline = buildResearchTimeline({
    reasoning: [
      { type: 'thought', content: 'Coordinator 路由：FUNDAMENTALS；计划步骤：财务、财报、知识库、最终回答。' },
      { type: 'action', content: 'getStockKLine: NVDA daily candles' },
      { type: 'observation', content: 'Retrieved 120 candles.' },
      { type: 'action', label: '读取财报', content: 'getFinancialReports: ["NVDA"]' },
      { type: 'observation', content: '部分 Analyst 预取超时，系统继续生成回答。' },
      { type: 'thought', content: '正在执行深度分析预取：Fundamentals / Market / News / Bull-Bear Debate.' },
    ],
    charts: [{ symbol: 'NVDA', period: 'daily' }],
    hasAnswer: true,
  })

  assert.deepEqual(timeline.map(stage => stage.kind), ['plan', 'evidence', 'analysis', 'chart', 'conclusion'])
  assert.deepEqual(timeline.map(stage => stage.label), ['分析规划', '数据获取', '综合分析', '图表生成', '生成结论'])
  assert.equal(timeline[0].detail, '已确定研究路线与所需数据范围。')
  assert.equal(timeline[1].detail, '已完成行情、SEC 财报数据获取。')
  assert.match(timeline[1].meta, /预取超时/)
  assert.equal(timeline[2].detail, '正在综合基本面、市场、新闻与多空观点。')
  assert.equal(timeline[3].detail, '已生成 NVDA 图表。')
})

test('buildResearchTimeline uses route decision as the plan stage', () => {
  const timeline = buildResearchTimeline({
    reasoning: [
      {
        type: 'route_decision',
        content: '意图理解：分析财报风险',
        metadata: {
          route: 'FUNDAMENTALS',
          source: 'ROUTING_LLM',
          intentSummary: '分析财报风险',
          rationale: '问题要求读取公司财报',
          confidence: 0.92,
          ragHitCount: 2,
        },
      },
      { type: 'thought', content: 'Preparing evidence.' },
    ],
  })

  assert.equal(timeline[0].kind, 'plan')
  assert.equal(timeline[0].detail, '意图：分析财报风险；路由：FUNDAMENTALS；依据：问题要求读取公司财报')
  assert.equal(timeline[0].meta, '来源 ROUTING_LLM · 置信度 0.92 · RAG 2 条')
})

test('buildResearchTimeline explains harness recovery and report acceptance', () => {
  const timeline = buildResearchTimeline({
    reasoning: [
      {
        type: 'action',
        content: 'harness:deep-equity-v1',
        metadata: {
          policyId: 'deep-equity-v1',
          phase: 'EVIDENCE',
          decision: 'RECOVER',
          violationCodes: ['MARKET_MISSING'],
          recoveryActions: ['RETRY_MARKET'],
        },
      },
    ],
  })

  const harness = timeline.find(stage => stage.label === '证据验收')
  assert.equal(harness.kind, 'harness-recover')
  assert.equal(harness.detail, 'deep-equity-v1：RECOVER')
  assert.equal(harness.meta, '恢复动作：RETRY_MARKET')
})

test('buildResearchTimeline merges repeated tool calls and hides implementation details', () => {
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

  assert.deepEqual(timeline.map(stage => stage.label), ['数据获取', '综合分析'])
  assert.equal(timeline[0].detail, '已完成行情数据获取。')
  assert.doesNotMatch(
    timeline.map(stage => `${stage.label} ${stage.detail}`).join('\n'),
    /getStockKLine|getTechnicalIndicators|\["meta/
  )
})
