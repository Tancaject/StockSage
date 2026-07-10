import { BASE_URL, ensureCsrfToken, getJson, requestJson, requestOk } from './http.js'

export { ensureCsrfToken }

export async function getCurrentUser() {
  return getJson(`${BASE_URL}/auth/me`, { redirectOnUnauthorized: false })
}

export async function login({ email, password }) {
  return requestJson(`${BASE_URL}/auth/login`, {
    method: 'POST',
    redirectOnUnauthorized: false,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
}

export async function register({ email, password }) {
  return requestJson(`${BASE_URL}/auth/register`, {
    method: 'POST',
    redirectOnUnauthorized: false,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
}

export async function logout() {
  await requestOk(`${BASE_URL}/auth/logout`, {
    method: 'POST',
    redirectOnUnauthorized: false,
  })
}
