import { reactive } from 'vue'
import { api, getToken, setToken } from '../lib/api'

export const appState = reactive({
  panel: null,
  auth: { initialized: true, anonymousAccess: true, authenticated: Boolean(getToken()) },
  network: localStorage.getItem('open-panel-network') || 'auto',
  statuses: {},
  loading: false,
  error: ''
})

export async function loadAuth() {
  appState.auth = await api('/api/auth/status')
  return appState.auth
}

export async function loadPanel() {
  appState.loading = true
  appState.error = ''
  try {
    appState.panel = await api(`/api/public/panel?network=${appState.network}`)
    document.title = appState.panel.site.title || 'Open Panel'
  } catch (error) {
    appState.error = error.message
  } finally {
    appState.loading = false
  }
}

export async function loadStatuses() {
  try {
    appState.statuses = await api('/api/public/card-status')
  } catch {
    appState.statuses = {}
  }
}

export function changeNetwork(network) {
  appState.network = network
  localStorage.setItem('open-panel-network', network)
  return loadPanel()
}

export function logOut() {
  api('/api/auth/logout', { method: 'POST' }).catch(() => {})
  setToken('')
  appState.auth.authenticated = false
}
