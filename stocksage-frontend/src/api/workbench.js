import { BASE_URL, apiFetch, getJson, requestJson } from './http.js'

const PROBE_TIMEOUT_MS = 8000

export async function runWorkbenchHealthChecks() {
  const [backend, profile, cockpit] = await Promise.all([
    probe(`${BASE_URL}/chat/conversations`),
    probe(`${BASE_URL}/user/me/profile`),
    probe(`${BASE_URL}/workbench/stocks/NVDA/cockpit?period=daily&days=30`),
  ])

  return {
    backend: backend.ok,
    profile: profile.ok,
    cockpit: cockpit.ok,
    rag: false,
    details: {
      backend,
      profile,
      cockpit,
      rag: { ok: false, status: 'deferred', message: 'checked during research execution' },
    },
  }
}

export async function fetchInvestmentReportVersions({ ticker = '', limit = 20 } = {}) {
  const params = new URLSearchParams()
  if (ticker) params.set('ticker', ticker)
  params.set('limit', String(limit))
  return getJson(`${BASE_URL}/reports/investment?${params.toString()}`)
}

export async function fetchInvestmentReportDetail(reportId) {
  const reportIdSegment = requiredPathSegment(reportId, 'reportId')
  return getJson(`${BASE_URL}/reports/investment/${reportIdSegment}`)
}

export async function updateInvestmentReportReview(
  reportId,
  { status, comment = '', expectedLockVersion } = {},
) {
  const reportIdSegment = requiredPathSegment(reportId, 'reportId')
  const reviewStatus = String(status || '').trim().toUpperCase()
  const hasLockVersion = expectedLockVersion !== null
    && expectedLockVersion !== undefined
    && String(expectedLockVersion).trim() !== ''
  const lockVersion = Number(expectedLockVersion)
  if (!reviewStatus) {
    throw new Error('status is required')
  }
  if (!hasLockVersion || !Number.isInteger(lockVersion) || lockVersion < 0) {
    throw new Error('expectedLockVersion is required')
  }

  return requestJson(`${BASE_URL}/reports/investment/${reportIdSegment}/review`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      status: reviewStatus,
      comment: String(comment ?? '').trim(),
      expectedLockVersion: lockVersion,
    }),
  })
}

export async function fetchStockCockpit({ ticker, period = 'daily', days = 120 } = {}) {
  const tickerSegment = requiredTicker(ticker)
  const params = new URLSearchParams()
  params.set('period', period)
  params.set('days', String(days))
  return getJson(`${BASE_URL}/workbench/stocks/${tickerSegment}/cockpit?${params.toString()}`)
}

export async function fetchStockRelations(ticker) {
  const tickerSegment = requiredTicker(ticker)
  return getJson(`${BASE_URL}/workbench/stocks/${tickerSegment}/relations`)
}

export async function fetchStockNews(ticker, days = 7) {
  const tickerSegment = requiredTicker(ticker)
  const params = new URLSearchParams()
  params.set('days', String(days))
  return getJson(`${BASE_URL}/workbench/stocks/${tickerSegment}/news?${params.toString()}`)
}

export async function refreshStockRelations(ticker) {
  const tickerSegment = requiredTicker(ticker)
  return requestJson(
    `${BASE_URL}/workbench/stocks/${tickerSegment}/relations/refresh`,
    { method: 'POST' },
  )
}

export async function fetchStockIntraday(ticker) {
  const tickerSegment = requiredTicker(ticker)
  const response = await apiFetch(`${BASE_URL}/workbench/stocks/${tickerSegment}/intraday`)
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return response.json()
}

export async function searchWorkbenchStocks({ query = '', limit = 8 } = {}) {
  const term = String(query || '').trim()
  if (!term) return []

  const params = new URLSearchParams()
  params.set('q', term)
  params.set('limit', String(limit))
  const response = await apiFetch(`${BASE_URL}/workbench/stocks/search?${params.toString()}`)
  if (!response.ok) {
    return []
  }
  const body = await response.json()
  return Array.isArray(body) ? body : []
}

function requiredPathSegment(value, name) {
  const segment = String(value ?? '').trim()
  if (!segment) {
    throw new Error(`${name} is required`)
  }
  return encodeURIComponent(segment)
}

function requiredTicker(ticker) {
  return requiredPathSegment(ticker || '', 'ticker')
}

async function probe(url) {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS)
  try {
    const response = await apiFetch(url, { signal: controller.signal })
    return {
      ok: response.ok,
      status: response.status,
      message: response.ok ? 'ok' : `HTTP ${response.status}`,
    }
  } catch (error) {
    return {
      ok: false,
      status: 0,
      message: error.name === 'AbortError' ? 'timeout' : error.message,
    }
  } finally {
    window.clearTimeout(timer)
  }
}
