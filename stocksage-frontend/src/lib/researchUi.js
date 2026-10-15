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
const TASK_OUTCOME_LABELS = {
  COMPLETED: '已完成',
  DEGRADED: '降级完成',
  BLOCKED: '已阻止',
  FAILED: '失败',
}

export function formatTaskOutcome(value) {
  const outcome = String(value || '').trim().toUpperCase()
  return TASK_OUTCOME_LABELS[outcome] || outcome
}

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

const PLAN_SOURCE_LABELS = { FIXED: '固定计划', DYNAMIC: '动态规划', FALLBACK: '规划失败，退回固定计划' }

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

  // 执行计划：与后端执行、Trace 读同一份最终计划。
  const plan = observations.find(item => item.metadata?.kind === 'ordinary-plan')
  if (plan) {
    const metadata = plan.metadata
    const steps = (Array.isArray(metadata.steps) ? metadata.steps : [])
      .map(step => step.action)
      .filter(action => action && action !== 'Final Answer')
    const targets = Array.isArray(metadata.targets) ? metadata.targets : []
    timeline.push({
      kind: 'plan',
      label: '执行计划',
      detail: `${PLAN_SOURCE_LABELS[metadata.source] || metadata.source}：${steps.join(' → ') || '无取数步骤'}`,
      meta: targets.length > 0 ? `对比标的：${targets.join('、')}` : '',
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

  // 多标的对比：每个标的单独列出验收结果和未满足的数据需求。
  observations
    .filter(item => item.metadata?.kind === 'ordinary-evidence' && item.metadata?.target)
    .forEach((item) => {
      const unmet = Array.isArray(item.metadata.unmetRequirements) ? item.metadata.unmetRequirements : []
      timeline.push({
        kind: 'evidence',
        label: `数据获取 · ${item.metadata.target}`,
        detail: `证据验收：${formatTaskOutcome(item.metadata.taskOutcome)}`,
        meta: unmet.length > 0 ? `未满足：${unmet.join('、')}` : '',
      })
    })

  // DEEP 取证循环：流式块与 Trace 携带同一份 attributes，按 stepKind + 轮次去重。
  timeline.push(...buildEvidenceLoopRows(items))

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

const LOOP_TOOL_LABELS = {
  focused_news_search: '新闻搜索',
  filing_text_search: '财报原文',
  relation_lookup: '关系图谱',
  financial_statements: '结构化财报',
  price_history: 'K 线',
  declare_facets: '声明必答维度',
}

const LOOP_OUTCOME_LABELS = { EXECUTED: '完成', DECLARED: '已记录' }

const LOOP_STOP_LABELS = {
  'FINISHED:SUFFICIENT': '模型判断证据已足够',
  'FINISHED:NO_SAFE_ACTION': '模型判断没有安全可行的下一步',
  NO_TOOL_CALLS: '模型没有再提出工具调用',
  MAX_ROUNDS: '达到轮数上限',
  MAX_TOOL_CALLS: '达到工具调用上限',
  TIME_BUDGET: '时间预算不足',
  MODEL_CALL_QUOTA: '模型调用配额需留给后续分析',
  STAGNATION: '连续多轮没有新增证据',
  MODEL_TIMEOUT: '模型响应超时',
  MODEL_ERROR: '模型调用失败',
  CAPACITY_UNAVAILABLE: '执行容量已满',
  HARNESS_NOT_PASS: '证据验收未通过',
}

const LOOP_STEP_KINDS = new Set(['loop_round', 'loop_resume', 'loop_stop'])

function buildEvidenceLoopRows(items) {
  const seen = new Set()
  const rows = []
  items.forEach((item) => {
    const metadata = item.metadata || {}
    if (!LOOP_STEP_KINDS.has(metadata.stepKind)) return
    const key = `${metadata.stepKind}:${metadata.round ?? ''}`
    if (seen.has(key)) return
    seen.add(key)
    if (metadata.stepKind === 'loop_round') {
      const tools = Array.isArray(metadata.tools) ? metadata.tools : []
      const outcomes = Array.isArray(metadata.outcomes) ? metadata.outcomes : []
      const added = Array.isArray(metadata.addedEvidenceIds) ? metadata.addedEvidenceIds.length : 0
      const rejected = metadata.stopReason ? formatLoopStop(metadata.stopReason) : ''
      rows.push({
        kind: rejected ? 'analysis' : 'evidence',
        label: `补充取证 · 第 ${metadata.round} 轮`,
        detail: outcomes.length > 0
          ? outcomes.map(formatLoopOutcome).join('、')
          : tools.map(tool => LOOP_TOOL_LABELS[tool] || tool).join('、'),
        meta: rejected || (added > 0 ? `新增 ${added} 条可引用证据` : '没有新增可引用证据'),
      })
    } else if (metadata.stepKind === 'loop_resume') {
      const pending = Array.isArray(metadata.pendingCalls) ? metadata.pendingCalls : []
      rows.push({
        kind: 'plan',
        label: '从断点恢复',
        detail: `已完成 ${metadata.completedRounds ?? 0} 轮，沿用已保存的结果，不重新请求这些轮次。`,
        meta: pending.length > 0 ? `继续执行：${pending.map(tool => LOOP_TOOL_LABELS[tool] || tool).join('、')}` : '',
      })
    } else {
      const added = Array.isArray(metadata.addedEvidenceIds) ? metadata.addedEvidenceIds.length : 0
      rows.push({
        kind: 'evidence',
        label: '补充取证结束',
        detail: [
          formatLoopStop(metadata.stopReason),
          metadata.facets ? `必答维度：${String(metadata.facets).split(';').join('、')}` : '',
          metadata.facetCoverage ? `覆盖情况：${metadata.facetCoverage}` : '',
        ].filter(Boolean).join('；'),
        meta: `共 ${metadata.rounds ?? 0} 轮 · ${metadata.toolCalls ?? 0} 次工具调用 · 新增 ${added} 条证据`,
      })
    }
  })
  return rows
}

const LOOP_APPEND_LABELS = {
  ADDED: '',
  'NOT_ADDED:DUPLICATE': '（与已有证据重复，未入账）',
  'NOT_ADDED:NO_USABLE_DATA': '（没有可用数据）',
  'NOT_ADDED:NO_PROVENANCE': '（缺来源，未入账）',
  'NOT_ADDED:TARGET_MISMATCH': '（标的不符，未入账）',
  'NOT_ADDED:SNAPSHOT_BUDGET_FULL': '（证据预算已满，未入账）',
}

function formatLoopOutcome(value) {
  const [tool, rest = ''] = String(value || '').split('=')
  const [outcome, append = ''] = rest.split(';')
  const label = LOOP_TOOL_LABELS[tool] || tool
  if (LOOP_OUTCOME_LABELS[outcome]) {
    const note = append.startsWith('ADDED:truncated')
      ? '（正文被截短后入账）'
      : (LOOP_APPEND_LABELS[append] ?? (append ? `（${append}）` : ''))
    return `${label}${LOOP_OUTCOME_LABELS[outcome]}${note}`
  }
  return outcome.startsWith('FAILED') ? `${label}失败` : `${label} ${outcome}`.trim()
}

function formatLoopStop(reason) {
  const text = String(reason || '')
  if (LOOP_STOP_LABELS[text]) return LOOP_STOP_LABELS[text]
  if (text.startsWith('REJECTED:')) return `模型的调用未通过校验，未执行（${text.slice('REJECTED:'.length)}）`
  return text || '已结束'
}

/** 把持久化 Trace 压平为聊天页和工作台共用的 reasoning 契约。 */
export function traceToReasoning(trace = {}) {
  let steps = []
  try {
    steps = Array.isArray(trace.steps) ? trace.steps : JSON.parse(trace.steps || '[]')
  } catch {
    return []
  }

  return steps.flatMap((step) => {
    const rows = []
    const durationMs = Number(step.durationMs || 0)
    if (step.attributes?.kind === 'routing-decision') {
      return [{
        type: 'route_decision',
        content: formatTraceRouteDecision(step.attributes),
        metadata: step.attributes,
        durationMs,
      }]
    }
    if (step.thought) rows.push({
      type: 'thought',
      content: textFrom(step.thought),
      metadata: step.attributes || null,
      durationMs: 0,
    })
    if (step.action) rows.push({
      type: 'action',
      content: formatTraceAction(step),
      metadata: step.attributes || null,
      durationMs,
    })
    if (step.observation) rows.push({
      type: 'observation',
      content: textFrom(step.observation),
      metadata: step.attributes || null,
      durationMs: 0,
    })
    return rows
  })
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
  if (metadata.plannedPrimaryAgent) parts.push(`计划主角色 ${metadata.plannedPrimaryAgent}`)
  if (Array.isArray(metadata.plannedSupportingAgents) && metadata.plannedSupportingAgents.length > 0) {
    parts.push(`计划协作角色 ${metadata.plannedSupportingAgents.join('、')}`)
  }
  return parts.join(' · ')
}

function formatTraceAction(step = {}) {
  const actionInput = textFrom(step.actionInput)
  return actionInput ? `${step.action}：${actionInput}` : textFrom(step.action)
}

function formatTraceRouteDecision(metadata = {}) {
  const sourceScores = Object.entries(metadata.sourceScores || {})
    .map(([source, score]) => `${source}=${Number(score || 0).toFixed(2)}`)
    .join(', ')
  const lines = [
    `意图理解：${metadata.intentSummary || '未提供'}`,
    `细粒度意图：${metadata.fineIntent || 'UNKNOWN'} / ${metadata.intentGroup || 'UNKNOWN'}`,
    `选择路由：${metadata.route || 'DIRECT'}`,
    `决策来源：${metadata.source || 'UNKNOWN'}`,
    `置信度：${Number(metadata.confidence || 0).toFixed(2)}`,
    `时效/深度：${metadata.timeSensitivity || 'UNSPECIFIED'} / ${metadata.analysisDepth || 'UNSPECIFIED'}`,
    `依据：${metadata.rationale || '未提供'}`,
    `RAG 命中：${Number(metadata.ragHitCount || 0)}`,
  ]
  if (sourceScores) lines.push(`信号分数：${sourceScores}`)
  if (metadata.needsClarification) lines.push('需要澄清：是')
  if (metadata.fallbackReason) lines.push(`降级原因：${metadata.fallbackReason}`)
  return lines.join('\n')
}

function summarizeDataStage(actions, observations) {
  const categories = [...new Set(actions.map(inferDataCategory).filter(Boolean))]
  const visibleCategories = categories.slice(0, 4)
  const detail = visibleCategories.length > 0
    ? `已完成${visibleCategories.join('、')}${categories.length > visibleCategories.length ? '等' : ''}数据获取。`
    : '已完成所需数据检索与读取。'
  // 多空辩论论点（[pt-…] 开头）是分析文本，不是取数结果，不参与数据阶段告警。
  const warning = observations
    .map(item => normalizeObservationDetail(item.content))
    .filter(item => !/^\[pt-[0-9a-f]{8,}\]/.test(item))
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
