import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

import {
  searchWorkbenchStocks,
} from '../api/workbench.js'

import {
  buildEventImpactPrompt,
  buildComparisonPrompt,
  buildHealthChecks,
  buildPortfolioDiagnosisPrompt,
  buildPromptBrief,
  buildReportMarkdown,
  buildResearchPrompt,
  buildTickerSuggestions,
  buildLatestBriefDigest,
  buildWatchlistFromProfile,
  markHealthCheck,
  normalizeTicker,
  normalizeCockpit,
  summarizeEvidencePreview,
  summarizeTaskTimeline,
  shortHash,
  summarizeHealthChecks,
  summarizeAgentEval,
  summarizeEvalResult,
  summarizeRagEval,
  summarizeReportVersions,
} from './workbench.js'

test('normalizeTicker uppercases symbols and strips noisy characters', () => {
  assert.equal(normalizeTicker('  nvda.us '), 'NVDAUS')
  assert.equal(normalizeTicker('brk-b'), 'BRKB')
  assert.equal(normalizeTicker(''), '')
})

test('buildResearchPrompt creates a single-stock deep research request', () => {
  const prompt = buildResearchPrompt('aapl', ['valuation', 'risks'])

  assert.match(prompt, /AAPL/)
  assert.match(prompt, /valuation/)
  assert.match(prompt, /risks/)
  assert.match(prompt, /SEC/)
})

test('buildComparisonPrompt keeps both tickers and requested dimensions', () => {
  const prompt = buildComparisonPrompt(['msft', 'amzn'], ['cloud', 'margin'])

  assert.match(prompt, /MSFT/)
  assert.match(prompt, /AMZN/)
  assert.match(prompt, /cloud/)
  assert.match(prompt, /margin/)
})

test('buildEventImpactPrompt creates a filing-backed event analysis request', () => {
  const prompt = buildEventImpactPrompt({
    ticker: 'nvda',
    eventText: 'Management guided data center revenue higher after Blackwell demand accelerated.',
  })

  assert.match(prompt, /NVDA/)
  assert.match(prompt, /event summary/i)
  assert.match(prompt, /impact chain/i)
  assert.match(prompt, /affected financial metrics/i)
  assert.match(prompt, /evidence citations/i)
  assert.match(prompt, /uncertainties/i)
  assert.match(prompt, /follow-up tracking/i)
  assert.match(prompt, /Blackwell demand/)
})

test('buildPortfolioDiagnosisPrompt keeps IBKR read-only boundaries explicit', () => {
  const prompt = buildPortfolioDiagnosisPrompt('DU12345')

  assert.match(prompt, /IBKR/)
  assert.match(prompt, /DU12345/)
  assert.match(prompt, /read-only/i)
  assert.match(prompt, /concentration/i)
  assert.match(prompt, /sector or theme exposure/i)
  assert.match(prompt, /Do not place/i)
})

test('summarizeRagEval extracts averages, failures, and gate status', () => {
  const summary = summarizeRagEval({
    created_at: '2026-05-13T15:57:58',
    case_count: 2,
    averages: {
      context_recall: 0.9,
      citation_precision: 0.7,
      no_answer_accuracy: 1,
      latency_seconds: 12,
    },
    cases: [
      { id: 'good', metrics: { context_recall: 1, citation_precision: 1 } },
      { id: 'bad', metrics: { context_recall: 0.4, citation_precision: 0.2 } },
    ],
  })

  assert.equal(summary.caseCount, 2)
  assert.equal(summary.status, 'fail')
  assert.deepEqual(summary.failedMetrics.map(item => item.metric), ['citation_precision'])
  assert.equal(summary.worstCases[0].id, 'bad')
})

test('summarizeRagEval exposes RAGAS metrics and failed case details', () => {
  const summary = summarizeRagEval({
    case_count: 2,
    averages: {
      ragas_faithfulness: 0.91,
      ragas_response_relevancy: 0.83,
      citation_precision: 0.9,
      no_answer_accuracy: 1,
    },
    cases: [
      {
        id: 'amzn_bad_context',
        category: 'single_filing_fact',
        metrics: { context_recall: 0.2, citation_precision: 0.1 },
        question: 'What changed in AWS margins?',
        answer: 'Unsupported answer.',
        expected_answer: 'Use segment operating income evidence.',
        retrieval: {
          final_contexts: [
            { rank: 1, ticker: 'AMZN', section: 'Item 1A', content: 'Risk factor text' },
          ],
        },
        citations: [{ rank: 1, claim: 'AWS margin improved' }],
      },
    ],
  })

  assert.deepEqual(
    summary.ragasMetrics.map(item => item.metric),
    ['ragas_faithfulness', 'ragas_response_relevancy']
  )
  assert.equal(summary.worstCases[0].id, 'amzn_bad_context')
  assert.equal(summary.worstCases[0].question, 'What changed in AWS margins?')
  assert.equal(summary.worstCases[0].contexts[0].ticker, 'AMZN')
  assert.equal(summary.worstCases[0].citations[0].claim, 'AWS margin improved')
})

