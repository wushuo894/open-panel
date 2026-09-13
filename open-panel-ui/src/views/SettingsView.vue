<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import { api, getToken, setToken } from '../lib/api'
import { appUrl } from '../lib/paths'
import { applySiteTheme, extractThemeColor, normalizeThemeColor } from '../lib/theme'
import { appState } from '../stores/app'
import MdiIconPicker from '../components/MdiIconPicker.vue'

const router = useRouter()
const theme = useTheme()
const tab = ref('appearance')
const config = ref(null)
const loading = ref(true)
const saving = ref(false)
const message = ref('')
const error = ref('')
const groupDialog = ref(false)
const groupDraft = ref(null)
const importInput = ref(null)
const updateInfo = ref(null)
const updateLoading = ref(false)
const updateAutoChecked = ref(false)
const uploadingBackground = ref(false)
const extractingThemeColor = ref(false)
const usernameForm = ref({ newUsername: '', currentPassword: '' })
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const visibleSecrets = ref({ usernamePassword: false, currentPassword: false, newPassword: false, confirmPassword: false, githubToken: false })
const changingUsername = ref(false)
const changingPassword = ref(false)
const currentPageHost = globalThis.location?.hostname?.replace(/^\[|\]$/g, '') || '127.0.0.1'
const scanForm = ref({ target: currentPageHost, range: [80, 65535] })
const scanJob = ref(null)
const scanGroupId = ref('')
const selectedServices = ref([])
const dockerOverview = ref({ available: false, error: '', containers: [], activeJob: null })
const dockerLoading = ref(false)
const dockerJob = ref(null)
const dockerAction = ref('')
const composeDialog = ref(false)
const composeLoading = ref(false)
const composeView = ref(null)
const cleanupDialog = ref(false)
const cleanupLoading = ref(false)
const cleanupPreviewLoading = ref(false)
const cleanupPreview = ref(null)
const dockerClock = ref(Date.now())
const dockerOverviewLoadedAt = ref(Date.now())
const predefinedThemeColors = [
  '#d8f257', '#409eff', '#109d58', '#bf3545', '#cb7574', '#9aaec7', '#2ec5b6', '#1c1c1c', '#f7b1a9',
  '#b18874', '#e9ba86', '#f68f6c', '#f0458b', '#c35653', '#40494e', '#6f0000', '#8d3647',
  '#e6c5d0', '#2377b3', '#49312d', '#7c9ab6', '#a5b18d', '#e8662a', '#ab5d50'
]
let scanTimer
let dockerTimer
let dockerClockTimer

const sortedGroups = computed(() => [...(config.value?.groups || [])]
  .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0)))
const coverGroupId = computed({
  get: () => config.value?.page?.cover?.groupIds?.[0] || null,
  set: value => {
    config.value.page.cover.groupIds = value ? [value] : []
  }
})

const wallpaperText = computed({
  get: () => config.value?.page.cover.wallpapers.join('\n') || '',
  set: value => { config.value.page.cover.wallpapers = value.split('\n').map(item => item.trim()).filter(Boolean) }
})
const previewWallpaper = computed(() => appUrl(config.value?.page?.cover?.wallpapers?.[0] || config.value?.site?.background || ''))
const footerText = computed({
  get: () => config.value?.page.footer.lines.join('\n') || '',
  set: value => { config.value.page.footer.lines = value.split('\n').slice(0, 4) }
})
const corsText = computed({
  get: () => config.value?.security.corsOrigins.join('\n') || '',
  set: value => { config.value.security.corsOrigins = lines(value) }
})
const allowlistText = computed({
  get: () => config.value?.security.ipAllowlist.join('\n') || '',
  set: value => { config.value.security.ipAllowlist = lines(value) }
})
const proxiesText = computed({
  get: () => config.value?.security.trustedProxyIps.join('\n') || '',
  set: value => { config.value.security.trustedProxyIps = lines(value) }
})

const scanRunning = computed(() => ['queued', 'running', 'cancelling'].includes(scanJob.value?.status))
const scanProgress = computed(() => scanJob.value?.total ? Math.round(scanJob.value.scanned / scanJob.value.total * 100) : 0)
const dockerJobRunning = computed(() => ['queued', 'running', 'cancelling'].includes(dockerJob.value?.status))
const dockerUpdates = computed(() => dockerOverview.value.containers.filter(container => container.updateAvailable).length)
const sortedDockerContainers = computed(() => [...dockerOverview.value.containers]
  .sort((left, right) => Number(Boolean(right.updateAvailable)) - Number(Boolean(left.updateAvailable))))

onMounted(() => {
  load()
  dockerClockTimer = setInterval(() => { dockerClock.value = Date.now() }, 1000)
})
onBeforeUnmount(() => { clearTimeout(scanTimer); clearTimeout(dockerTimer); clearInterval(dockerClockTimer) })
watch(tab, value => {
  if (value === 'about' && !updateAutoChecked.value) {
    updateAutoChecked.value = true
    checkUpdate()
  }
})
watch(() => [config.value?.site?.theme, config.value?.site?.themeColor], () => {
  if (config.value?.site) applySiteTheme(theme, config.value.site)
})

async function load() {
  loading.value = true
  try {
    const [loadedConfig, version] = await Promise.all([
      api('/api/admin/config'),
      api('/api/admin/version')
    ])
    config.value = loadedConfig
    usernameForm.value.newUsername = loadedConfig.security.username
    updateInfo.value = version
    scanGroupId.value = sortedGroups.value[0]?.id || ''
    loadDockerOverview()
  }
  catch (e) { if (!getToken()) router.replace('/login'); else error.value = e.message }
  finally { loading.value = false }
}

async function save() {
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    normalizeAllSort()
    config.value = await api('/api/admin/config', { method: 'PUT', body: JSON.stringify(config.value) })
    message.value = '设置已保存'
  } catch (e) { error.value = e.message }
  finally { saving.value = false }
}

