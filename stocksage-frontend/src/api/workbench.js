import { BASE_URL, apiFetch, responseMessage } from './http.js'

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
  const response = await apiFetch(`${BASE_URL}/reports/investment?${params.toString()}`)
  if (!response.ok) {
    throw new Error(await responseMessage(response))
  }
  return response.json()
}

export async function fetchInvestmentReportDetail(reportId) {
  const id = String(reportId ?? '').trim()
  if (!id) {
    throw new Error('reportId is required')
  }
  const response = await apiFetch(`${BASE_URL}/reports/investment/${encodeURIComponent(id)}`)
  if (!response.ok) {
    throw new Error(await responseMessage(response))
  }
  return response.json()
}

export async function updateInvestmentReportReview(
  reportId,
  { status, comment = '', expectedLockVersion } = {},
) {
  const id = String(reportId ?? '').trim()
  const reviewStatus = String(status || '').trim().toUpperCase()
  const hasLockVersion = expectedLockVersion !== null
    && expectedLockVersion !== undefined
    && String(expectedLockVersion).trim() !== ''
  const lockVersion = Number(expectedLockVersion)
  if (!id) {
    throw new Error('reportId is required')
  }
  if (!reviewStatus) {
    throw new Error('status is required')
  }
  if (!hasLockVersion || !Number.isInteger(lockVersion) || lockVersion < 0) {
    throw new Error('expectedLockVersion is required')
  }

  const response = await apiFetch(`${BASE_URL}/reports/investment/${encodeURIComponent(id)}/review`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      status: reviewStatus,
      comment: String(comment ?? '').trim(),
      expectedLockVersion: lockVersion,
    }),
  })
  if (!response.ok) {
    throw new Error(await responseMessage(response))
  }
  return response.json()
}

export async function fetchStockCockpit({ ticker, period = 'daily', days = 120 } = {}) {
  const symbol = String(ticker || '').trim()
  if (!symbol) {
    throw new Error('ticker is required')
  }
  const params = new URLSearchParams()
  params.set('period', period)
  params.set('days', String(days))
  const response = await apiFetch(`${BASE_URL}/workbench/stocks/${encodeURIComponent(symbol)}/cockpit?${params.toString()}`)
  if (!response.ok) {
    throw new Error(await responseMessage(response))
  }
  return response.json()
}

export async function fetchStockRelations(ticker) {
  const symbol = String(ticker || '').trim()
  if (!symbol) {
    throw new Error('ticker is required')
  }
  const response = await apiFetch(`${BASE_URL}/workbench/stocks/${encodeURIComponent(symbol)}/relations`)
  if (!response.ok) {
    throw new Error(await responseMessage(response))
  }
  return response.json()
}

export async function fetchStockNews(ticker, days = 7) {
  const symbol = String(ticker || '').trim()
  if (!symbol) {
    throw new Error('ticker is required')
  }
  const params = new URLSearchParams()
  params.set('days', String(days))
  const response = await apiFetch(
    `${BASE_URL}/workbench/stocks/${encodeURIComponent(symbol)}/news?${params.toString()}`,
  )
  if (!response.ok) {
    throw new Error(await responseMessage(response))
  }
  return response.json()
}

export async function refreshStockRelations(ticker) {
  const symbol = String(ticker || '').trim()
  if (!symbol) {
    throw new Error('ticker is required')
  }
  const response = await apiFetch(
    `${BASE_URL}/workbench/stocks/${encodeURIComponent(symbol)}/relations/refresh`,
    { method: 'POST' },
  )
  if (!response.ok) {
    throw new Error(await responseMessage(response))
  }
  return response.json()
}

export async function fetchStockIntraday(ticker) {
  const symbol = String(ticker || '').trim()
  if (!symbol) throw new Error('ticker is required')
  const response = await apiFetch(`${BASE_URL}/workbench/stocks/${encodeURIComponent(symbol)}/intraday`)
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