test('summarizeEvalResult imports agent_eval_v1 failures and optional sections', () => {
  const summary = summarizeEvalResult({
    schema_version: 'agent_eval_v1',
    status: 'failed',
    planner: {
      total_cases: 2,
      route_accuracy: 0.5,
      required_action_recall: 0.8,
      results: [{
        id: 'case-1',
        expectedRoute: 'NEWS',
        actualRoute: 'MARKET',
        passed: false,
        plannedActions: ['MARKET_AGENT'],
        missingRequiredActions: ['NEWS_AGENT'],
        matchedForbiddenActions: [],
      }],
    },
    rag: { status: 'not_run' },
    trace: { status: 'not_run' },
    baseline_delta: { status: 'completed' },
    gates: [{
      metric: 'route_accuracy',
      value: 0.5,
      operator: '>=',
      threshold: 0.95,
      status: 'failed',
    }],
  })

  assert.equal(summary.kind, 'agent')
  assert.equal(summary.status, 'fail')
  assert.equal(summary.worstCases[0].id, 'case-1')
  assert.match(summary.worstCases[0].expectedAnswer, /missing NEWS_AGENT/)
  assert.equal(summary.optionalSections.rag, 'not_run')
  assert.equal(summarizeAgentEval({ planner: {}, gates: [] }).kind, 'agent')
})

test('summarizeHealthChecks returns fail when any required service fails', () => {
  const summary = summarizeHealthChecks([
    { id: 'backend', required: true, ok: true },
    { id: 'data', required: true, ok: false },
    { id: 'phoenix', required: false, ok: false },
  ])

  assert.equal(summary.status, 'fail')
  assert.equal(summary.requiredReady, 1)
  assert.equal(summary.requiredTotal, 2)
})

test('buildHealthChecks treats RAG retrieval as an optional runtime capability', () => {
  const checks = buildHealthChecks({
    backend: true,
    profile: true,
    cockpit: true,
    rag: false,
    details: {
      rag: { status: 403, message: 'HTTP 403' },
    },
  })
  const summary = summarizeHealthChecks(checks)

  assert.equal(summary.status, 'pass')
  assert.equal(checks.find(check => check.id === 'rag').required, false)
  assert.equal(checks.find(check => check.id === 'rag').ok, false)
  assert.match(checks.find(check => check.id === 'rag').detail, /不会阻断研究/)
})

test('workbench shell does not show persistent service-readiness chrome', () => {
  const viewPath = fileURLToPath(new URL('../views/WorkbenchView.vue', import.meta.url))
  const source = fs.readFileSync(viewPath, 'utf8')

  assert.doesNotMatch(source, /class="rail-foot"/)
  assert.doesNotMatch(source, /researchStack\.readyLabel/)
})

test('markHealthCheck updates a stale cockpit health result after a successful direct load', () => {
  const checks = buildHealthChecks({
    backend: true,
    profile: true,
    cockpit: false,
    rag: false,
  })

  const updated = markHealthCheck(checks, 'cockpit', true, '驾驶舱接口已响应')
  const summary = summarizeHealthChecks(updated)

  assert.equal(updated.find(check => check.id === 'cockpit').ok, true)
  assert.equal(updated.find(check => check.id === 'cockpit').detail, '驾驶舱接口已响应')
  assert.equal(summary.status, 'pass')
  assert.equal(checks.find(check => check.id === 'cockpit').ok, false)
})

test('buildPromptBrief exposes user-facing settings instead of raw prompt text', () => {
  const brief = buildPromptBrief('research', {
    ticker: 'nvda',
    focusAreas: ['valuation', 'risks'],
  })

  assert.equal(brief.title, '单股研究设置')
  assert.deepEqual(brief.rows.map(row => row.label), ['标的', '关注重点', '输出'])
  assert.match(brief.rows[1].value, /估值/)
  assert.doesNotMatch(brief.rows.map(row => row.value).join('\n'), /Run a full StockSage/i)
})