function lines(value) { return value.split('\n').map(item => item.trim()).filter(Boolean) }
function uuid() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID()
  const bytes = new Uint8Array(16)
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index++) bytes[index] = Math.floor(Math.random() * 256)
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0'))
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10).join('')}`
}

function cardsForGroup(groupId) {
  return (config.value?.cards || [])
    .filter(card => card.groupId === groupId)
    .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
}

function normalizeGroupSort() {
  sortedGroups.value.forEach((group, index) => { group.sort = index })
}

function normalizeCardSort(groupId) {
  cardsForGroup(groupId).forEach((card, index) => { card.sort = index })
}

function normalizeAllSort() {
  normalizeGroupSort()
  sortedGroups.value.forEach(group => normalizeCardSort(group.id))
}

function moveGroup(group, direction) {
  const ordered = sortedGroups.value
  const index = ordered.findIndex(item => item.id === group.id)
  const target = index + direction
  if (index < 0 || target < 0 || target >= ordered.length) return
  ;[ordered[index], ordered[target]] = [ordered[target], ordered[index]]
  ordered.forEach((item, sort) => { item.sort = sort })
}

function addGroup() {
  groupDraft.value = {
    id: uuid(),
    title: '',
    icon: 'mdi-folder-outline',
    displayMode: 'detail',
    enabled: true,
    sort: sortedGroups.value.length
  }
  groupDialog.value = true
}

function commitGroup() {
  const title = groupDraft.value?.title?.trim()
  if (!title) return
  groupDraft.value.title = title
  groupDraft.value.icon = groupDraft.value.icon?.trim() || 'mdi-folder-outline'
  config.value.groups.push(groupDraft.value)
  if (!scanGroupId.value) scanGroupId.value = groupDraft.value.id
  groupDialog.value = false
  groupDraft.value = null
  message.value = `已添加分组“${title}”，保存后生效`
}

function removeGroup(group) {
  config.value.groups = config.value.groups.filter(item => item.id !== group.id)
  config.value.cards = config.value.cards.filter(card => card.groupId !== group.id)
  config.value.page.cover.groupIds = config.value.page.cover.groupIds.filter(id => id !== group.id)
  normalizeGroupSort()
}

async function startScan() {
  error.value = ''
  selectedServices.value = []
  try {
    scanJob.value = await api('/api/admin/scan', {
      method: 'POST',
      body: JSON.stringify({
        target: scanForm.value.target,
        startPort: scanForm.value.range[0],
        endPort: scanForm.value.range[1]
      })
    })
    pollScan()
  } catch (e) { error.value = e.message }
}

async function pollScan() {
  clearTimeout(scanTimer)
  if (!scanJob.value?.id) return
  try {
    scanJob.value = await api(`/api/admin/scan/${scanJob.value.id}`)
    if (scanRunning.value) scanTimer = setTimeout(pollScan, 700)
    else if (scanJob.value.status === 'failed') error.value = scanJob.value.error || '扫描失败'
  } catch (e) { error.value = e.message }
}

async function cancelScan() {
  if (!scanJob.value?.id) return
  try { await api(`/api/admin/scan/${scanJob.value.id}`, { method: 'DELETE' }); pollScan() }
  catch (e) { error.value = e.message }
}

function addScannedServices() {
  if (!scanGroupId.value) { error.value = '请先选择目标分组'; return }
  const selected = new Set(selectedServices.value)
  for (const service of scanJob.value?.results || []) {
    if (!selected.has(service.id)) continue
    config.value.cards.push({
      id: uuid(), groupId: scanGroupId.value, type: 'service', title: service.title,
      remark: service.description, icon: 'mdi-application-outline', iconUrl: service.iconUrl,
      enabled: true, sort: cardsForGroup(scanGroupId.value).length, openTarget: 'new', custom: null, system: null, docker: null,
      service: { serviceType: 'generic', internalUrl: service.url, externalUrl: service.url, statusUrl: service.url, token: '' }
    })
  }
  message.value = `已添加 ${selected.size} 个 Web 服务卡片，保存后生效`
  selectedServices.value = []
}

async function uploadBackground(value) {
  const file = Array.isArray(value) ? value[0] : value
  if (!file) return
  uploadingBackground.value = true
  error.value = ''
  const body = new FormData()
  body.append('file', file)
  try {
    const result = await api('/api/admin/assets/background', { method: 'POST', body })
    config.value.page.cover.wallpapers = [result.url, ...config.value.page.cover.wallpapers.filter(url => url !== result.url)]
    config.value.site.background = result.url
    message.value = '背景图片已上传并设为当前壁纸，点击保存后生效'
  } catch (e) { error.value = e.message }
  finally { uploadingBackground.value = false }
}

function normalizeThemeColorField() {
  config.value.site.themeColor = normalizeThemeColor(config.value.site.themeColor)
}

async function pickThemeColorFromWallpaper() {
  if (!previewWallpaper.value) {
    error.value = '请先设置壁纸'
    return
  }
  extractingThemeColor.value = true
  error.value = ''
  try {
    config.value.site.themeColor = await extractThemeColor(previewWallpaper.value)
    message.value = `已从壁纸选取主题色 ${config.value.site.themeColor}，点击保存后生效`
  } catch (e) {
    error.value = e.message
  } finally {
    extractingThemeColor.value = false
  }
}

function addEngine() {
  config.value.searchEngines.push({ id: uuid(), name: '新搜索引擎', icon: 'mdi-magnify', urlTemplate: 'https://example.com/search?q={query}', enabled: true, sort: config.value.searchEngines.length })
}

function removeEngine(engine) { config.value.searchEngines = config.value.searchEngines.filter(item => item.id !== engine.id) }

async function exportConfig() {
  try {
    const response = await fetch(appUrl('/api/admin/config/export'), { headers: { Authorization: `Bearer ${getToken()}` } })
    if (!response.ok) throw new Error('导出失败')
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'open-panel-config.zip'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (e) { error.value = e.message }
}

async function importConfig(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) {
    error.value = '请选择 ZIP 配置备份'
    event.target.value = ''
    return
  }
  const body = new FormData()
  body.append('file', file)
  try {
    config.value = await api('/api/admin/config/import', { method: 'POST', body })
    message.value = '配置已导入'
  } catch (e) { error.value = e.message }
  event.target.value = ''
}

async function checkUpdate() {
  updateLoading.value = true
  try { updateInfo.value = await api('/api/admin/update') }
  catch (e) { error.value = e.message }
  finally { updateLoading.value = false }
}

async function loadDockerOverview(showLoading = true) {
  if (showLoading) dockerLoading.value = true
  try {
    dockerOverview.value = await api('/api/admin/docker')
    dockerOverviewLoadedAt.value = Date.now()
    if (dockerOverview.value.activeJob && dockerOverview.value.activeJob.id !== dockerJob.value?.id) {
      dockerJob.value = dockerOverview.value.activeJob
      pollDockerJob()
    }
  } catch {
    dockerOverview.value = { available: false, error: '', containers: [], activeJob: null }
  } finally {
    dockerLoading.value = false
  }
}

async function startDockerCheck() {
  clearTimeout(dockerTimer)
  error.value = ''
  try {
    dockerJob.value = await api('/api/admin/docker/check', { method: 'POST' })
    pollDockerJob()
  } catch (e) { error.value = e.message }
}

async function startDockerUpdate(container) {
  clearTimeout(dockerTimer)
  error.value = ''
  try {
    dockerJob.value = await api('/api/admin/docker/update', {
      method: 'POST',
      body: JSON.stringify({ containerId: container.id })
    })
    pollDockerJob()
  } catch (e) { error.value = e.message }
}

async function pollDockerJob() {
  clearTimeout(dockerTimer)
  if (!dockerJob.value?.id) return
  try {
    dockerJob.value = await api(`/api/admin/docker/jobs/${dockerJob.value.id}`)
    if (dockerJobRunning.value) {
      dockerTimer = setTimeout(pollDockerJob, 700)
    } else {
      await loadDockerOverview(false)
      if (dockerJob.value.status === 'failed') error.value = dockerJob.value.error || 'Docker 任务失败'
    }
  } catch (e) { error.value = e.message }
}

async function cancelDockerJob() {
  if (!dockerJob.value?.id || !dockerJob.value.cancellable) return
  try {
    await api(`/api/admin/docker/jobs/${dockerJob.value.id}`, { method: 'DELETE' })
    pollDockerJob()
  } catch (e) { error.value = e.message }
}

async function runDockerAction(container, action) {
  const key = `${container.id}:${action}`
  dockerAction.value = key
  error.value = ''
  try {
    await api('/api/admin/docker/containers/action', {
      method: 'POST',
      body: JSON.stringify({ containerId: container.id, action })
    })
    await loadDockerOverview(false)
  } catch (e) { error.value = e.message }
  finally { dockerAction.value = '' }
}

async function showDockerCompose(container) {
  composeDialog.value = true
  composeLoading.value = true
  composeView.value = null
  try {
    composeView.value = await api(`/api/admin/docker/containers/${encodeURIComponent(container.id)}/compose`)
  } catch (e) {
    error.value = e.message
    composeDialog.value = false
  } finally {
    composeLoading.value = false
  }
}

async function copyDockerCompose() {
  const content = composeView.value?.content
  if (!content) return
  try {
    await navigator.clipboard.writeText(content)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = content
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  message.value = 'docker-compose.yaml 已复制'
}

async function cleanupDockerImages() {
  cleanupLoading.value = true
  error.value = ''
  try {
    const result = await api('/api/admin/docker/images/unused', { method: 'DELETE' })
    cleanupDialog.value = false
    message.value = result.deletedImages
      ? `已删除 ${result.deletedImages} 个未使用镜像，释放 ${formatBytes(result.spaceReclaimed)}`
      : '没有可删除的未使用镜像'
    dockerJob.value = null
    await loadDockerOverview(false)
  } catch (e) { error.value = e.message }
  finally { cleanupLoading.value = false }
}

async function openCleanupDialog() {
  cleanupDialog.value = true
  cleanupPreviewLoading.value = true
  cleanupPreview.value = null
  error.value = ''
  try {
    cleanupPreview.value = await api('/api/admin/docker/images/unused')
  } catch (e) {
    error.value = e.message
    cleanupDialog.value = false
  } finally {
    cleanupPreviewLoading.value = false
  }
}

function cleanupImageName(image) {
  return image.references?.[0] || '未命名镜像'
}

function cleanupImageDetail(image) {
  const id = String(image.id || '').replace(/^sha256:/, '').slice(0, 12) || '未知 ID'
  const more = image.references?.length > 1 ? ` · 另有 ${image.references.length - 1} 个标签` : ''
  return `${id} · ${formatBytes(image.size)}${more}`
}

function dockerStateLabel(state) {
  return ({ running: '运行中', exited: '已停止', created: '已创建', paused: '已暂停', restarting: '重启中', dead: '异常退出' })[state] || state || '未知'
}

function containerIsActive(container) {
  return ['running', 'paused', 'restarting'].includes(container.state)
}

function containerIsUpdating(container) {
  if (!dockerJobRunning.value || dockerJob.value?.type !== 'update' || !dockerJob.value.containerId) return false
  const target = dockerJob.value.containerId
  return container.id === target || container.id.startsWith(target) || target.startsWith(container.id)
}

function dockerPhaseLabel(phase) {
  return ({
    queued: '等待开始更新',
    loading: '正在读取容器配置',
    pulling: '正在拉取新镜像',
    recreating: '正在重建容器',
    cancelling: '正在中断更新'
  })[phase] || '正在更新容器'
}

function formatDuration(seconds) {
  const value = Math.max(0, Number(seconds) || 0)
  const days = Math.floor(value / 86400)
  const hours = Math.floor(value % 86400 / 3600)
  const minutes = Math.floor(value % 3600 / 60)
  if (days) return `${days}天${hours ? ` ${hours}小时` : ''}`
  if (hours) return `${hours}小时${minutes ? ` ${minutes}分钟` : ''}`
  if (minutes) return `${minutes}分钟`
  return `${Math.floor(value)}秒`
}

function containerUptime(container) {
  if (container.state !== 'running') return 0
  return container.uptimeSeconds + Math.max(0, Math.floor((dockerClock.value - dockerOverviewLoadedAt.value) / 1000))
}

function formatBytes(bytes) {
  const value = Math.max(0, Number(bytes) || 0)
  if (value < 1024) return `${value} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let size = value
  let unit = -1
  do { size /= 1024; unit++ } while (size >= 1024 && unit < units.length - 1)
  return `${size.toFixed(size >= 10 ? 1 : 2)} ${units[unit]}`
}

