export const BASE_URL = '/api'

let csrfRequest = null

export function readCookie(name) {
  if (typeof document === 'undefined') return ''
  const prefix = `${name}=`
  const entry = document.cookie
    .split(';')
    .map(part => part.trim())
    .find(part => part.startsWith(prefix))
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : ''
}

export async function ensureCsrfToken() {
  const existing = readCookie('XSRF-TOKEN')
  if (existing) return existing

  if (!csrfRequest) {
    csrfRequest = fetch(`${BASE_URL}/auth/csrf`, {
      method: 'GET',
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
      .then(async response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        const body = await response.json()
        return readCookie('XSRF-TOKEN') || body?.token || ''
      })
      .finally(() => {
        csrfRequest = null
      })
  }
  return csrfRequest
}

export async function apiFetch(url, options = {}) {
  const method = String(options.method || 'GET').toUpperCase()
  const headers = new Headers(options.headers || {})
  if (!headers.has('Accept')) headers.set('Accept', 'application/json')

  if (shouldAttachCsrf(method)) {
    const token = await ensureCsrfToken()
    if (token && !headers.has('X-XSRF-TOKEN')) {
      headers.set('X-XSRF-TOKEN', token)
    }
  }

  const response = await fetch(url, {
    ...options,
    credentials: 'include',
    headers,
  })

  if (response.status === 401 && options.redirectOnUnauthorized !== false) {
    redirectToLogin()
  }

  return response
}

export async function getJson(url, options = {}) {
  const response = await apiFetch(url, options)
  if (!response.ok) throw new Error(await responseMessage(response))
  return response.json()
}

export async function requestJson(url, options = {}) {
  const response = await apiFetch(url, options)
  if (!response.ok) throw new Error(await responseMessage(response))
  if (response.status === 204) return null
  return response.json()
}

export async function requestOk(url, options = {}) {
  const response = await apiFetch(url, options)
  if (!response.ok) throw new Error(await responseMessage(response))
  return response
}

export async function responseMessage(response) {
  let message = `HTTP ${response.status}`
  try {
    const body = await response.json()
    if (body?.message) message = body.message
  } catch {
    // Keep the HTTP status when the backend does not return JSON.
  }
  return message
}

function shouldAttachCsrf(method) {
  return !['GET', 'HEAD', 'OPTIONS'].includes(method)
}

function redirectToLogin() {
  if (typeof window === 'undefined') return
  const path = window.location.pathname
  if (path === '/login' || path === '/register') return
  const target = `${path}${window.location.search || ''}`
  window.location.assign(`/login?redirect=${encodeURIComponent(target)}`)
}
