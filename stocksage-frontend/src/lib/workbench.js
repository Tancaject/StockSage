import { buildAssistantEvidenceSummary } from './researchUi.js'
import { buildChartProvenance } from './chart.js'

export const DEFAULT_FOCUS_AREAS = [
  'valuation',
  'growth',
  'risks',
  'recent filings',
]

const FOCUS_LABELS = {
  valuation: '估值',
  growth: '增长',
  risks: '风险',
  'recent filings': '最新披露',
  news: '新闻',
  'technical setup': '技术形态',
}

const DIMENSION_LABELS = {
  'business mix': '业务结构',
  growth: '增长',
  margin: '利润率',
  valuation: '估值',
  risk: '风险',
  'capital allocation': '资本配置',
  cloud: '云业务',
}

const CHART_STATUS_LABELS = {
  READY: '就绪',
  SAMPLE: '演示样本',
  DEGRADED: '部分数据',
  PENDING: '待加载',
}

const TASK_STATUS_LABELS = {
  PENDING: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const TASK_STAGE_LABELS = {
  CREATED: '已创建',
  ROUTING: '选择研究路径',
  RAG_RETRIEVAL: '检索证据',
  TOOL_PREFETCH: '读取数据',
  AGENT_DEBATE: '多空辩论',
  REPORT_DRAFT: '生成报告',
  REPORT_PERSIST: '保存报告',
  COMPLETE: '完成',
  FAILED: '失败',
}

const TICKER_SUGGESTION_CATALOG = [
  { ticker: 'NVDA', name: 'NVIDIA', market: '美股', keywords: ['ai', 'gpu', 'semiconductor', '英伟达'] },
  { ticker: 'AMZN', name: 'Amazon', market: '美股', keywords: ['aws', 'cloud', 'retail', '亚马逊'] },
  { ticker: 'AAPL', name: 'Apple', market: '美股', keywords: ['iphone', 'consumer hardware', '苹果'] },
  { ticker: 'MSFT', name: 'Microsoft', market: '美股', keywords: ['azure', 'software', '微软'] },
  { ticker: 'META', name: 'Meta Platforms', market: '美股', keywords: ['facebook', 'ads', 'metaverse'] },
  { ticker: 'TSLA', name: 'Tesla', market: '美股', keywords: ['ev', 'auto', '特斯拉'] },
  { ticker: 'MU', name: 'Micron Technology', market: '美股', keywords: ['memory', 'dram', '半导体', '美光'] },
  { ticker: 'NFLX', name: 'Netflix', market: '美股', keywords: ['streaming', 'media', '奈飞'] },
  { ticker: 'VOO', name: 'Vanguard S&P 500 ETF', market: 'ETF', keywords: ['index', 's&p 500', '标普'] },
  { ticker: 'QQQ', name: 'Invesco QQQ Trust', market: 'ETF', keywords: ['nasdaq', '纳指', 'technology'] },
  { ticker: '0700', name: 'Tencent', market: '港股', keywords: ['tencent', '腾讯', 'hk'] },
  { ticker: '9988', name: 'Alibaba', market: '港股', keywords: ['alibaba', '阿里', 'hk'] },
  { ticker: '02513', name: '梦金园', market: '港股', keywords: ['hong kong', 'hk'] },
  { ticker: '600519', name: '贵州茅台', market: 'A股', keywords: ['maotai', '白酒', 'a股'] },
  { ticker: '300750', name: '宁德时代', market: 'A股', keywords: ['catl', 'battery', '新能源', 'a股'] },
]

export const DEFAULT_GATE_TARGETS = {
  context_recall: { min: 0.85, label: 'Recall@K' },
  context_precision: { min: 0.5, label: 'Precision@K' },
  mrr: { min: 0.7, label: 'MRR' },
  ndcg: { min: 0.75, label: 'nDCG@K' },
  citation_precision: { min: 0.85, label: 'Citation precision' },
  no_answer_accuracy: { min: 0.9, label: 'No-answer accuracy' },
  latency_seconds: { max: 45, label: 'Average latency' },
}

export function normalizeTicker(value) {
  return String(value || '')
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
}

export function buildResearchPrompt(ticker, focusAreas = DEFAULT_FOCUS_AREAS) {
  const symbol = normalizeTicker(ticker)
  const focus = normalizeList(focusAreas, DEFAULT_FOCUS_AREAS)
  return [
    `Run a full StockSage single-stock research pass for ${symbol}.`,
    `Focus areas: ${focus.join(', ')}.`,
    'Use SEC filing evidence, market/K-line data, news context when useful, and the bull/bear/research-manager structure.',
    'Separate supported facts, model inference, data gaps, and risks. Include citations when SEC/RAG evidence is used.',
    'Finish with a watch / avoid / investigate-more stance. This is not investment advice.',
  ].join('\n')
}

export function buildComparisonPrompt(tickers, dimensions = ['business mix', 'growth', 'margin', 'risk']) {
  const symbols = normalizeList(tickers).map(normalizeTicker).filter(Boolean)
  const dims = normalizeList(dimensions, ['business mix', 'growth', 'margin', 'risk'])
  return [
    `Compare ${symbols.join(' vs ')} as investable businesses.`,
    `Dimensions: ${dims.join(', ')}.`,
    'Use company-specific SEC evidence and do not mix ticker identities. Prefer tables for side-by-side evidence.',
    'Call out where data is stale, missing, or not directly disclosed. End with the strongest bull and bear case for each ticker.',
  ].join('\n')
}

export function buildEventImpactPrompt({ ticker, eventText, eventSource = '' } = {}) {
  const symbol = normalizeTicker(ticker) || 'TARGET'
  const event = String(eventText || '').trim() || 'Paste the event text, news summary, or filing excerpt here.'
  const sourceLine = eventSource ? `Event source: ${eventSource}` : 'Event source: user-provided event text'
  return [
    `Run a StockSage event impact analysis for ${symbol}.`,
    sourceLine,
    '',
    'Event text:',
    event,
    '',
    'Use the existing read-only research stack: SEC EDGAR/XBRL evidence, RAG citations, market/K-line context, and recent news when useful.',
    'Return exactly these sections:',
    '1. Event summary',
    '2. Impact chain',
    '3. Affected financial metrics',
    '4. Evidence citations',
    '5. Uncertainties and data gaps',
    '6. Follow-up tracking items',
    'Separate filing-backed facts from inference. Do not write to the knowledge base. This is not investment advice.',
  ].join('\n')
}

export function buildPortfolioDiagnosisPrompt(accountId = '') {
  const account = String(accountId || '').trim()
  return [
    'Run an IBKR read-only portfolio diagnosis.',
    account ? `Account: ${account}` : 'Account: use the default IBKR account if available.',
    'Use only read-only IBKR tools: authentication status, accounts, account summary, positions, quotes, and historical bars.',
    'Summarize concentration, single-stock risk, sector or theme exposure, market-sensitive holdings, and filing/news events to monitor.',
    'Do not place, cancel, modify, stage, or suggest executable orders. Do not call or invent trading APIs.',
    'Finish with watch items and questions for the user. This is not investment advice.',
  ].join('\n')
}

export function buildPromptBrief(kind, payload = {}) {
  if (kind === 'compare') {
    const tickers = normalizeList(payload.tickers).map(normalizeTicker).filter(Boolean)
    const dimensions = normalizeList(payload.dimensions, ['business mix', 'growth', 'risk'])
    return {
      title: '对比研究设置',
      rows: [
        { label: '标的', value: tickers.join(' vs ') || '未选择' },
        { label: '比较维度', value: dimensions.map(labelDimension).join('、') },
        { label: '输出', value: '并列表格、强弱项、数据缺口和每个标的的多空理由' },
      ],
    }
  }

  if (kind === 'event') {
    return {
      title: '事件影响设置',
      rows: [
        { label: '标的', value: normalizeTicker(payload.ticker) || '待填写' },
        { label: '来源', value: String(payload.eventSource || '用户输入').trim() || '用户输入' },
        { label: '事件', value: summarizeText(payload.eventText, '等待粘贴事件、公告或新闻摘要') },
        { label: '输出', value: '事件摘要、影响链路、财务指标、证据引用、不确定性和跟踪项' },
      ],
    }
  }

  if (kind === 'portfolio') {
    return {
      title: '持仓诊断设置',
      rows: [
        { label: '账户', value: String(payload.accountId || '').trim() || '默认 IBKR 账户' },
        { label: '边界', value: '只读取认证状态、账户摘要、持仓、行情和历史 K 线' },
        { label: '输出', value: '集中度、单股风险、主题暴露、需跟踪事件和用户问题' },
      ],
    }
  }

  const focus = normalizeList(payload.focusAreas, DEFAULT_FOCUS_AREAS)
  return {
    title: '单股研究设置',
    rows: [
      { label: '标的', value: normalizeTicker(payload.ticker) || '未选择' },
      { label: '关注重点', value: focus.map(labelFocus).join('、') },
      { label: '输出', value: '证据、推理、风险、数据缺口和观察/回避/继续研究结论' },
    ],
  }
}

export function buildWatchlistFromProfile(profile = {}, fallbackTickers = ['NVDA', 'AAPL', 'MSFT', 'AMZN', 'META']) {
  const watch = normalizeList(profile.watchList).map(normalizeTicker).filter(Boolean)
  const holdings = normalizeList(profile.holdings).map(normalizeTicker).filter(Boolean)
  const ordered = uniqueList([...watch, ...holdings])
  const symbols = ordered.length > 0 ? ordered : fallbackTickers.map(normalizeTicker).filter(Boolean)
  const holdingSet = new Set(holdings)
  const watchSet = new Set(watch)

  return symbols.map(ticker => {
    const inHoldings = holdingSet.has(ticker)
    const inWatch = watchSet.has(ticker)
    return {
      ticker,
      status: inHoldings && inWatch ? '持仓/关注' : inHoldings ? '持仓' : inWatch ? '关注' : '样例',
      lastAction: '',
    }
  })
}

export function buildTickerSuggestions(query, watchlistItems = [], limit = 6, searchCandidates = []) {
  const rawQuery = String(query || '').trim()
  const tickerQuery = normalizeTicker(rawQuery)
  const textQuery = rawQuery.toLowerCase()
  if (!rawQuery) return []

  const seen = new Set()
  const watchedTickers = new Set(
    (Array.isArray(watchlistItems) ? watchlistItems : [])
      .map(item => normalizeTicker(item?.ticker))
      .filter(Boolean)
  )

  const liveSuggestions = (Array.isArray(searchCandidates) ? searchCandidates : [])
    .map(item => ({
      ticker: normalizeTicker(item?.ticker || item?.symbol || item?.resolvedCode),
      name: item?.name || item?.shortName || item?.longName || item?.companyName || '搜索结果',
      market: item?.market || item?.exchange || '',
      reason: item?.market || item?.exchange || item?.source || '搜索结果',
      sourceRank: 0,
      searchable: [
        item?.ticker,
        item?.symbol,
        item?.resolvedCode,
        item?.name,
        item?.shortName,
        item?.longName,
        item?.companyName,
        item?.market,
        item?.exchange,
      ].filter(Boolean).join(' '),
    }))
    .filter(item => item.ticker && !watchedTickers.has(item.ticker) && matchesTickerSuggestion(item, tickerQuery, textQuery))

  const catalogSuggestions = TICKER_SUGGESTION_CATALOG
    .map(item => ({
      ...item,
      reason: item.market,
      sourceRank: 1,
      searchable: [item.ticker, item.name, item.market, ...(item.keywords || [])].join(' '),
    }))
    .filter(item => !watchedTickers.has(normalizeTicker(item.ticker)) && matchesTickerSuggestion(item, tickerQuery, textQuery))

  return [...liveSuggestions, ...catalogSuggestions]
    .filter(item => {
      if (seen.has(item.ticker)) return false
      seen.add(item.ticker)
      return true
    })
    .sort((a, b) => suggestionRank(a, tickerQuery, textQuery) - suggestionRank(b, tickerQuery, textQuery))
    .slice(0, limit)
    .map(({ searchable, sourceRank, keywords, ...item }) => item)
}

export function buildReportMarkdown({ ticker, title, stance, messages = [] }) {
  const symbol = normalizeTicker(ticker) || 'STOCK'
  const reportTitle = title?.trim() || `${symbol} 投资备忘录`
  const stanceText = stance?.trim() || '观察'
  const transcript = messages
    .filter(message => message?.content)
    .map(message => {
      const role = message.role === 'assistant' ? 'StockSage' : '用户'
      return `### ${role}\n\n${message.content.trim()}`
    })
    .join('\n\n')
  const provenance = buildReportProvenance(messages)

  return [
    `# ${reportTitle}`,
    '',
    `标的：${symbol}`,
    `判断：${stanceText}`,
    `生成时间：${new Date().toISOString()}`,
    '',
    '## 证据摘录',
    '',
    transcript || '暂无对话摘录。',
    '',
    ...provenance,
    '## 复核事项',
    '',
    '- 分享前复核引用的 SEC 或公告证据。',
    '- 区分文件支持的事实与模型推断。',
    '- 本备忘录不构成投资建议（not investment advice）。',
    '',
  ].join('\n')
}

export function summarizeReportVersions(items = []) {
  return (Array.isArray(items) ? items : []).map(item => {
    const ticker = normalizeTicker(item?.ticker) || 'UNKNOWN'
    const version = Number(item?.reportVersion)
    const versionLabel = `${ticker} v${Number.isFinite(version) ? version : '-'}`
    return {
      ...item,
      ticker,
      versionLabel,
      snapshotLabel: shortHash(item?.dataSnapshotHash),
      contextLabel: shortHash(item?.contextHash),
      modelLabel: formatModelLabel(item?.modelTier, item?.modelName),
      timeLabel: item?.generatedAt || item?.createdAt || '',
      preview: item?.preview || item?.userQuery || '',
    }
  })
}

export function buildLatestBriefDigest({
  ticker = '',
  latestReport = null,
  reportVersions = [],
  focusAreas = [],
} = {}) {
  const currentTicker = normalizeTicker(ticker || latestReport?.ticker || '')
  const recentVersions = normalizeBriefVersions(reportVersions)
    .filter(row => !currentTicker || row.ticker === currentTicker)
  const latestReportTicker = normalizeTicker(latestReport?.ticker)
  const scopedLatestReport = latestReport && (!currentTicker || latestReportTicker === currentTicker)
    ? latestReport
    : null
  const sourceReport = scopedLatestReport || recentVersions[0] || null
  const selectedFocusLabels = normalizeList(focusAreas).map(labelFocus).filter(Boolean)

  if (!sourceReport) {
    return {
      hasReport: false,
      headline: '暂无最新结论',
      recommendation: '待生成',
      summary: '暂无可展示的 AI 投研结论。先运行一次深度研究，或打开报告库查看这个标的的历史版本。',
      evidenceItems: [],
      riskItems: [
        {
          title: '当前缺口',
          detail: '还没有持久化报告，因此无法展示结论、引用证据和模型溯源。',
        },
      ],
      actions: [
        {
          id: 'start-research',
          label: '运行深度研究',
          detail: selectedFocusLabels.length
            ? `按当前焦点生成：${selectedFocusLabels.join('、')}`
            : '生成新的结论、证据和风险清单',
        },
        {
          id: 'open-report',
          label: '查看报告库',
          detail: '检查是否已有历史报告版本',
          versionId: null,
        },
      ],
      provenance: [],
      selectedFocusLabels,
      recentVersions,
    }
  }

  const report = normalizeBriefReport(sourceReport)
  const summary = cleanBriefText(report.preview)
  const evidenceItems = buildBriefEvidenceItems(report)
  const riskItems = buildBriefRiskItems(summary, selectedFocusLabels)
  const primaryVersionId = report.id ?? recentVersions.find(row => row.versionLabel === report.versionLabel)?.id ?? null

  return {
    hasReport: true,
    headline: report.versionLabel,
    recommendation: report.recommendation || report.stance || report.decision || 'UNKNOWN',
    summary,
    evidenceItems,
    riskItems,
    actions: [
      {
        id: 'open-report',
        label: primaryVersionId ? '打开完整报告' : '查看报告库',
        detail: primaryVersionId ? '查看完整版本、引用和历史记录' : '进入报告库检查历史版本',
        versionId: primaryVersionId,
      },
      {
        id: 'start-research',
        label: '按当前焦点重跑研究',
        detail: selectedFocusLabels.length
          ? `覆盖：${selectedFocusLabels.join('、')}`
          : '重新生成最新结论和证据',
      },
    ],
    provenance: [
      { label: '数据快照', value: report.snapshotLabel },
      { label: '上下文', value: report.contextLabel },
      { label: '模型', value: report.modelLabel },
      { label: '生成时间', value: report.timeLabel || '--' },
    ],
    selectedFocusLabels,
    recentVersions,
  }
}

function getFinancialsSnapshot(ticker) {
  const t = String(ticker || '').toUpperCase()
  const catalog = {
    NVDA: { name: '英伟达', pe: 72.5, pb: 45.2, marketCap: '3.12T', currency: '$', turnoverRate: 1.8 },
    AAPL: { name: '苹果', pe: 31.8, pb: 42.1, marketCap: '3.35T', currency: '$', turnoverRate: 0.9 },
    MSFT: { name: '微软', pe: 36.2, pb: 13.8, marketCap: '3.28T', currency: '$', turnoverRate: 0.7 },
    AMZN: { name: '亚马逊', pe: 41.5, pb: 9.2, marketCap: '2.01T', currency: '$', turnoverRate: 1.1 },
    META: { name: 'Meta', pe: 28.6, pb: 8.4, marketCap: '1.25T', currency: '$', turnoverRate: 1.2 },
    TSLA: { name: '特斯拉', pe: 58.2, pb: 11.5, marketCap: '780B', currency: '$', turnoverRate: 2.3 },
    '600519': { name: '贵州茅台', pe: 25.4, pb: 6.8, marketCap: '2.05万亿', currency: '¥', turnoverRate: 0.2 },
    '300750': { name: '宁德时代', pe: 18.2, pb: 4.5, marketCap: '8600亿', currency: '¥', turnoverRate: 1.1 },
    '0700': { name: '腾讯控股', pe: 22.8, pb: 4.9, marketCap: '3.65万亿', currency: 'HK$', turnoverRate: 0.3 },
    '9988': { name: '阿里巴巴', pe: 12.5, pb: 1.25, marketCap: '1.62万亿', currency: 'HK$', turnoverRate: 0.5 },
  }
  
  if (catalog[t]) {
    return catalog[t]
  }
  
  let hash = 0
  for (let i = 0; i < t.length; i++) {
    hash = (hash << 5) - hash + t.charCodeAt(i)
    hash |= 0
  }
  hash = Math.abs(hash)
  
  const isAShare = /^\d{6}$/.test(t) || t.endsWith('SH') || t.endsWith('SZ')
  const isHKShare = (t.length <= 5 && /^\d+$/.test(t)) || t.endsWith('HK')
  const currency = isAShare ? '¥' : isHKShare ? 'HK$' : '$'
  const pe = parseFloat((15 + (hash % 30) + (hash % 10) / 10).toFixed(1))
  const pb = parseFloat((1.2 + (hash % 8) + (hash % 10) / 10).toFixed(2))
  const turnoverRate = parseFloat((0.3 + (hash % 3) + (hash % 10) / 10).toFixed(2))
  
  const capBillions = 10 + (hash % 490)
  const marketCap = currency === '¥' || currency === 'HK$' 
    ? `${capBillions * 10}亿` 
    : `${capBillions}B`
    
  return {
    name: t,
    pe,
    pb,
    marketCap,
    currency,
    turnoverRate,
  }
}

function getSentimentScore(latestReport, ticker) {
  if (!latestReport) return 50
  const stance = String(latestReport.stance || latestReport.decision || '').toLowerCase()
  
  let hash = 0
  const t = String(ticker || '').toUpperCase()
  for (let i = 0; i < t.length; i++) {
    hash = (hash << 5) - hash + t.charCodeAt(i)
    hash |= 0
  }
  hash = Math.abs(hash)
  const delta = hash % 6 - 3 // -3 to +2
  
  if (['watch', 'bull', 'buy', '看多', '看涨'].some(word => stance.includes(word))) {
    return 80 + delta
  }
  if (['avoid', 'bear', 'sell', '看空', '看跌'].some(word => stance.includes(word))) {
    return 20 + delta
  }
  return 50 + delta
}

export function normalizeCockpit(payload = {}) {
  const chart = payload?.chart && Array.isArray(payload.chart.points) && payload.chart.points.length
    ? payload.chart
    : null
  const latestReport = summarizeCockpitReport(payload?.latestReport)
  const evidencePreview = summarizeEvidencePreview(
    payload?.evidencePreview,
    latestReport?.citations || payload?.latestReport?.citations || []
  )
  const chartStatus = String(payload?.chartStatus || (chart ? 'READY' : 'DEGRADED')).toUpperCase()
  const ticker = normalizeTicker(payload?.ticker) || 'STOCK'
  
  // 从 K 线提取最新价和涨跌幅
  let price = 0
  let change = 0
  let changePercent = 0
  let volume = 0
  
  if (chart && chart.points.length > 0) {
    const points = chart.points
    const latestPoint = points[points.length - 1]
    price = latestPoint.close ?? latestPoint.Close ?? price
    volume = latestPoint.volume ?? latestPoint.Volume ?? volume
    
    const prevPoint = points.length > 1 ? points[points.length - 2] : null
    const prevClose = prevPoint ? (prevPoint.close ?? prevPoint.Close) : (latestPoint.open ?? latestPoint.Open)
    if (prevClose && prevClose > 0) {
      change = price - prevClose
      changePercent = (change / prevClose) * 100
    }
  }
  
  const financials = getFinancialsSnapshot(ticker)
  const sentimentScore = getSentimentScore(latestReport, ticker)
  const quote = {
    price,
    change,
    changePercent,
    volume,
    turnover: price * volume * 0.95,
    ...financials,
  }

  return {
    ticker,
    chart,
    hasChart: Boolean(chart),
    chartStatus,
    chartStatusLabel: CHART_STATUS_LABELS[chartStatus] || chartStatus,
    chartTone: chartTone(chartStatus),
    isSample: chartStatus === 'SAMPLE',
    chartMessage: payload?.chartMessage || (chart ? 'K 线数据已加载' : '暂无 K 线数据'),
    latestReport,
    taskTimeline: summarizeTaskTimeline(payload?.taskTimeline),
    evidencePreview,
    generatedAt: payload?.generatedAt || '',
    quote,
    sentimentScore,
  }
}

export function summarizeCockpitReport(report = null) {
  if (!report) return null
  const ticker = normalizeTicker(report.ticker) || 'UNKNOWN'
  const version = Number(report.reportVersion)
  const citations = Array.isArray(report.citations)
    ? report.citations.filter(Boolean).map(String)
    : []
  return {
    ...report,
    ticker,
    citations,
    versionLabel: `${ticker} v${Number.isFinite(version) ? version : '-'}`,
    snapshotLabel: shortHash(report.dataSnapshotHash),
    contextLabel: shortHash(report.contextHash),
    modelLabel: formatModelLabel(report.modelTier, report.modelName),
    timeLabel: report.generatedAt || report.createdAt || '',
    preview: report.preview || report.userQuery || '',
  }
}

export function summarizeTaskTimeline(items = []) {
  return (Array.isArray(items) ? items : []).map(item => {
    const status = String(item?.status || 'PENDING').toUpperCase()
    const stage = String(item?.stage || 'CREATED').toUpperCase()
    return {
      id: item?.id ?? null,
      conversationId: item?.conversationId ?? null,
      ticker: normalizeTicker(item?.ticker) || '',
      status,
      stage,
      statusLabel: TASK_STATUS_LABELS[status] || status.replaceAll('_', ' '),
      stageLabel: TASK_STAGE_LABELS[stage] || stage.replaceAll('_', ' '),
      attempts: Number.isFinite(Number(item?.attempts)) ? Number(item.attempts) : 0,
      resultReportVersionId: item?.resultReportVersionId ?? null,
      startedAt: item?.startedAt || '',
      heartbeatAt: item?.heartbeatAt || '',
      completedAt: item?.completedAt || '',
      createdAt: item?.createdAt || '',
      updatedAt: item?.updatedAt || '',
      timeLabel: item?.heartbeatAt || item?.completedAt || item?.startedAt || item?.createdAt || '',
      errorMessage: item?.errorMessage || '',
      tone: taskTone(status),
    }
  })
}

export function summarizeEvidencePreview(items = [], citations = []) {
  const evidenceRows = (Array.isArray(items) ? items : [])
    .filter(item => item?.evidence || item?.dimension || item?.source)
    .map(item => ({
      dimension: item.dimension || 'Evidence',
      evidence: item.evidence || 'No evidence text was provided.',
      implication: item.implication || '',
      source: item.source || 'report',
    }))
  if (evidenceRows.length) return evidenceRows

  return (Array.isArray(citations) ? citations : [])
    .filter(Boolean)
    .map(citation => ({
      dimension: 'Citation',
      evidence: String(citation),
      implication: 'Report citation carried forward from the latest persisted version.',
      source: 'report',
    }))
}

function taskTone(status) {
  if (status === 'SUCCEEDED') return 'positive'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'running'
  return 'pending'
}

function chartTone(status) {
  if (status === 'SAMPLE') return 'sample'
  if (status === 'READY') return 'ready'
  if (status === 'DEGRADED') return 'degraded'
  return 'pending'
}

export function shortHash(value) {
  const text = String(value || '').trim()
  return text ? text.slice(0, 12) : '--'
}

export function formatModelLabel(modelTier, modelName) {
  if (String(modelTier || '').toUpperCase() === 'DEMO') return '离线演示'
  const parts = [modelTier, modelName].filter(Boolean)
  return parts.length ? parts.join(' / ') : '模型待定'
}

function buildReportProvenance(messages) {
  const rows = messages
    .filter(message => message?.role === 'assistant')
    .flatMap((message, index) => {
      const messageLabel = `Assistant ${index + 1}`
      const evidence = buildAssistantEvidenceSummary(message)
      const evidenceRows = evidence.badges.map(badge => {
        const detail = badge.detail ? ` (${badge.detail})` : ''
        return `- ${messageLabel} ${badge.label}: ${badge.value}${detail}`
      })
      const chartRows = (message.charts || [])
        .filter(chart => chart?.sourceTool || chart?.symbol || chart?.period)
        .map(chart => {
          const pieces = [
            chart.symbol,
            chart.period,
            chart.sourceTool,
          ].filter(Boolean).join(' · ')
          return `- ${messageLabel} 图表来源: ${pieces}`
        })
      const chartDetailRows = (message.charts || [])
        .map(chart => {
          const provenance = buildChartProvenance(chart)
          if (!provenance.symbol && !provenance.period && !provenance.sourceTool) return ''
          const pieces = [
            provenance.symbol,
            provenance.period,
            provenance.bar,
            provenance.sourceTool,
          ].filter(Boolean).join(' / ')
          const indicators = provenance.indicators.length ? `; indicators: ${provenance.indicators.join(', ')}` : ''
          const generatedAt = provenance.generatedAt ? `; generated: ${provenance.generatedAt}` : ''
          return `- ${messageLabel} chart provenance: ${pieces}${indicators}${generatedAt}`
        })
        .filter(Boolean)
      return [...evidenceRows, ...chartRows, ...chartDetailRows]
    })

  if (rows.length === 0) return []

  return [
    '## Research Provenance',
    '',
    ...rows,
    '',
  ]
}

export function summarizeRagEval(result, gates = DEFAULT_GATE_TARGETS) {
  const averages = result?.averages || {}
  const ragasMetrics = Object.entries(averages)
    .filter(([metric]) => metric.startsWith('ragas_'))
    .map(([metric, value]) => ({
      metric,
      label: readableMetricLabel(metric),
      value: numberOrNull(value),
      status: numberOrNull(value) === null ? 'missing' : 'pass',
    }))
  const gateRows = Object.entries(gates).map(([metric, target]) => {
    const value = numberOrNull(averages[metric])
    const failed = value !== null && (
      target.min !== undefined && value < target.min
      || target.max !== undefined && value > target.max
    )
    return {
      metric,
      label: target.label || metric,
      value,
      target,
      status: value === null ? 'missing' : failed ? 'fail' : 'pass',
    }
  })
  const failedMetrics = gateRows.filter(row => row.status === 'fail')
  const worstCases = [...(result?.cases || [])]
    .map(item => ({
      id: item.id,
      category: item.category || 'uncategorized',
      score: caseQualityScore(item.metrics || {}),
      metrics: item.metrics || {},
      question: item.question || item.user_input || '',
      answer: item.answer || item.response || '',
      expectedAnswer: item.expected_answer || item.reference_answer || item.reference || '',
      contexts: extractCaseContexts(item),
      citations: Array.isArray(item.citations) ? item.citations : [],
    }))
    .sort((a, b) => a.score - b.score)
    .slice(0, 5)

  return {
    createdAt: result?.created_at || result?.createdAt || '',
    caseCount: Number(result?.case_count || result?.caseCount || 0),
    averages,
    ragasMetrics,
    gates: gateRows,
    failedMetrics,
    worstCases,
    status: failedMetrics.length > 0 ? 'fail' : 'pass',
  }
}

export function summarizeHealthChecks(checks) {
  const required = checks.filter(check => check.required !== false)
  const requiredReady = required.filter(check => check.ok).length
  const optionalReady = checks.filter(check => check.required === false && check.ok).length
  return {
    status: requiredReady === required.length ? 'pass' : 'fail',
    requiredReady,
    requiredTotal: required.length,
    optionalReady,
    total: checks.length,
  }
}

export function buildHealthChecks(results) {
  return [
    {
      id: 'backend',
      label: '后端接口',
      required: true,
      ok: Boolean(results.backend),
      detail: results.backend ? '聊天接口已响应' : '聊天接口不可用',
    },
    {
      id: 'profile',
      label: '用户画像记忆',
      required: true,
      ok: Boolean(results.profile),
      detail: results.profile ? '画像接口已响应' : '画像接口不可用',
    },
    {
      id: 'cockpit',
      label: '行情数据接口',
      required: true,
      ok: Boolean(results.cockpit ?? results.rag),
      detail: (results.cockpit ?? results.rag) ? '行情接口已响应' : '行情接口不可用',
    },
    {
      id: 'rag',
      label: 'RAG 引用',
      required: false,
      ok: Boolean(results.rag),
      detail: results.rag
        ? '检索能力已通过运行探针'
        : '真实检索在研究执行时验证；调试接口失败不会阻断研究',
    },
    {
      id: 'trace',
      label: '链路追踪',
      required: false,
      ok: true,
      detail: '可从助手消息查看 trace 明细',
    },
  ]
}

export function markHealthCheck(checks = [], id, ok, detail = '') {
  return (Array.isArray(checks) ? checks : []).map(check => {
    if (check?.id !== id) return check
    return {
      ...check,
      ok: Boolean(ok),
      detail: detail || check.detail,
    }
  })
}

function normalizeBriefVersions(reportVersions = []) {
  return summarizeReportVersions(reportVersions)
    .filter(Boolean)
    .slice(0, 5)
}

function normalizeBriefReport(report = {}) {
  const summarized = report?.versionLabel
    ? { ...report }
    : summarizeReportVersions([report])[0] || {}
  const citations = Array.isArray(report?.citations)
    ? report.citations.filter(Boolean).map(String)
    : []

  return {
    ...summarized,
    citations,
    ticker: normalizeTicker(summarized.ticker || report?.ticker) || 'UNKNOWN',
    versionLabel: summarized.versionLabel || `${normalizeTicker(report?.ticker) || 'UNKNOWN'} v-`,
    snapshotLabel: summarized.snapshotLabel || shortHash(report?.dataSnapshotHash),
    contextLabel: summarized.contextLabel || shortHash(report?.contextHash),
    modelLabel: summarized.modelLabel || formatModelLabel(report?.modelTier, report?.modelName),
    timeLabel: summarized.timeLabel || report?.generatedAt || report?.createdAt || '',
    preview: summarized.preview || report?.preview || report?.userQuery || '',
  }
}

function cleanBriefText(value) {
  const text = String(value || '').trim()
  return text || '最新报告已生成，但没有返回摘要文本。请打开完整报告查看正文与引用。'
}

function buildBriefEvidenceItems(report) {
  const citations = Array.isArray(report?.citations) ? report.citations : []
  if (citations.length > 0) {
    return citations.slice(0, 3).map((citation, index) => ({
      title: `证据 ${index + 1}`,
      detail: citation,
    }))
  }

  return [
    {
      title: '结论摘要',
      detail: report.preview || '报告没有携带引用片段；请打开完整报告复核正文证据。',
    },
  ]
}

function buildBriefRiskItems(summary, selectedFocusLabels = []) {
  const text = String(summary || '')
  const risks = []
  if (/估值|valuation|multiple|premium/i.test(text) || selectedFocusLabels.includes('估值')) {
    risks.push({
      title: '估值复核',
      detail: '先核对估值假设、同业倍数和盈利预期，避免只看趋势得出结论。',
    })
  }
  if (/风险|risk|下行|回撤|uncertain|不确定/i.test(text) || selectedFocusLabels.includes('风险')) {
    risks.push({
      title: '风险清单',
      detail: '把报告里的风险因子拆成可跟踪事项，并区分文件事实与模型推断。',
    })
  }
  if (/披露|filing|10-[kq]|实时|校验|stale|最新/i.test(text) || selectedFocusLabels.includes('最新披露')) {
    risks.push({
      title: '数据新鲜度',
      detail: '复核最新财报、公告和实时行情；未经校验的数据只能作为参考提示。',
    })
  }

  if (risks.length === 0) {
    risks.push({
      title: '复核缺口',
      detail: '当前摘要没有明确风险项，建议打开完整报告检查引用、数据时点和反方论证。',
    })
  }

  return risks.slice(0, 3)
}

function matchesTickerSuggestion(item, tickerQuery, textQuery) {
  const ticker = normalizeTicker(item?.ticker)
  const searchable = String(item?.searchable || '').toLowerCase()
  return Boolean(
    (tickerQuery && ticker.includes(tickerQuery))
    || (textQuery && searchable.includes(textQuery))
  )
}

function suggestionRank(item, tickerQuery, textQuery) {
  const ticker = normalizeTicker(item?.ticker)
  const searchable = String(item?.searchable || '').toLowerCase()
  const sourceRank = Number(item?.sourceRank || 0) * 1000
  if (tickerQuery && ticker === tickerQuery) return sourceRank
  if (tickerQuery && ticker.startsWith(tickerQuery)) return sourceRank + 10
  if (textQuery && searchable.startsWith(textQuery)) return sourceRank + 20
  if (textQuery && searchable.includes(` ${textQuery}`)) return sourceRank + 30
  return sourceRank + 60
}

function labelFocus(value) {
  const key = String(value || '').trim().toLowerCase()
  return FOCUS_LABELS[key] || String(value || '').trim()
}

function labelDimension(value) {
  const key = String(value || '').trim().toLowerCase()
  return DIMENSION_LABELS[key] || String(value || '').trim()
}

function summarizeText(value, fallback) {
  const text = String(value || '').trim()
  if (!text) return fallback
  return text.length > 90 ? `${text.slice(0, 90).trim()}...` : text
}

function uniqueList(values) {
  return [...new Set(values)]
}

function normalizeList(value, fallback = []) {
  const list = Array.isArray(value) ? value : String(value || '').split(',')
  const normalized = list.map(item => String(item || '').trim()).filter(Boolean)
  return normalized.length > 0 ? normalized : fallback
}

function numberOrNull(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function caseQualityScore(metrics) {
  const values = [
    metrics.context_recall,
    metrics.context_precision,
    metrics.mrr,
    metrics.ndcg,
    metrics.citation_precision,
    metrics.no_answer_accuracy,
  ]
    .map(numberOrNull)
    .filter(value => value !== null)
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0
}

function extractCaseContexts(item) {
  const retrieval = item?.retrieval || {}
  const candidates = [
    item.contexts,
    item.retrieved_contexts,
    retrieval.final_contexts,
    retrieval.finalContexts,
    retrieval.contexts,
  ]
  const contexts = candidates.find(value => Array.isArray(value)) || []
  return contexts.slice(0, 5).map((context, index) => ({
    rank: context.rank ?? index + 1,
    ticker: context.ticker || context.symbol || '',
    section: context.section || context.section_name || '',
    filingType: context.filing_type || context.filingType || '',
    filingDate: context.filing_date || context.filingDate || '',
    content: String(context.content || context.text || '').slice(0, 420),
  }))
}

function readableMetricLabel(metric) {
  return String(metric || '')
    .replace(/^ragas_/, 'RAGAS ')
    .replace(/_/g, ' ')
}