function dockerJobTitle(job) {
  if (!job) return ''
  if (job.type === 'check') return job.status === 'completed' ? '镜像检查完成' : job.status === 'cancelled' ? '镜像检查已中断' : '正在检查镜像更新'
  return job.status === 'completed' ? `${job.containerName || '容器'} 更新完成` : job.status === 'cancelled' ? '容器更新已中断' : `正在更新 ${job.containerName || '容器'}`
}

function formatLogTime(timestamp) {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}

async function changePassword() {
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    error.value = '两次输入的新密码不一致'
    return
  }
  changingPassword.value = true
  try {
    await api('/api/admin/password', {
      method: 'POST',
      body: JSON.stringify({ currentPassword: passwordForm.value.currentPassword, newPassword: passwordForm.value.newPassword })
    })
    setToken('')
    appState.auth.authenticated = false
    router.replace('/login')
  } catch (e) { error.value = e.message }
  finally { changingPassword.value = false }
}

async function changeUsername() {
  changingUsername.value = true
  error.value = ''
  const username = usernameForm.value.newUsername.trim()
  try {
    await api('/api/admin/username', {
      method: 'POST',
      body: JSON.stringify({ currentPassword: usernameForm.value.currentPassword, newUsername: username })
    })
    try {
      const key = 'open-panel-remembered-login'
      const remembered = JSON.parse(localStorage.getItem(key) || '{}')
      if (remembered?.password) localStorage.setItem(key, JSON.stringify({ ...remembered, username }))
    } catch {
      localStorage.removeItem('open-panel-remembered-login')
    }
    setToken('')
    appState.auth.authenticated = false
    router.replace('/login')
  } catch (e) { error.value = e.message }
  finally { changingUsername.value = false }
}

async function installUpdate() {
  updateLoading.value = true
  try {
    const result = await api('/api/admin/update/install', { method: 'POST' })
    if (!result.restarting) {
      message.value = '更新任务已提交'
      return
    }
    message.value = '更新已安装，正在重启服务'
    for (let attempt = 0; attempt < 24; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 5000))
      try {
        const response = await fetch(appUrl('/api/public/health'), { cache: 'no-store' })
        if (response.ok) {
          location.reload()
          return
        }
      } catch {
        // Service is restarting.
      }
    }
    throw new Error('服务重启超时，请稍后刷新页面')
  } catch (e) { error.value = e.message }
  finally { updateLoading.value = false }
}

function logout() {
  api('/api/auth/logout', { method: 'POST' }).catch(() => {})
  setToken('')
  appState.auth.authenticated = false
  router.replace('/')
}
</script>

