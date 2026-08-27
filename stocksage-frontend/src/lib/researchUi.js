import { markdownToPlainText } from './markdown.js'

const TOOL_LABELS = {
  getStockKLine: '读取 K 线',
  getFinancialMetrics: '读取财务指标',
  getTechnicalIndicators: '读取技术指标',
  getStockNews: '读取新闻',
  getFinancialReports: '读取财报',
  getFinancialReport: '读取财报',
  searchKnowledge: '检索知识库',
  searchRag: '检索知识库',
}

const CALLED_TOOL_LABELS = {
  获取K线数据: '读取 K 线',
  获取财务指标: '读取财务指标',
  获取技术指标: '读取技术指标',
  获取股票新闻: '读取新闻',
  获取新闻: '读取新闻',
  获取财报: '读取财报',
  检索知识库: '检索知识库',
}

const KNOWN_SYMBOLS = ['META', 'NVDA', 'AAPL', 'MSFT', 'GOOGL', 'GOOG', 'AMZN', 'TSLA', 'NFLX', 'AMD']

export function buildAssistantEvidenceSummary(message = {}) {
  if (message.role !== 'assistant') {
    return { visible: false, badges: [] }
  }

  const badges = []
  const content = String(message.content || '')
  const charts = Array.isArray(message.charts) ? message.charts : []

  if (message.modelTier || message.modelName) {
    badges.push({
      label: '模型',
      value: String(message.modelTier || message.modelName || 'unknown').toUpperCase(),
      detail: message.modelName || 'model returned by backend',
      tone: 'neutral',
    })
  }

  if (message.traceId) {
    badges.push({
      label: '链路',
      value: shortTraceId(message.traceId),
      detail: message.traceId,
      tone: 'info',
    })
  }

  if (charts.length > 0) {
    const firstChart = charts[0] || {}
    badges.push({
      label: '图表',
      value: firstChart.symbol || `${charts.length} 张图表`,
      detail: firstChart.sourceTool || firstChart.period || '流式行情图表',
      tone: 'market',
    })
  }

  if (/\b(SEC|10-K|10-Q|8-K|filing|filings)\b|财报|披露|年报/i.test(content)) {
    badges.push({
      label: '财报',
      value: 'SEC',
      detail: 'filing-backed evidence mentioned',
      tone: 'filing',
    })
  }

  if (/\b(RAG|citation|citations|evidence|knowledge)\b|知识库|引用|证据/i.test(content)) {
    badges.push({
      label: '知识库',
      value: 'RAG',
      detail: 'retrieval or citation context mentioned',
      tone: 'knowledge',
    })
  }

  return {
    visible: badges.length > 0,
    badges,
  }
}

export function buildResearchTimeline({ reasoning = [], charts = [], hasAnswer = false } = {}) {
  const items = normalizeReasoningList(reasoning)
  const routeDecisions = items.filter(item => item.type === 'route_decision')
  const thoughts = items.filter(item => item.type === 'thought' && !summarizeCalledTool(item.content))
  const actions = items.filter(item => item.type === 'action')
  const observations = items.filter(item => item.type === 'observation')
  const harnessEvents = items.filter(item => item.metadata?.policyId && item.metadata?.decision)
  const timeline = []

  const planThought = routeDecisions[0] || thoughts[0]
  if (planThought) {
    const routeMetadata = planThought.type === 'route_decision' ? planThought.metadata : null
    timeline.push({
      kind: 'plan',
      label: '分析规划',
      detail: routeMetadata ? summarizeRouteDecision(routeMetadata) : summarizePlanStage(planThought.content),
      meta: routeMetadata ? summarizeRouteDecisionMeta(routeMetadata) : '',
    })
  }

  if (actions.length > 0 || observations.length > 0) {
    const dataStage = summarizeDataStage(actions, observations)
    timeline.push({
      kind: 'evidence',
      label: '数据获取',
      detail: dataStage.detail,
      meta: dataStage.meta,
    })
  }

  if (harnessEvents.length > 0) {
    const latest = harnessEvents[harnessEvents.length - 1]
    const metadata = latest.metadata || {}
    const recoveries = Array.isArray(metadata.recoveryActions)
      ? metadata.recoveryActions.join('、')
      : ''
    const violations = Array.isArray(metadata.violationCodes)
      ? metadata.violationCodes.join('、')
      : ''
    timeline.push({
      kind: `harness-${String(metadata.decision || '').toLowerCase()}`,
      label: metadata.phase === 'REPORT' ? '报告验收' : '证据验收',
      detail: `${metadata.policyId}：${metadata.decision}`,
      meta: recoveries
        ? `恢复动作：${recoveries}`
        : (violations ? `约束：${violations}` : '完成策略已通过'),
    })
  }

  if (actions.length > 0 || observations.length > 0 || thoughts.length > 1) {
    timeline.push({
      kind: 'analysis',
      label: '综合分析',
      detail: summarizeAnalysisStage(thoughts.slice(routeDecisions.length > 0 ? 0 : (planThought ? 1 : 0))),
      meta: '',
    })
  }

  const chartList = Array.isArray(charts) ? charts : []
  if (chartList.length > 0) {
    const symbols = [...new Set(chartList.map(chart => chart?.symbol).filter(Boolean))]
    timeline.push({
      kind: 'chart',
      label: '图表生成',
      detail: symbols.length > 0
        ? `已生成 ${symbols.slice(0, 3).join('、')} 图表。`
        : `已生成 ${chartList.length} 张市场图表。`,
      meta: '',
    })
  }

  if (hasAnswer) {
    timeline.push({
      kind: 'conclusion',
      label: '生成结论',
      detail: '已形成研究结论与风险提示。',
      meta: '',
    })
  }

  return timeline
}