test('buildWatchlistFromProfile prefers backend profile watch list and holdings', () => {
  const watchlist = buildWatchlistFromProfile({
    watchList: ['nvda', 'amzn'],
    holdings: ['voo', 'nvda'],
  })

  assert.deepEqual(watchlist.map(item => item.ticker), ['NVDA', 'AMZN', 'VOO'])
  assert.equal(watchlist[0].status, '持仓/关注')
  assert.equal(watchlist[1].status, '关注')
  assert.equal(watchlist[2].status, '持仓')
})

test('buildTickerSuggestions matches ticker, company name, and market metadata', () => {
  const byTicker = buildTickerSuggestions('nv')
  const byName = buildTickerSuggestions('amazon')
  const byMarket = buildTickerSuggestions('港股')

  assert.equal(byTicker[0].ticker, 'NVDA')
  assert.equal(byName[0].ticker, 'AMZN')
  assert.ok(byMarket.some(item => item.ticker === '0700'))
})

test('buildTickerSuggestions excludes symbols already present in the watchlist', () => {
  const suggestions = buildTickerSuggestions('n', [
    { ticker: 'NFLX', status: 'watching', lastAction: '' },
    { ticker: 'NVDA', status: 'holding/watch', lastAction: '' },
  ])

  assert.ok(suggestions.length > 0)
  assert.equal(suggestions.some(item => item.ticker === 'NFLX'), false)
  assert.equal(suggestions.some(item => item.ticker === 'NVDA'), false)
})

test('buildTickerSuggestions prefers live stock search candidates over the local fallback', () => {
  const suggestions = buildTickerSuggestions('amazon', [], 6, [
    { ticker: 'AMZN', name: 'Amazon.com Inc.', market: 'US', source: 'stock_search' },
    { ticker: 'BABA', name: 'Alibaba Group', market: 'US', source: 'stock_search' },
  ])

  assert.equal(suggestions[0].ticker, 'AMZN')
  assert.equal(suggestions[0].name, 'Amazon.com Inc.')
  assert.equal(suggestions[0].reason, 'US')
})