<template>
  <main class="settings-page">
    <header class="settings-header">
      <div class="header-inner content-width">
        <v-btn icon="mdi-arrow-left" variant="text" aria-label="返回导航页" @click="router.push('/')"><v-icon icon="mdi-arrow-left" /></v-btn>
        <img :src="appUrl('icons/icon.svg')" alt="" />
        <div><strong>Open Panel</strong><span>设置</span></div>
        <v-spacer />
        <v-btn icon="mdi-logout" variant="text" aria-label="退出登录" @click="logout"><v-icon icon="mdi-logout" /><v-tooltip activator="parent">退出登录</v-tooltip></v-btn>
        <v-btn color="secondary" prepend-icon="mdi-content-save-outline" :loading="saving" @click="save">保存</v-btn>
      </div>
    </header>

    <v-progress-linear v-if="loading" indeterminate color="secondary" />
    <div v-if="config" class="settings-layout content-width">
      <v-tabs v-model="tab" color="primary" class="settings-tabs" show-arrows>
        <v-tab value="appearance" prepend-icon="mdi-palette-outline">外观</v-tab>
        <v-tab value="content" prepend-icon="mdi-view-grid-outline">内容</v-tab>
        <v-tab v-if="dockerOverview.available" value="docker" prepend-icon="mdi-docker">Docker</v-tab>
        <v-tab value="security" prepend-icon="mdi-shield-lock-outline">安全</v-tab>
        <v-tab value="data" prepend-icon="mdi-database-outline">数据</v-tab>
        <v-tab value="about" prepend-icon="mdi-information-outline">关于</v-tab>
      </v-tabs>

      <v-alert v-if="message" type="success" variant="tonal" closable @click:close="message = ''">{{ message }}</v-alert>
      <v-alert v-if="error" type="error" variant="tonal" closable @click:close="error = ''">{{ error }}</v-alert>

      <v-window v-model="tab" class="settings-window">
        <v-window-item value="appearance">
          <section class="settings-section">
            <div class="section-heading"><div><h2>站点与页面</h2><p>名称、主题与首页呈现方式</p></div></div>
            <div class="form-grid">
              <v-text-field v-model="config.site.title" label="网站标题" />
              <v-text-field v-model="config.site.icon" label="网站图标 URL" />
              <v-select v-model="config.site.theme" label="主题" :items="[{title:'跟随系统',value:'system'},{title:'浅色',value:'light'},{title:'深色',value:'dark'}]" />
              <v-select v-model="config.page.mode" label="页面模式" :items="[{title:'大封面模式',value:'cover'},{title:'列表模式',value:'list'}]" />
              <v-select v-model="config.page.groupLayout" label="卡片组布局" :items="[{title:'纵向分组',value:'sections'},{title:'Tabs 切换',value:'tabs'}]" />
              <div class="theme-color-control">
                <input v-model="config.site.themeColor" class="theme-color-swatch" type="color" aria-label="选择主题色" title="选择主题色" />
                <v-text-field v-model="config.site.themeColor" label="主题色" hide-details @blur="normalizeThemeColorField" />
                <v-btn variant="outlined" prepend-icon="mdi-eyedropper-variant" :loading="extractingThemeColor" @click="pickThemeColorFromWallpaper">从壁纸选取</v-btn>
              </div>
              <div class="theme-color-presets" aria-label="推荐主题色">
                <span>推荐颜色</span>
                <button
                  v-for="color in predefinedThemeColors"
                  :key="color"
                  type="button"
                  class="theme-color-preset"
                  :class="{ selected: normalizeThemeColor(config.site.themeColor) === color }"
                  :style="{ '--preset-color': color }"
                  :aria-label="`使用主题色 ${color}`"
                  :title="color"
                  @click="config.site.themeColor = color"
                >
                  <v-icon v-if="normalizeThemeColor(config.site.themeColor) === color" icon="mdi-check" size="15" />
                </button>
              </div>
              <div class="site-page-switches">
                <v-switch v-if="config.page.groupLayout === 'tabs'" v-model="config.page.tabsShowAll" label="显示“全部”标签" color="primary" hide-details />
                <v-switch v-model="config.site.cornerControlsHoverOnly" label="右上角按钮仅悬停时显示" color="primary" hide-details />
              </div>
            </div>
          </section>

          <section class="settings-section">
            <div class="section-heading"><div><h2>卡片与搜索框</h2><p>统一调整首页导航控件的外观</p></div><v-switch v-model="config.page.searchVisible" label="显示搜索框" color="primary" hide-details /></div>
            <div class="form-grid">
              <v-slider v-model="config.page.surfaceTransparency" label="透明度" :min="0" :max="0.9" :step="0.05" thumb-label />
              <v-slider v-model="config.page.surfaceRadius" label="圆角（px）" :min="0" :max="32" :step="1" thumb-label />
            </div>
          </section>

          <section class="settings-section">
            <div class="section-heading"><div><h2>Banner</h2><p>首页时间、标题和一句话</p></div><v-switch v-model="config.page.banner.visible" label="显示" color="primary" hide-details /></div>
            <div class="form-grid">
              <v-switch v-model="config.page.banner.showTime" label="显示时间" color="primary" hide-details />
              <v-switch v-model="config.page.banner.showSeconds" label="精确到秒" color="primary" hide-details />
              <v-switch v-model="config.page.banner.showDate" label="显示日期" color="primary" hide-details />
              <v-switch v-model="config.page.banner.showWeekday" label="显示周几" color="primary" hide-details />
              <v-switch v-model="config.page.banner.showTitle" label="显示自定义标题" color="primary" hide-details />
              <v-switch v-model="config.page.banner.showQuote" label="显示自定义一句话" color="primary" hide-details />
              <v-text-field v-model="config.page.banner.title" label="Banner 标题" />
              <v-text-field v-model="config.page.banner.quote" label="自定义一句话" />
            </div>
          </section>

          <section class="settings-section">
            <div class="section-heading"><div><h2>壁纸</h2><p>上传图片或每行填写一个 URL，多张时自动轮换</p></div></div>
            <div class="background-upload">
              <span
                class="background-preview"
                :style="{
                  backgroundImage: `linear-gradient(rgba(8, 12, 11, ${config.site.backgroundOverlay}), rgba(8, 12, 11, ${config.site.backgroundOverlay})), url(${previewWallpaper})`
                }"
              />
              <v-file-input
                label="上传背景图片"
                accept=".avif,.png,.webp,.jpg,.jpeg,.gif"
                prepend-icon="mdi-image-plus-outline"
                :loading="uploadingBackground"
                hint="AVIF、PNG、WebP、JPG、JPEG 或 GIF，最大 20 MB"
                persistent-hint
                @update:model-value="uploadBackground"
              />
            </div>
            <v-textarea v-model="wallpaperText" label="壁纸列表" rows="4" />
            <div class="form-grid">
              <v-slider v-model="config.site.backgroundOverlay" label="壁纸遮罩" :min="0" :max="0.9" :step="0.05" thumb-label />
              <v-number-input v-model="config.page.cover.intervalSeconds" label="轮换间隔（秒）" :min="5" :max="3600" />
              <v-select v-model="coverGroupId" label="封面中显示的分组" :items="sortedGroups" item-title="title" item-value="id" clearable />
            </div>
          </section>

          <section class="settings-section">
            <div class="section-heading"><div><h2>页脚</h2><p>最多显示四行文字</p></div><v-switch v-model="config.page.footer.visible" label="显示" color="primary" hide-details /></div>
            <v-textarea v-model="footerText" label="页脚文字" rows="4" counter="4" />
          </section>
        </v-window-item>

        <v-window-item value="content">
          <section class="settings-section">
            <div class="section-heading"><div><h2>发现 Web 服务</h2><p>扫描指定 IP 或域名的 HTTP/HTTPS 服务</p></div></div>
            <div class="scan-controls">
              <v-text-field v-model="scanForm.target" label="IP 或域名" prepend-inner-icon="mdi-server-network" :disabled="scanRunning" />
              <div class="range-control">
                <v-range-slider v-model="scanForm.range" :min="80" :max="65535" :step="1" color="primary" thumb-label :disabled="scanRunning" />
                <div class="range-inputs">
                  <v-number-input v-model="scanForm.range[0]" label="起始端口" :min="80" :max="scanForm.range[1]" :disabled="scanRunning" />
                  <v-number-input v-model="scanForm.range[1]" label="结束端口" :min="scanForm.range[0]" :max="65535" :disabled="scanRunning" />
                </div>
              </div>
              <v-btn v-if="!scanRunning" color="secondary" prepend-icon="mdi-radar" size="large" @click="startScan">开始扫描</v-btn>
              <v-btn v-else variant="outlined" color="error" prepend-icon="mdi-stop" size="large" @click="cancelScan">停止</v-btn>
            </div>
            <div v-if="scanJob" class="scan-progress">
              <div><strong>{{ scanJob.status === 'completed' ? '扫描完成' : scanJob.status === 'cancelled' ? '扫描已停止' : '正在扫描' }}</strong><span>{{ scanJob.scanned }} / {{ scanJob.total }} 端口 · 已发现 {{ scanJob.results.length }} 个服务</span></div>
              <v-progress-linear :model-value="scanProgress" color="secondary" height="7" rounded />
            </div>
            <div v-if="scanJob?.results?.length" class="scan-results">
              <label v-for="service in scanJob.results" :key="service.id" class="scan-result">
                <v-checkbox-btn v-model="selectedServices" :value="service.id" color="primary" />
                <span class="scan-icon">
                  <img v-if="service.iconUrl" :src="appUrl(service.iconUrl)" alt="" @error="service.iconUrl = ''" />
                  <v-icon v-else icon="mdi-web" />
                </span>
                <span class="scan-copy"><strong>{{ service.title }}</strong><small>{{ service.url }}</small><small>{{ service.description }}</small></span>
                <v-chip size="small" variant="tonal">{{ service.protocol.toUpperCase() }} {{ service.statusCode }}</v-chip>
              </label>
              <div class="scan-add-row">
                <v-select v-model="scanGroupId" label="添加到分组" :items="sortedGroups" item-title="title" item-value="id" hide-details />
                <v-btn color="secondary" prepend-icon="mdi-plus" :disabled="!selectedServices.length" @click="addScannedServices">添加所选（{{ selectedServices.length }}）</v-btn>
              </div>
            </div>
          </section>

          <section class="settings-section">
            <div class="section-heading"><div><h2>卡片分组</h2><p>编辑分组的名称、图标、显示方式和首页顺序</p></div><v-btn prepend-icon="mdi-plus" variant="outlined" @click="addGroup">添加分组</v-btn></div>
            <div class="group-editor-list">
              <div v-for="(group, groupIndex) in sortedGroups" :key="group.id" class="group-editor">
                <div class="group-editor-head">
                  <div class="group-fields">
                    <v-text-field v-model="group.title" label="分组名称" hide-details />
                    <MdiIconPicker v-model="group.icon" hide-details />
                    <v-select v-model="group.displayMode" label="卡片显示" :items="[{title:'标题、图标、备注',value:'detail'},{title:'只显示图标',value:'icon'}]" hide-details />
                  </div>
                  <div class="group-actions">
                    <v-switch v-model="group.enabled" label="显示" color="primary" hide-details />
                    <v-btn icon="mdi-arrow-up" variant="text" :disabled="groupIndex === 0" aria-label="分组上移" title="分组上移" @click="moveGroup(group, -1)" />
                    <v-btn icon="mdi-arrow-down" variant="text" :disabled="groupIndex === sortedGroups.length - 1" aria-label="分组下移" title="分组下移" @click="moveGroup(group, 1)" />
                    <v-btn icon="mdi-delete-outline" variant="text" color="error" aria-label="删除分组" title="删除分组" @click="removeGroup(group)" />
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section class="settings-section">
            <div class="section-heading"><div><h2>搜索引擎</h2><p>URL 模板中使用 {query} 作为关键词占位符</p></div><v-btn prepend-icon="mdi-plus" variant="outlined" @click="addEngine">添加引擎</v-btn></div>
            <div class="editable-list">
              <div v-for="engine in config.searchEngines" :key="engine.id" class="editable-row engine-row">
                <v-text-field v-model="engine.name" label="名称" hide-details />
                <MdiIconPicker v-model="engine.icon" hide-details />
                <v-text-field v-model="engine.urlTemplate" label="搜索 URL 模板" hide-details />
                <v-switch v-model="engine.enabled" label="启用" color="primary" hide-details />
                <v-btn icon="mdi-delete-outline" variant="text" color="error" aria-label="删除引擎" @click="removeEngine(engine)" />
              </div>
            </div>
          </section>
        </v-window-item>

        <v-window-item v-if="dockerOverview.available" value="docker">
          <section class="settings-section">
            <div class="section-heading">
              <div>
                <h2>容器镜像</h2>
                <p>项目启动后及每小时自动检测镜像；容器只会在手动点击更新后重建</p>
              </div>
              <div class="action-row">
                <v-btn
                  variant="outlined"
                  prepend-icon="mdi-cloud-sync-outline"
                  :loading="dockerJobRunning && dockerJob?.type === 'check'"
                  :disabled="dockerJobRunning || !!dockerAction"
                  @click="startDockerCheck"
                >重新检测</v-btn>
                <v-btn
                  variant="outlined"
                  color="error"
                  prepend-icon="mdi-delete-sweep-outline"
                  :disabled="dockerJobRunning || !!dockerAction"
                  @click="openCleanupDialog"
                >清理镜像</v-btn>
              </div>
            </div>

            <div class="docker-summary">
              <span><strong>{{ dockerOverview.containers.length }}</strong><small>容器</small></span>
              <span><strong>{{ dockerUpdates }}</strong><small>可更新</small></span>
              <span><strong>{{ dockerOverview.containers.filter(item => item.state === 'running').length }}</strong><small>运行中</small></span>
            </div>

            <div v-if="dockerOverview.containers.length" class="docker-list">
              <article
                v-for="container in sortedDockerContainers"
                :key="container.id"
                class="docker-row"
                :class="{ 'docker-row--updating': containerIsUpdating(container) }"
              >
                <div class="docker-card-head">
                  <div class="docker-copy">
                    <strong>{{ container.name }}</strong>
                    <small class="docker-image" :title="container.image">{{ container.image || '未知镜像' }}</small>
                  </div>
                  <div class="docker-card-badges">
                    <v-chip v-if="container.self" size="x-small" variant="tonal">当前实例</v-chip>
                    <v-chip v-else-if="container.checkError" size="x-small" color="error" variant="tonal">检测失败</v-chip>
                    <v-chip v-else-if="!container.updatable" size="x-small" variant="tonal">不可更新</v-chip>
                    <v-chip v-else-if="container.updateAvailable" size="x-small" color="warning" variant="tonal">发现更新</v-chip>
                  </div>
                </div>

                <div class="docker-card-meta" :class="{ 'docker-error': container.checkError }">
                  <v-icon :icon="containerIsUpdating(container) ? 'mdi-sync' : container.state === 'running' ? 'mdi-clock-outline' : 'mdi-information-outline'" size="15" />
                  <span v-if="containerIsUpdating(container)">{{ dockerPhaseLabel(dockerJob.phase) }}</span>
                  <span v-else-if="container.state === 'running'">已运行 {{ formatDuration(containerUptime(container)) }}</span>
                  <span v-else>{{ dockerStateLabel(container.state) }} · {{ container.status }}</span>
                  <span v-if="container.checkError || container.reason" class="docker-note">{{ container.checkError || container.reason }}</span>
                </div>

                <div v-if="containerIsUpdating(container)" class="docker-card-progress">
                  <div><span>{{ dockerJob.status === 'cancelling' ? '正在停止' : dockerJob.cancellable ? '更新中' : '正在安全替换' }}</span><strong>{{ dockerJob.progress }}%</strong></div>
                  <v-progress-linear :model-value="dockerJob.progress" color="primary" height="5" rounded />
                  <v-btn
                    icon="mdi-stop-circle-outline"
                    size="small"
                    variant="text"
                    color="error"
                    aria-label="停止更新"
                    :disabled="!dockerJob.cancellable || dockerJob.status === 'cancelling'"
                    @click="cancelDockerJob"
                  ><v-icon icon="mdi-stop-circle-outline" /><v-tooltip activator="parent">停止更新</v-tooltip></v-btn>
                </div>
                <div v-else class="docker-controls">
                  <v-btn icon="mdi-file-code-outline" size="small" variant="text" aria-label="查看 Docker Compose" @click="showDockerCompose(container)">
                    <v-icon icon="mdi-file-code-outline" /><v-tooltip activator="parent">查看 docker-compose.yaml</v-tooltip>
                  </v-btn>
                  <span class="docker-control-spacer" />
                  <template v-if="!container.self">
                    <v-btn
                      v-if="containerIsActive(container)"
                      icon="mdi-stop"
                      size="small"
                      variant="text"
                      color="error"
                      aria-label="停止容器"
                      :loading="dockerAction === `${container.id}:stop`"
                      :disabled="dockerJobRunning || !!dockerAction"
                      @click="runDockerAction(container, 'stop')"
                    ><v-icon icon="mdi-stop" /><v-tooltip activator="parent">停止容器</v-tooltip></v-btn>
                    <v-btn
                      v-else
                      icon="mdi-play"
                      size="small"
                      variant="text"
                      color="success"
                      aria-label="启动容器"
                      :loading="dockerAction === `${container.id}:start`"
                      :disabled="dockerJobRunning || !!dockerAction"
                      @click="runDockerAction(container, 'start')"
                    ><v-icon icon="mdi-play" /><v-tooltip activator="parent">启动容器</v-tooltip></v-btn>
                    <v-btn
                      icon="mdi-restart"
                      size="small"
                      variant="text"
                      color="primary"
                      aria-label="重启容器"
                      :loading="dockerAction === `${container.id}:restart`"
                      :disabled="!containerIsActive(container) || dockerJobRunning || !!dockerAction"
                      @click="runDockerAction(container, 'restart')"
                    ><v-icon icon="mdi-restart" /><v-tooltip activator="parent">重启容器</v-tooltip></v-btn>
                  </template>
                  <v-btn
                    v-if="container.updatable"
                    icon="mdi-cloud-download-outline"
                    size="small"
                    variant="text"
                    color="secondary"
                    aria-label="更新容器"
                    :disabled="dockerJobRunning || !!dockerAction"
                    @click="startDockerUpdate(container)"
                  ><v-icon icon="mdi-cloud-download-outline" /><v-tooltip activator="parent">更新容器</v-tooltip></v-btn>
                </div>
              </article>
            </div>
            <div v-else class="docker-empty">
              <v-icon icon="mdi-package-variant-closed" size="32" />
              <span>当前没有 Docker 容器</span>
            </div>
          </section>

          <section v-if="dockerJob" class="settings-section docker-task-section">
            <div class="docker-task-head">
              <div>
                <strong>{{ dockerJobTitle(dockerJob) }}</strong>
                <small v-if="dockerJob.type === 'check' && dockerJob.totalItems">
                  {{ dockerJob.completedItems }} / {{ dockerJob.totalItems }} 个镜像
                </small>
                <small v-else-if="dockerJob.image">{{ dockerJob.image }}</small>
              </div>
              <span>{{ dockerJob.progress }}%</span>
              <v-btn
                v-if="dockerJobRunning"
                color="error"
                variant="text"
                prepend-icon="mdi-stop-circle-outline"
                :disabled="!dockerJob.cancellable || dockerJob.status === 'cancelling'"
                @click="cancelDockerJob"
              >{{ dockerJob.status === 'cancelling' ? '正在停止' : dockerJob.cancellable ? '中断' : '正在安全替换' }}</v-btn>
            </div>
            <v-progress-linear
              :model-value="dockerJob.progress"
              :indeterminate="dockerJob.status === 'queued'"
              :color="dockerJob.status === 'failed' ? 'error' : dockerJob.status === 'completed' ? 'success' : 'secondary'"
              height="7"
              rounded
            />
            <div class="docker-log" role="log" aria-live="polite">
              <div v-for="(entry, index) in dockerJob.logs" :key="`${entry.timestamp}-${index}`" :class="`log-${entry.level}`">
                <time>{{ formatLogTime(entry.timestamp) }}</time>
                <v-icon :icon="entry.level === 'error' ? 'mdi-alert-circle-outline' : entry.level === 'success' ? 'mdi-check-circle-outline' : entry.level === 'warn' ? 'mdi-alert-outline' : 'mdi-chevron-right'" size="15" />
                <span>{{ entry.message }}</span>
              </div>
            </div>
          </section>
        </v-window-item>

        <v-window-item value="security">
          <section class="settings-section">
            <div class="section-heading"><div><h2>访问权限</h2><p>匿名用户只有导航页只读权限，不能进入设置</p></div></div>
            <div class="switch-grid">
              <v-switch v-model="config.security.anonymousAccess" label="允许免登录访问" color="primary" />
              <v-switch v-model="config.security.forbidMultipleLogin" label="禁止多端登录" color="primary" />
              <v-switch v-model="config.security.forbidPublicAccess" label="禁止公网访问" color="primary" />
              <v-switch v-model="config.security.invalidateOnIpChange" label="IP 改变后登录失效" color="primary" />
              <v-switch v-model="config.security.limitLoginAttempts" label="限制登录尝试次数" color="primary" />
              <v-switch v-model="config.security.allowCors" label="允许公开接口跨域" color="primary" />
            </div>
            <div class="form-grid">
              <v-number-input v-model="config.security.tokenValidHours" label="登录有效时间（小时）" hint="填写 0 表示永久有效" persistent-hint :min="0" :max="8760" />
              <v-number-input v-model="config.security.maxLoginAttempts" label="最大尝试次数" :min="1" :max="100" :disabled="!config.security.limitLoginAttempts" />
              <v-number-input v-model="config.security.loginLockMinutes" label="锁定时间（分钟）" hint="失败次数达到上限后，该 IP 暂停登录的时长" persistent-hint :min="1" :max="1440" :disabled="!config.security.limitLoginAttempts" />
            </div>
          </section>
          <section class="settings-section">
            <div class="section-heading"><div><h2>网络信任</h2><p>每行填写一个 IP 或 CIDR；白名单只限制登录和管理接口</p></div></div>
            <div class="form-grid">
              <v-textarea v-model="allowlistText" label="IP 白名单" rows="5" />
              <v-textarea v-model="proxiesText" label="信任的反代 IP" rows="5" />
              <v-textarea v-model="corsText" label="允许跨域的来源" rows="5" :disabled="!config.security.allowCors" placeholder="https://example.com" />
            </div>
          </section>
          <section class="settings-section">
            <div class="section-heading"><div><h2>管理员用户名</h2><p>修改后所有现有登录令牌立即失效</p></div></div>
            <form class="username-form" @submit.prevent="changeUsername">
              <v-text-field v-model="usernameForm.newUsername" label="新用户名" autocomplete="username" />
              <v-text-field
                v-model="usernameForm.currentPassword"
                label="当前密码"
                :type="visibleSecrets.usernamePassword ? 'text' : 'password'"
                autocomplete="current-password"
                :append-inner-icon="visibleSecrets.usernamePassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
                @click:append-inner="visibleSecrets.usernamePassword = !visibleSecrets.usernamePassword"
              />
              <v-btn type="submit" variant="outlined" prepend-icon="mdi-account-edit-outline" :loading="changingUsername">修改用户名</v-btn>
            </form>
          </section>
          <section class="settings-section">
            <div class="section-heading"><div><h2>管理员密码</h2><p>修改后所有现有登录令牌立即失效</p></div></div>
            <form class="password-form" @submit.prevent="changePassword">
              <v-text-field
                v-model="passwordForm.currentPassword"
                label="当前密码"
                :type="visibleSecrets.currentPassword ? 'text' : 'password'"
                autocomplete="current-password"
                :append-inner-icon="visibleSecrets.currentPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
                @click:append-inner="visibleSecrets.currentPassword = !visibleSecrets.currentPassword"
              />
              <v-text-field
                v-model="passwordForm.newPassword"
                label="新密码"
                :type="visibleSecrets.newPassword ? 'text' : 'password'"
                autocomplete="new-password"
                :append-inner-icon="visibleSecrets.newPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
                @click:append-inner="visibleSecrets.newPassword = !visibleSecrets.newPassword"
              />
              <v-text-field
                v-model="passwordForm.confirmPassword"
                label="确认新密码"
                :type="visibleSecrets.confirmPassword ? 'text' : 'password'"
                autocomplete="new-password"
                :append-inner-icon="visibleSecrets.confirmPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
                @click:append-inner="visibleSecrets.confirmPassword = !visibleSecrets.confirmPassword"
              />
              <v-btn type="submit" variant="outlined" prepend-icon="mdi-lock-reset" :loading="changingPassword">修改密码</v-btn>
            </form>
          </section>
        </v-window-item>

        <v-window-item value="data">
          <section class="settings-section">
            <div class="section-heading"><div><h2>配置备份</h2><p>ZIP 备份包含完整配置、凭据和已上传图片，请妥善保管</p></div></div>
            <div class="action-row">
              <v-btn prepend-icon="mdi-download-outline" variant="outlined" @click="exportConfig">导出配置</v-btn>
              <v-btn prepend-icon="mdi-upload-outline" variant="outlined" @click="importInput.click()">导入配置</v-btn>
              <input ref="importInput" type="file" accept="application/zip,application/x-zip-compressed,.zip" hidden @change="importConfig" />
            </div>
          </section>
        </v-window-item>

        <v-window-item value="about">
          <section class="settings-section">
            <div class="section-heading"><div><h2>项目更新</h2><p>从 GitHub Releases 检查稳定版本</p></div></div>
            <v-text-field
              v-model="config.update.githubToken"
              label="GitHub Token"
              :type="visibleSecrets.githubToken ? 'text' : 'password'"
              autocomplete="off"
              prepend-inner-icon="mdi-github"
              :append-inner-icon="visibleSecrets.githubToken ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
              hint="可选，仅用于 GitHub API 更新检查，避免匿名请求受 IP 频率限制"
              persistent-hint
              class="update-token"
              @click:append-inner="visibleSecrets.githubToken = !visibleSecrets.githubToken"
            />
            <div class="update-row">
              <div><strong>Open Panel <span v-if="updateInfo?.currentVersion" class="version-badge">v{{ updateInfo.currentVersion }}</span></strong><small v-if="updateInfo?.latestVersion">当前 {{ updateInfo.currentVersion }} · 最新 {{ updateInfo.latestVersion }}</small><small v-else>尚未检查更新</small></div>
              <v-spacer />
              <v-btn variant="outlined" prepend-icon="mdi-refresh" :loading="updateLoading" @click="checkUpdate">检查更新</v-btn>
              <v-btn v-if="updateInfo?.available" color="secondary" prepend-icon="mdi-download" :loading="updateLoading" @click="installUpdate">安装更新</v-btn>
            </div>
          </section>
          <section class="settings-section">
            <div class="section-heading"><div><h2>开源项目</h2><p>GPL-2.0 · GitHub</p></div></div>
            <div class="action-row">
              <v-btn href="https://github.com/wushuo894/open-panel" target="_blank" prepend-icon="mdi-github" variant="outlined">wushuo894/open-panel</v-btn>
              <v-btn
                href="https://ifdian.net/a/wushuo894"
                target="_blank"
                rel="noopener noreferrer"
                prepend-icon="mdi-lightning-bolt"
                variant="flat"
                class="afdian-button"
              >在爱发电支持我</v-btn>
            </div>
          </section>
        </v-window-item>
      </v-window>
    </div>

    <v-dialog v-model="groupDialog" max-width="480">
      <v-card v-if="groupDraft">
        <v-card-title>添加分组</v-card-title>
        <v-card-text class="dialog-form">
          <v-text-field v-model="groupDraft.title" label="分组名称" autofocus @keydown.enter="commitGroup" />
          <MdiIconPicker v-model="groupDraft.icon" />
          <v-select
            v-model="groupDraft.displayMode"
            label="卡片显示方式"
            :items="[{title:'标题、图标、备注',value:'detail'},{title:'只显示图标',value:'icon'}]"
          />
          <v-switch v-model="groupDraft.enabled" label="在首页显示" color="primary" hide-details />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="groupDialog = false">取消</v-btn>
          <v-btn color="secondary" :disabled="!groupDraft.title?.trim()" @click="commitGroup">添加</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="composeDialog" max-width="900">
      <v-card>
        <v-card-title class="compose-title">
          <span>{{ composeView?.containerName || 'Docker Compose' }}</span>
          <v-spacer />
          <v-btn icon="mdi-close" variant="text" aria-label="关闭" @click="composeDialog = false"><v-icon icon="mdi-close" /></v-btn>
        </v-card-title>
        <v-card-text>
          <v-progress-linear v-if="composeLoading" indeterminate color="secondary" />
          <pre v-else-if="composeView" class="compose-source"><code>{{ composeView.content }}</code></pre>
        </v-card-text>
        <v-card-actions v-if="composeView">
          <small class="compose-filename">{{ composeView.filename }}</small>
          <v-spacer />
          <v-btn prepend-icon="mdi-content-copy" variant="outlined" @click="copyDockerCompose">复制</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="cleanupDialog" max-width="680" scrollable persistent>
      <v-card>
        <v-card-title>清理未使用镜像</v-card-title>
        <v-card-text class="cleanup-dialog-content">
          <v-progress-linear v-if="cleanupPreviewLoading" indeterminate color="secondary" />
          <template v-else-if="cleanupPreview">
            <div v-if="cleanupPreview.images.length" class="cleanup-summary">
              <strong>将清理 {{ cleanupPreview.images.length }} 个镜像</strong>
              <small>镜像大小合计 {{ formatBytes(cleanupPreview.totalSize) }}</small>
            </div>
            <div v-if="cleanupPreview.images.length" class="cleanup-image-list">
              <div v-for="image in cleanupPreview.images" :key="image.id" class="cleanup-image-row">
                <v-icon icon="mdi-package-variant" size="22" />
                <span>
                  <strong :title="image.references?.join('\n')">{{ cleanupImageName(image) }}</strong>
                  <small>{{ cleanupImageDetail(image) }}</small>
                </span>
              </div>
            </div>
            <div v-else class="cleanup-empty">
              <v-icon icon="mdi-check-circle-outline" size="30" />
              <span>没有可清理的未使用镜像</span>
            </div>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn :disabled="cleanupLoading" @click="cleanupDialog = false">取消</v-btn>
          <v-btn
            color="error"
            prepend-icon="mdi-delete-sweep-outline"
            :loading="cleanupLoading"
            :disabled="cleanupPreviewLoading || !cleanupPreview?.images?.length"
            @click="cleanupDockerImages"
          >确认清理</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

  </main>
