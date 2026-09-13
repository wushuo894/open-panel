import { appUrl } from './paths'

const TOKEN_KEY = 'open-panel-token'

function migrateToken() {
  const token = localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY) || ''
  if (token) localStorage.setItem(TOKEN_KEY, token)
  sessionStorage.removeItem(TOKEN_KEY)
  return token
}

export const getToken = () => localStorage.getItem(TOKEN_KEY) || migrateToken()
export const setToken = (token) => {
  sessionStorage.removeItem(TOKEN_KEY)
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export async function api(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(appUrl(path), { ...options, headers })
  const type = response.headers.get('content-type') || ''
  const payload = type.includes('json') ? await response.json() : await response.blob()
  if (!response.ok || (payload && typeof payload === 'object' && 'code' in payload && payload.code !== 200)) {
    if (response.status === 401) setToken('')
    throw new Error(payload?.message || `请求失败 (${response.status})`)
  }
  return payload?.data ?? payload
}