test('searchWorkbenchStocks calls the workbench stock search endpoint', async () => {
  const originalFetch = globalThis.fetch
  let requestedUrl = ''
  globalThis.fetch = async url => {
    requestedUrl = url
    return {
      ok: true,
      json: async () => [
        { ticker: 'AMZN', name: 'Amazon.com Inc.', market: 'US', source: 'stock_search' },
      ],
    }
  }

  try {
    const result = await searchWorkbenchStocks({ query: 'amazon', limit: 5 })

    assert.equal(requestedUrl, '/api/workbench/stocks/search?q=amazon&limit=5')
    assert.deepEqual(result, [
      { ticker: 'AMZN', name: 'Amazon.com Inc.', market: 'US', source: 'stock_search' },
    ])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('buildReportMarkdown includes thesis, evidence, and disclaimers', () => {
  const markdown = buildReportMarkdown({
    ticker: 'nvda',
    title: 'AI infrastructure thesis',
    stance: 'watch',
    messages: [
      { role: 'user', content: 'Analyze NVDA' },
      {
        role: 'assistant',
        content: 'NVDA has growth evidence [1].',
        modelTier: 'STRONG',
        modelName: 'qwen3.6-max-preview',
        traceId: 'trace-123',
        charts: [{
          symbol: 'NVDA',
          period: 'daily',
          bar: '1d',
          indicators: ['MA5', 'MA20'],
          generatedAt: '2026-05-30T10:00:00.000Z',
          sourceTool: 'getStockKLine',
        }],
      },
    ],
  })

  assert.match(markdown, /NVDA/)
  assert.match(markdown, /AI infrastructure thesis/)
  assert.match(markdown, /NVDA has growth evidence/)
  assert.match(markdown, /标的：NVDA/)
  assert.match(markdown, /判断：watch/)
  assert.match(markdown, /证据摘录/)
  assert.match(markdown, /Research Provenance/)
  assert.match(markdown, /qwen3\.6-max-preview/)
  assert.match(markdown, /trace-123/)
  assert.match(markdown, /getStockKLine/)
  assert.match(markdown, /MA5, MA20/)
  assert.match(markdown, /2026-05-30T10:00:00.000Z/)
  assert.match(markdown, /not investment advice/i)
})

test('buildReportMarkdown uses a Chinese memo title when no title is provided', () => {
  const markdown = buildReportMarkdown({
    ticker: 'NVDA',
    stance: '',
    messages: [],
  })

  assert.match(markdown, /^# NVDA 投资备忘录/)
  assert.match(markdown, /暂无对话摘录。/)
})

test('summarizeReportVersions exposes compact version metadata', () => {
  const rows = summarizeReportVersions([
    {
      ticker: 'nvda',
      reportVersion: 3,
      dataSnapshotHash: '1234567890abcdef',
      contextHash: 'abcdef1234567890',
      modelTier: 'STRONG',
      modelName: 'qwen3.6-max',
      generatedAt: '2026-06-05T09:00:00',
      preview: 'Updated report preview.',
    },
  ])

  assert.equal(rows[0].versionLabel, 'NVDA v3')
  assert.equal(rows[0].snapshotLabel, '1234567890ab')
  assert.equal(rows[0].contextLabel, 'abcdef123456')
  assert.equal(rows[0].modelLabel, 'STRONG / qwen3.6-max')
  assert.equal(rows[0].preview, 'Updated report preview.')
  assert.equal(shortHash(''), '--')
})

test('buildLatestBriefDigest turns the latest report into an actionable brief', () => {
  const digest = buildLatestBriefDigest({
    latestReport: {
      id: 42,
      ticker: 'NVDA',
      versionLabel: 'NVDA v4',
      recommendation: 'HOLD',
      dataSnapshotHash: '1234567890abcdef',
      contextHash: 'abcdef1234567890',
      modelTier: 'STRONG',
      modelName: 'qwen3.6-max',
      generatedAt: '2026-06-29T10:00:00',
      preview: 'NVDA 维持 HOLD：AI 数据中心需求强劲，但估值和最新披露仍需要实时校验。',
      citations: ['[1] NVDA 10-Q data center revenue', '[2] NVDA risk factors'],
    },
    reportVersions: [
      { id: 42, ticker: 'NVDA', versionLabel: 'NVDA v4', recommendation: 'HOLD', preview: 'Latest version' },
      { id: 41, ticker: 'NVDA', versionLabel: 'NVDA v3', recommendation: 'WATCH', preview: 'Prior version' },
    ],
    focusAreas: ['valuation', 'risks', 'recent filings'],
  })

  assert.equal(digest.hasReport, true)
  assert.equal(digest.headline, 'NVDA v4')
  assert.equal(digest.recommendation, 'HOLD')
  assert.match(digest.summary, /AI 数据中心需求强劲/)
  assert.deepEqual(digest.evidenceItems.map(item => item.title), ['证据 1', '证据 2'])
  assert.match(digest.riskItems.map(item => item.detail).join('\n'), /估值/)
  assert.deepEqual(digest.selectedFocusLabels, ['估值', '风险', '最新披露'])
  assert.equal(digest.actions[0].id, 'open-report')
  assert.equal(digest.actions[0].versionId, 42)
  assert.equal(digest.recentVersions.length, 2)
  assert.deepEqual(digest.provenance.map(row => row.label), ['数据快照', '上下文', '模型', '生成时间'])
})

test('buildLatestBriefDigest gives an explicit empty-state action when no report exists', () => {
  const digest = buildLatestBriefDigest({
    latestReport: null,
    reportVersions: [],
    focusAreas: ['valuation', 'news'],
  })

  assert.equal(digest.hasReport, false)
  assert.match(digest.summary, /暂无可展示的 AI 投研结论/)
  assert.equal(digest.actions[0].id, 'start-research')
  assert.deepEqual(digest.selectedFocusLabels, ['估值', '新闻'])
  assert.equal(digest.provenance.length, 0)
})

test('buildLatestBriefDigest does not show another ticker report for the selected ticker', () => {
  const digest = buildLatestBriefDigest({
    ticker: 'SNDK',
    latestReport: null,
    reportVersions: [
      { id: 1, ticker: 'NVDA', reportVersion: 1, recommendation: 'HOLD', preview: 'NVDA report preview.' },
    ],
    focusAreas: ['valuation'],
  })

  assert.equal(digest.hasReport, false)
  assert.match(digest.summary, /暂无可展示的 AI 投研结论/)
  assert.equal(digest.recentVersions.length, 0)
})

test('normalizeCockpit prepares chart, report provenance, task timeline, and evidence preview', () => {
  const cockpit = normalizeCockpit({
    ticker: 'nvda',
    chartStatus: 'READY',
    chart: {
      chartType: 'candlestick',
      symbol: 'NVDA',
      points: [{ date: '2026-06-04', open: 119, high: 121, low: 118, close: 120 }],
    },
    latestReport: {
      ticker: 'NVDA',
      reportVersion: 3,
      recommendation: 'WATCH',
      dataSnapshotHash: '1234567890abcdef',
      contextHash: 'abcdef1234567890',
      modelTier: 'STRONG',
      modelName: 'qwen3.6-max',
      preview: 'Watch with valuation discipline.',
      citations: ['[1] NVDA 10-Q'],
    },
    taskTimeline: [
      { status: 'RUNNING', stage: 'AGENT_DEBATE', attempts: 2, heartbeatAt: '2026-06-04T12:00:00' },
    ],
    evidencePreview: [
      { dimension: 'Margins', evidence: 'Gross margin expanded.', implication: 'Supports leverage.', source: '10-Q' },
    ],
  })

  assert.equal(cockpit.ticker, 'NVDA')
  assert.equal(cockpit.hasChart, true)
  assert.equal(cockpit.chartStatusLabel, '就绪')
  assert.equal(cockpit.latestReport.versionLabel, 'NVDA v3')
  assert.equal(cockpit.latestReport.snapshotLabel, '1234567890ab')
  assert.equal(cockpit.latestReport.contextLabel, 'abcdef123456')
  assert.equal(cockpit.latestReport.modelLabel, 'STRONG / qwen3.6-max')
  assert.equal(cockpit.taskTimeline[0].statusLabel, '运行中')
  assert.equal(cockpit.taskTimeline[0].stageLabel, '多空辩论')
  assert.equal(cockpit.taskTimeline[0].tone, 'running')
  assert.equal(cockpit.evidencePreview[0].dimension, 'Margins')
})

test('normalizeCockpit labels offline sample cockpit status distinctly', () => {
  const cockpit = normalizeCockpit({
    ticker: 'nvda',
    chartStatus: 'SAMPLE',
    chartMessage: 'Loaded offline sample cockpit.',
    chart: {
      chartType: 'candlestick',
      sourceTool: 'offlineDemoSample',
      symbol: 'NVDA',
      points: [{ date: '2026-06-05', open: 130, high: 132, low: 129, close: 131 }],
    },
  })

  assert.equal(cockpit.chartStatus, 'SAMPLE')
  assert.equal(cockpit.chartStatusLabel, '演示样本')
  assert.equal(cockpit.chartTone, 'sample')
  assert.equal(cockpit.isSample, true)
})

test('summarizeEvidencePreview falls back to citations when evidence rows are empty', () => {
  const rows = summarizeEvidencePreview([], ['[1] NVDA 10-Q risk factor'])

  assert.equal(rows.length, 1)
  assert.equal(rows[0].dimension, 'Citation')
  assert.equal(rows[0].source, 'report')
  assert.match(rows[0].evidence, /10-Q/)
})

test('summarizeTaskTimeline hides owner tokens and labels failed tasks', () => {
  const rows = summarizeTaskTimeline([
    {
      id: 9,
      status: 'FAILED',
      stage: 'FAILED',
      attempts: 3,
      leaseToken: 'secret-token',
      errorMessage: 'attempts exhausted before execution',
    },
  ])

  assert.equal(rows[0].tone, 'danger')
  assert.equal(rows[0].stageLabel, '失败')
  assert.equal(rows[0].leaseToken, undefined)
  assert.equal(rows[0].deadLettered, true)
  assert.match(rows[0].recoveryHint, /死信队列/)
})

test('summarizeTaskTimeline labels report synthesis without claiming DLQ for ordinary failures', () => {
  const rows = summarizeTaskTimeline([
    { id: 10, status: 'RUNNING', stage: 'REPORT_SYNTHESIS', attempts: 1 },
    { id: 11, status: 'FAILED', stage: 'FAILED', attempts: 1, errorMessage: 'provider timeout' },
  ])

  assert.equal(rows[0].stageLabel, '综合研究结论')
  assert.equal(rows[0].deadLettered, false)
  assert.equal(rows[1].deadLettered, false)
  assert.equal(rows[1].recoveryHint, '')
})
