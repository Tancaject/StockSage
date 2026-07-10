import test from 'node:test'
import assert from 'node:assert/strict'

import { apiFetch, ensureCsrfToken, readCookie } from '../api/http.js'

test('readCookie decodes the named cookie value', () => {
  global.document = { cookie: 'theme=dark; XSRF-TOKEN=raw%20token' }

  assert.equal(readCookie('XSRF-TOKEN'), 'raw token')
})

test('ensureCsrfToken returns the raw csrf cookie after fetching csrf metadata', async () => {
  global.document = { cookie: '' }
  global.fetch = async () => {
    global.document.cookie = 'XSRF-TOKEN=raw-token'
    return {
      ok: true,
      async json() {
        return { token: 'masked-token' }
      },
    }
  }

  assert.equal(await ensureCsrfToken(), 'raw-token')
})

test('apiFetch sends the raw csrf cookie on the first write request after metadata fetch', async () => {
  const calls = []
  global.document = { cookie: '' }
  global.fetch = async (url, options = {}) => {
    calls.push({ url, options })
    if (url === '/api/auth/csrf') {
      global.document.cookie = 'XSRF-TOKEN=raw-token'
      return {
        ok: true,
        async json() {
          return { token: 'masked-token' }
        },
      }
    }
    return { ok: true, status: 204 }
  }

  await apiFetch('/api/auth/login', { method: 'POST' })

  assert.equal(calls[1].options.headers.get('X-XSRF-TOKEN'), 'raw-token')
})
