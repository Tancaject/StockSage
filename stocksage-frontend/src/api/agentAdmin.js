import { BASE_URL, apiFetch, responseMessage } from './http.js'

async function adminGet(path, token) {
  const response = await apiFetch(`${BASE_URL}${path}`, {
    headers: { 'X-StockSage-Admin-Token': token },
    redirectOnUnauthorized: false,
  })
  if (!response.ok) {
    const error = new Error(await responseMessage(response))
    error.status = response.status
    throw error
  }
  return response.json()
}

export async function fetchAgentAdminSnapshot(token) {
  const [skills, runtime] = await Promise.all([
    adminGet('/admin/agent/skills', token),
    adminGet('/admin/agent/runtime', token),
  ])
  return { skills, runtime }
}