function normalizeReasoningList(reasoning) {
  return (Array.isArray(reasoning) ? reasoning : [])
    .map(normalizeReasoningItem)
    .filter(item => item.content)
}

function normalizeReasoningItem(item = {}) {
  return {
    type: item.type || 'thought',
    label: item.label || null,
    content: textFrom(item.content),
    durationMs: Number(item.durationMs || 0),
    metadata: item.metadata || null,
  }
}

function summarizePlanStage(content) {
  const text = compactStageDetail(content)
  if (/Coordinator\s*路由|计划步骤|分层路线|FUNDAMENTALS|MARKET|NEWS|DEEP/i.test(text)) {
    return '已确定研究路线与所需数据范围。'
  }
  return text || '已确定本轮研究重点。'
}

function summarizeRouteDecision(metadata = {}) {
  const intent = String(metadata.intentSummary || '').trim()
  const fineIntent = String(metadata.fineIntent || '').trim()
  const intentGroup = String(metadata.intentGroup || '').trim()
  const route = String(metadata.route || 'DIRECT').trim()
  const rationale = String(metadata.rationale || '').trim()
  const parts = []
  if (intent) parts.push(`意图：${intent}`)
  if (fineIntent) parts.push(`分类：${fineIntent}${intentGroup ? `/${intentGroup}` : ''}`)
  parts.push(`路由：${route}`)
  if (rationale) parts.push(`依据：${rationale}`)
  return parts.join('；')
}

function summarizeRouteDecisionMeta(metadata = {}) {
  const parts = []
  if (metadata.source) parts.push(`来源 ${metadata.source}`)
  if (Number.isFinite(Number(metadata.confidence))) {
    parts.push(`置信度 ${Number(metadata.confidence).toFixed(2)}`)
  }
  if (Number.isFinite(Number(metadata.ragHitCount))) {
    parts.push(`RAG ${Number(metadata.ragHitCount)} 条`)
  }
  if (metadata.fallbackReason) parts.push(`降级 ${metadata.fallbackReason}`)
  if (metadata.needsClarification) parts.push('需澄清')
  return parts.join(' · ')
}

function summarizeDataStage(actions, observations) {
  const categories = [...new Set(actions.map(inferDataCategory).filter(Boolean))]
  const visibleCategories = categories.slice(0, 4)
  const detail = visibleCategories.length > 0
    ? `已完成${visibleCategories.join('、')}${categories.length > visibleCategories.length ? '等' : ''}数据获取。`
    : '已完成所需数据检索与读取。'
  const warning = observations
    .map(item => normalizeObservationDetail(item.content))
    .find(item => /超时|失败|不可用|降级|部分|timeout|failed|unavailable/i.test(item))

  return {
    detail,
    meta: warning ? compactStageDetail(warning, 56) : '',
  }
}

function inferDataCategory(item) {
  const action = summarizeToolAction(item.content)
  const text = `${item.label || ''} ${action.label || ''} ${item.content || ''}`

  if (/SEC|filing|company\s*reports?|financial\s*reports?|10-[KQ]|财报|年报/i.test(text)) return 'SEC 财报'
  if (/financial|财务指标|结构化财务/i.test(text)) return '财务指标'
  if (/knowledge|RAG|知识库/i.test(text)) return '知识库'
  if (/news|新闻/i.test(text)) return '新闻'
  if (/K\s*线|kline|technical|market|行情|技术指标/i.test(text)) return '行情'
  if (/search\s*stocks?|resolve\s*stock|company|公司|标的/i.test(text)) return '公司资料'
  return ''
}

function summarizeAnalysisStage(thoughts) {
  const selected = thoughts.find(item => /深度分析|综合|预取|Fundamentals|Market|News|Bull|Bear|多空/i.test(item.content))
  if (selected && /Fundamentals|Market|News|Bull|Bear|多空/i.test(selected.content)) {
    return '正在综合基本面、市场、新闻与多空观点。'
  }
  if (selected) return compactStageDetail(selected.content)
  return '正在交叉验证已获取的数据与证据。'
}

function compactStageDetail(content, maxLength = 88) {
  const text = String(content || '').replace(/\s+/g, ' ').trim()
  if (text.length <= maxLength) return text
  return `${text.slice(0, maxLength - 1).trimEnd()}…`
}