</template>

<style scoped>
.settings-page { min-height: 100svh; background: rgb(var(--v-theme-background)); }
.settings-header { position: sticky; z-index: 20; top: 0; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); background: rgba(var(--v-theme-surface),.92); backdrop-filter: blur(14px); }
.header-inner { display: flex; align-items: center; min-height: 66px; gap: 10px; }
.header-inner > img { width: 34px; height: 34px; border-radius: 8px; }
.header-inner > div { display: grid; line-height: 1.1; }
.header-inner span { margin-top: 4px; color: rgba(var(--v-theme-on-surface),.5); font-size: .74rem; }
.settings-layout { padding-block: 16px 64px; }
.settings-tabs { margin-bottom: 12px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.08); }
.settings-window { margin-inline: -24px; }
.settings-window :deep(.v-window-item) { padding-inline: 24px; }
.settings-section { padding-block: 28px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.section-heading h2 { margin: 0; font-size: 1.08rem; letter-spacing: 0; }
.section-heading p { margin: 5px 0 0; color: rgba(var(--v-theme-on-surface),.56); font-size: .84rem; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 4px 16px; }
.theme-color-control { display: grid; grid-column: 1 / -1; grid-template-columns: 48px minmax(180px, 280px) auto; align-items: center; justify-content: start; gap: 10px; margin-block: 2px 8px; }
.theme-color-swatch { width: 48px; height: 48px; padding: 3px; border: 1px solid rgba(var(--v-theme-on-surface),.16); border-radius: 8px; background: rgb(var(--v-theme-surface)); cursor: pointer; }
.theme-color-swatch::-webkit-color-swatch-wrapper { padding: 0; }
.theme-color-swatch::-webkit-color-swatch { border: 0; border-radius: 5px; }
.theme-color-swatch::-moz-color-swatch { border: 0; border-radius: 5px; }
.theme-color-presets { display: flex; grid-column: 1 / -1; align-items: center; flex-wrap: wrap; gap: 8px; margin: -2px 0 10px; }
.theme-color-presets > span { margin-right: 3px; color: rgba(var(--v-theme-on-surface),.58); font-size: .78rem; }
.theme-color-preset { display: grid; width: 28px; height: 28px; padding: 0; border: 2px solid rgba(var(--v-theme-on-surface),.12); border-radius: 50%; place-items: center; background: var(--preset-color); color: white; cursor: pointer; box-shadow: 0 0 0 1px rgba(var(--v-theme-surface),.9); transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease; }
.theme-color-preset:hover { transform: translateY(-1px); box-shadow: 0 0 0 2px rgba(var(--v-theme-primary),.32); }
.theme-color-preset.selected { border-color: rgb(var(--v-theme-surface)); box-shadow: 0 0 0 2px rgb(var(--v-theme-primary)); }
.theme-color-preset:focus-visible { outline: 2px solid rgb(var(--v-theme-primary)); outline-offset: 2px; }
.site-page-switches { display: grid; grid-column: 1 / -1; grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: center; gap: 8px 24px; }
.switch-grid { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); margin-bottom: 10px; }
.editable-list, .item-list { display: grid; gap: 10px; }
.editable-row, .item-row { display: flex; align-items: center; gap: 10px; min-width: 0; padding: 12px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgb(var(--v-theme-surface)); }
.editable-row > :first-child { flex: 0 1 190px; }
.editable-row > :nth-child(2) { flex: 0 1 190px; }
.editable-row > :nth-child(3) { flex: 1 1 280px; }
.item-icon { display: grid; width: 40px; height: 40px; flex: 0 0 40px; place-items: center; border-radius: 8px; background: rgb(var(--v-theme-secondary)); color: #1a211d; }
.item-icon img { width: 28px; height: 28px; object-fit: contain; }
.item-row > div { display: grid; min-width: 0; }
.item-row small, .update-row small { margin-top: 3px; color: rgba(var(--v-theme-on-surface),.56); }
.action-row, .update-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.afdian-button { background: #946ce6 !important; color: #fff !important; box-shadow: 0 4px 12px rgba(148, 108, 230, .28); }
.afdian-button:hover { background: #835bd6 !important; }
.afdian-button :deep(.v-icon) { color: #ffe36e; }
.update-row > div { display: grid; }
.version-badge { margin-left: 6px; color: rgb(var(--v-theme-primary)); font-size: .82rem; font-weight: 650; }
.update-token { max-width: 620px; margin-bottom: 18px; }
.group-editor-list { display: grid; }
.group-editor { padding-block: 20px; border-top: 1px solid rgba(var(--v-theme-on-surface),.1); }
.group-editor:last-child { border-bottom: 1px solid rgba(var(--v-theme-on-surface),.1); }
.group-editor-head { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: center; gap: 14px; }
.group-fields { display: grid; grid-template-columns: minmax(150px,.8fr) minmax(150px,.8fr) minmax(210px,1fr); gap: 12px; }
.group-actions { display: flex; align-items: center; gap: 2px; }
.dialog-form { display: grid; gap: 4px; padding-top: 20px !important; }
.background-upload { display: grid; grid-template-columns: minmax(180px, 320px) 1fr; align-items: center; gap: 18px; margin-bottom: 18px; }
.background-preview { width: 100%; aspect-ratio: 16 / 9; border-radius: 8px; background-color: rgba(var(--v-theme-on-surface),.08); background-position: center; background-size: cover; }
.scan-controls { display: grid; grid-template-columns: minmax(190px, .7fr) minmax(360px, 1.5fr) auto; align-items: start; gap: 16px; }
.range-control { padding-top: 8px; }
.range-inputs { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: -6px; }
.scan-progress { display: grid; gap: 11px; margin-top: 18px; }
.scan-progress > div { display: flex; justify-content: space-between; gap: 12px; font-size: .82rem; }
.scan-progress span { color: rgba(var(--v-theme-on-surface),.58); }
.scan-results { display: grid; gap: 8px; margin-top: 18px; }
.scan-result { display: flex; align-items: center; min-width: 0; gap: 10px; padding: 11px 12px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgb(var(--v-theme-surface)); cursor: pointer; }
.scan-icon { position: relative; display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; overflow: hidden; border-radius: 7px; background: rgb(var(--v-theme-secondary)); color: #1a211d; }
.scan-icon img { position: absolute; z-index: 1; width: 28px; height: 28px; object-fit: contain; }
.scan-copy { display: grid; min-width: 0; flex: 1; }
.scan-copy strong, .scan-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scan-copy small { color: rgba(var(--v-theme-on-surface),.56); font-size: .75rem; }
.scan-add-row { display: grid; grid-template-columns: minmax(190px, 1fr) auto; align-items: center; gap: 12px; margin-top: 8px; }
.password-form { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)) auto; align-items: start; gap: 10px; }
.username-form { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)) auto; align-items: start; gap: 10px; }
.docker-summary { display: flex; gap: 28px; margin-bottom: 18px; }
.docker-summary > span { display: grid; min-width: 72px; }
.docker-summary strong { font-size: 1.5rem; line-height: 1.1; }
.docker-summary small { margin-top: 5px; color: rgba(var(--v-theme-on-surface),.56); font-size: .75rem; }
.docker-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 330px), 1fr)); gap: 10px; }
.docker-row { display: flex; min-width: 0; min-height: 142px; flex-direction: column; padding: 13px 14px 10px; border: 1px solid rgba(var(--v-theme-on-surface),.11); border-radius: 8px; background: rgb(var(--v-theme-surface)); transition: border-color .18s ease, background-color .18s ease; }
.docker-row:hover { border-color: rgba(var(--v-theme-primary),.3); }
.docker-row--updating { border-color: rgba(var(--v-theme-primary),.52); background: rgba(var(--v-theme-primary),.035); }
.docker-card-head { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: center; min-width: 0; gap: 10px; }
.docker-copy { display: grid; min-width: 0; line-height: 1.25; }
.docker-copy strong, .docker-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.docker-copy strong { font-size: .98rem; font-weight: 700; }
.docker-copy small, .docker-task-head small { margin-top: 3px; color: rgba(var(--v-theme-on-surface),.58); font-size: .76rem; }
.docker-image { font-size: .8rem !important; }
.docker-row--updating .docker-copy strong { color: rgb(var(--v-theme-primary)); }
.docker-card-badges { display: flex; align-self: start; align-items: center; gap: 4px; }
.docker-card-meta { display: flex; min-width: 0; align-items: center; gap: 5px; overflow: hidden; margin-top: 9px; color: rgba(var(--v-theme-on-surface),.58); font-size: .73rem; white-space: nowrap; }
.docker-card-meta > .v-icon { flex: 0 0 auto; }
.docker-card-meta.docker-error { color: rgb(var(--v-theme-error)); }
.docker-note { overflow: hidden; text-overflow: ellipsis; }
.docker-note::before { margin-inline: 2px 7px; color: rgba(var(--v-theme-on-surface),.28); content: '·'; }
.docker-row--updating .docker-card-meta > .v-icon { color: rgb(var(--v-theme-primary)); animation: docker-spin 1.1s linear infinite; }
.docker-card-progress { display: grid; grid-template-columns: minmax(0,1fr) 32px; align-items: center; gap: 4px 8px; margin-top: auto; padding-top: 9px; }
.docker-card-progress > div { display: flex; justify-content: space-between; color: rgba(var(--v-theme-on-surface),.62); font-size: .72rem; font-variant-numeric: tabular-nums; }
.docker-card-progress > .v-progress-linear { grid-column: 1; }
.docker-card-progress > .v-btn { grid-column: 2; grid-row: 1 / 3; }
.docker-controls { display: flex; min-height: 34px; align-items: center; gap: 1px; margin-top: auto; padding-top: 8px; border-top: 1px solid rgba(var(--v-theme-on-surface),.08); }
.docker-control-spacer { flex: 1; }
.docker-controls .v-btn { width: 32px; height: 32px; }
@keyframes docker-spin { to { transform: rotate(360deg); } }
.docker-empty { display: grid; min-height: 180px; place-items: center; align-content: center; gap: 10px; color: rgba(var(--v-theme-on-surface),.52); }
.docker-task-section { display: grid; gap: 14px; }
.docker-task-head { display: grid; grid-template-columns: minmax(0,1fr) auto auto; align-items: center; gap: 14px; }
.docker-task-head > div { display: grid; min-width: 0; }
.docker-task-head > span { color: rgba(var(--v-theme-on-surface),.62); font-variant-numeric: tabular-nums; font-size: .82rem; }
.docker-log { height: 260px; overflow: auto; padding: 10px 12px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgba(var(--v-theme-on-surface),.035); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .75rem; }
.docker-log > div { display: grid; grid-template-columns: 68px 18px minmax(0,1fr); align-items: start; min-height: 25px; gap: 4px; line-height: 1.55; }
.docker-log time { color: rgba(var(--v-theme-on-surface),.44); font-variant-numeric: tabular-nums; }
.docker-log .log-success { color: rgb(var(--v-theme-success)); }
.docker-log .log-warn { color: rgb(var(--v-theme-warning)); }
.docker-log .log-error { color: rgb(var(--v-theme-error)); }
.compose-title { display: flex; align-items: center; min-height: 58px; }
.compose-source { max-height: min(68svh, 720px); overflow: auto; margin: 0; padding: 16px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgba(var(--v-theme-on-surface),.045); color: rgb(var(--v-theme-on-surface)); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .78rem; line-height: 1.6; white-space: pre; }
.compose-filename { padding-left: 8px; color: rgba(var(--v-theme-on-surface),.56); }
.cleanup-dialog-content { display: grid; min-height: 150px; gap: 14px; }
.cleanup-summary { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; }
.cleanup-summary small { color: rgba(var(--v-theme-on-surface),.58); font-size: .76rem; }
.cleanup-image-list { display: grid; max-height: min(52svh, 480px); gap: 2px; overflow-y: auto; }
.cleanup-image-row { display: flex; align-items: center; min-width: 0; gap: 11px; padding: 10px 8px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.08); }
.cleanup-image-row > .v-icon { flex: 0 0 auto; color: rgba(var(--v-theme-on-surface),.6); }
.cleanup-image-row > span { display: grid; min-width: 0; gap: 3px; }
.cleanup-image-row strong, .cleanup-image-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cleanup-image-row strong { font-size: .86rem; }
.cleanup-image-row small { color: rgba(var(--v-theme-on-surface),.54); font-size: .73rem; font-variant-numeric: tabular-nums; }
.cleanup-empty { display: grid; min-height: 120px; place-items: center; align-content: center; gap: 9px; color: rgba(var(--v-theme-on-surface),.54); }
@media (max-width: 820px) {
  .form-grid { grid-template-columns: 1fr; }
  .switch-grid { grid-template-columns: repeat(2, 1fr); }
  .editable-row { align-items: stretch; flex-wrap: wrap; }
  .editable-row > :first-child, .editable-row > :nth-child(2), .editable-row > :nth-child(3) { flex: 1 1 220px; }
  .password-form, .username-form { grid-template-columns: 1fr 1fr; }
  .scan-controls { grid-template-columns: 1fr; }
  .background-upload { grid-template-columns: 1fr; }
  .site-page-switches { grid-template-columns: 1fr; }
  .group-editor-head { grid-template-columns: 1fr; }
  .group-fields { grid-template-columns: 1fr; }
  .group-actions { justify-content: flex-end; }
  .docker-list { grid-template-columns: 1fr; }
}
@media (max-width: 540px) {
  .settings-window { margin-inline: -12px; }
  .settings-window :deep(.v-window-item) { padding-inline: 20px; }
  .switch-grid { grid-template-columns: 1fr; }
  .section-heading { align-items: flex-start; flex-direction: column; }
  .header-inner > div { display: none; }
  .item-row { flex-wrap: wrap; }
  .password-form, .username-form { grid-template-columns: 1fr; }
  .scan-add-row { grid-template-columns: 1fr; }
  .scan-result { align-items: flex-start; flex-wrap: wrap; }
  .theme-color-control { grid-template-columns: 44px minmax(0,1fr); }
  .theme-color-swatch { width: 44px; height: 44px; }
  .theme-color-control .v-btn { grid-column: 1 / -1; }
  .docker-summary { justify-content: space-between; gap: 12px; }
  .docker-row { min-height: 138px; padding-inline: 12px; }
  .docker-card-badges .v-chip { display: none; }
  .docker-task-head { grid-template-columns: minmax(0,1fr) auto; }
  .docker-task-head .v-btn { grid-column: 1 / 3; justify-self: start; }
  .docker-log { height: 220px; }
}
</style>