function summarizeToolAction(content) {
  const calledTool = summarizeCalledTool(content)
  if (calledTool) {
    return { label: calledTool.label, detail: '' }
  }

  const parsed = parseToolCall(content)
  if (!parsed) {
    return { label: '读取数据', detail: content }
  }

  return {
    label: TOOL_LABELS[parsed.name] || readableToolName(parsed.name),
    detail: formatToolDetail(parsed),
  }
}

function summarizeCalledTool(content) {
  const match = String(content || '').match(/^Called tool:\s*(.+)$/i)
  if (!match) return null
  const rawLabel = match[1].trim()
  return { label: CALLED_TOOL_LABELS[rawLabel] || rawLabel }
}

function parseToolCall(content) {
  const match = String(content || '').match(/^([A-Za-z_$][\w$]*)\s*[:：]\s*(.*)$/s)
  if (!match) return null

  const name = match[1]
  const rawArgs = match[2].trim()
  const args = parseJsonArgs(rawArgs)

  return {
    name,
    rawArgs,
    args,
    structured: Array.isArray(args),
  }
}

function parseJsonArgs(rawArgs) {
  if (!rawArgs || !/^[\[{]/.test(rawArgs)) return null
  try {
    const parsed = JSON.parse(rawArgs)
    return Array.isArray(parsed) ? parsed : [parsed]
  } catch {
    return null
  }
}

function formatToolDetail(tool) {
  if (!tool.structured) {
    return cleanRawToolDetail(tool.rawArgs) || '按当前问题读取数据'
  }

  const args = tool.args || []
  const symbol = inferSymbol(args, tool.rawArgs)
  const parts = []
  if (symbol) parts.push(symbol)

  if (tool.name === 'getStockKLine') {
    const period = formatPeriod(args[1])
    const count = formatCount(args[2])
    if (period) parts.push(period)
    if (count) parts.push(count)
  } else if (tool.name === 'getTechnicalIndicators') {
    parts.push(...formatIndicators(args[1]))
  } else if (tool.name === 'getStockNews') {
    const count = formatCount(args[1] ?? args[2])
    if (count) parts.push(count)
  }

  if (parts.length > 0) return parts.join(' · ')
  return safeArgSummary(args) || '按当前问题读取数据'
}

function inferSymbol(args, rawArgs) {
  for (const arg of args || []) {
    const compact = normalizeTicker(arg)
    if (isLikelyTicker(compact)) return compact
  }

  const text = String(rawArgs || '')
  for (const symbol of KNOWN_SYMBOLS) {
    const pattern = new RegExp(`(^|[^A-Za-z])${symbol}([^A-Za-z]|$)`, 'i')
    if (pattern.test(text)) return symbol
  }

  return ''
}

function isLikelyTicker(value) {
  const text = String(value || '').toUpperCase()
  if (!/^[A-Z]{1,6}([.-][A-Z]{1,3})?$/.test(text)) return false
  return !['DAILY', 'DAY', 'WEEKLY', 'WEEK', 'MONTHLY', 'MONTH'].includes(text)
}

function formatPeriod(value) {
  const period = String(value || '').trim().toLowerCase()
  return {
    daily: '日线',
    day: '日线',
    '1d': '日线',
    weekly: '周线',
    week: '周线',
    '1w': '周线',
    monthly: '月线',
    month: '月线',
    '1m': '月线',
  }[period] || ''
}

function formatCount(value) {
  const count = Number(value)
  if (!Number.isFinite(count) || count <= 0) return ''
  return `${Math.round(count)} 条`
}

function formatIndicators(value) {
  if (Array.isArray(value)) {
    return value.map(item => String(item || '').trim()).filter(Boolean)
  }
  return String(value || '')
    .split(/[,\s/，、]+/)
    .map(item => item.trim())
    .filter(Boolean)
}

function safeArgSummary(args) {
  return (args || [])
    .map(value => String(value ?? '').trim())
    .filter(value => value && value.length <= 32 && !/\s{2,}/.test(value))
    .slice(0, 3)
    .join(' · ')
}

function cleanRawToolDetail(value) {
  return String(value || '')
    .replace(/^\[|\]$/g, '')
    .replace(/^["']|["']$/g, '')
    .trim()
}

function normalizeObservationDetail(content) {
  return String(content || '')
    .replace(/\s*\(\d+(?:\.\d+)?\s*ms\)\s*$/i, '')
    .trim()
}

function readableToolName(name) {
  return String(name || '读取数据')
    .replace(/^get/i, '读取')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
}

function textFrom(value) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return markdownToPlainText(value)
  return JSON.stringify(value, null, 2)
}

function normalizeTicker(value) {
  return String(value || '')
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
}

function shortTraceId(traceId) {
  const text = String(traceId || '')
  return text.length > 10 ? `${text.slice(0, 6)}...${text.slice(-4)}` : text
}
