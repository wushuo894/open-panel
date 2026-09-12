<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, getToken, setToken } from '../lib/api'
import { appUrl } from '../lib/paths'
import { appState } from '../stores/app'

const router = useRouter()
const tab = ref('appearance')
const config = ref(null)
const loading = ref(true)
const saving = ref(false)
const message = ref('')
const error = ref('')
const cardDialog = ref(false)
const editingCard = ref(null)
const editingOriginalGroupId = ref('')
const importInput = ref(null)
const updateInfo = ref(null)
const updateLoading = ref(false)
const updateAutoChecked = ref(false)
const uploadingIcon = ref(false)
const uploadingBackground = ref(false)
const loadingMetadata = ref(false)
const dockerContainers = ref([])
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const changingPassword = ref(false)
const scanForm = ref({ target: '127.0.0.1', range: [80, 65535] })
const scanJob = ref(null)
const scanGroupId = ref('')
const selectedServices = ref([])
let scanTimer

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

const cardTypes = [
  { title: '自定义链接', value: 'custom' },
  { title: '系统信息', value: 'system' },
  { title: '软件服务', value: 'service' },
  { title: 'Docker 容器', value: 'docker' }
]
const serviceTypes = [
  { title: 'Emby', value: 'emby' },
  { title: 'ani-rss', value: 'ani-rss' },
  { title: 'qBittorrent', value: 'qbit' },
  { title: '通用 Web 服务', value: 'generic' }
]
const serviceDefaults = {
  emby: ['Emby', 'mdi-play-circle-outline', '媒体服务器'],
  'ani-rss': ['ani-rss', 'mdi-rss', '自动追番与下载'],
  qbit: ['qBittorrent', 'mdi-download-network-outline', '下载服务'],
  generic: ['Web 服务', 'mdi-application-outline', '自托管 Web 服务']
}
const serviceAuthHints = {
  emby: '填写 Emby API Key 后可显示版本、活跃播放和转码数量。',
  'ani-rss': '填写 ani-rss 设置页生成的 API Key。',
  qbit: '填写 qBittorrent 生成的 qbt_ 开头 API Key。',
  generic: '通用服务使用状态检测 URL 探测 HTTP 状态。'
}
const currentServiceAuthHint = computed(() => serviceAuthHints[editingCard.value?.service?.serviceType] || serviceAuthHints.generic)
const scanRunning = computed(() => ['queued', 'running', 'cancelling'].includes(scanJob.value?.status))
const scanProgress = computed(() => scanJob.value?.total ? Math.round(scanJob.value.scanned / scanJob.value.total * 100) : 0)

onMounted(load)
onBeforeUnmount(() => clearTimeout(scanTimer))
watch(tab, value => {
  if (value !== 'about' || updateAutoChecked.value) return
  updateAutoChecked.value = true
  checkUpdate()
})

async function load() {
  loading.value = true
  try {
    const [loadedConfig, version] = await Promise.all([
      api('/api/admin/config'),
      api('/api/admin/version')
    ])
    config.value = loadedConfig
    updateInfo.value = version
    scanGroupId.value = sortedGroups.value[0]?.id || ''
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
function uuid() { return crypto.randomUUID() }

function cardsForGroup(groupId) {
  return (config.value?.cards || [])
    .filter(card => card.groupId === groupId)
    .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
}

function cardTypeName(type) {
  return cardTypes.find(item => item.value === type)?.title || type
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

function moveCard(card, direction) {
  const ordered = cardsForGroup(card.groupId)
  const index = ordered.findIndex(item => item.id === card.id)
  const target = index + direction
  if (index < 0 || target < 0 || target >= ordered.length) return
  ;[ordered[index], ordered[target]] = [ordered[target], ordered[index]]
  ordered.forEach((item, sort) => { item.sort = sort })
}

function addGroup() {
  config.value.groups.push({ id: uuid(), title: '新分组', icon: 'mdi-folder-outline', displayMode: 'detail', enabled: true, sort: sortedGroups.value.length })
}

function removeGroup(group) {
  config.value.groups = config.value.groups.filter(item => item.id !== group.id)
  config.value.cards = config.value.cards.filter(card => card.groupId !== group.id)
  config.value.page.cover.groupIds = config.value.page.cover.groupIds.filter(id => id !== group.id)
  normalizeGroupSort()
}

function newCard(groupId = sortedGroups.value[0]?.id || '') {
  editingOriginalGroupId.value = ''
  editingCard.value = {
    id: uuid(), groupId, type: 'custom', title: '新卡片', remark: '', icon: 'mdi-web', iconUrl: '', enabled: true,
    sort: cardsForGroup(groupId).length, openTarget: 'new',
    custom: { internalUrl: '', externalUrl: '' },
    system: { metric: 'overview' },
    service: { serviceType: 'generic', internalUrl: '', externalUrl: '', statusUrl: '', token: '' },
    docker: { containerId: '', internalUrl: '', externalUrl: '' }
  }
  cardDialog.value = true
  loadDockerContainers()
}

function editCard(card) {
  editingOriginalGroupId.value = card.groupId
  editingCard.value = JSON.parse(JSON.stringify(card))
  editingCard.value.custom ||= { internalUrl: '', externalUrl: '' }
  editingCard.value.system ||= { metric: 'overview' }
  editingCard.value.service ||= { serviceType: 'generic', internalUrl: '', externalUrl: '', statusUrl: '', token: '' }
  editingCard.value.docker ||= { containerId: '', internalUrl: '', externalUrl: '' }
  cardDialog.value = true
  loadDockerContainers()
}

function commitCard() {
  if (!editingCard.value.title || !editingCard.value.groupId) return
  const index = config.value.cards.findIndex(card => card.id === editingCard.value.id)
  const destinationGroupId = editingCard.value.groupId
  if (editingOriginalGroupId.value && editingOriginalGroupId.value !== destinationGroupId) {
    editingCard.value.sort = cardsForGroup(destinationGroupId).length
  }
  if (index >= 0) config.value.cards.splice(index, 1, editingCard.value)
  else config.value.cards.push(editingCard.value)
  if (editingOriginalGroupId.value) normalizeCardSort(editingOriginalGroupId.value)
  normalizeCardSort(destinationGroupId)
  cardDialog.value = false
}

function removeCard(card) {
  config.value.cards = config.value.cards.filter(item => item.id !== card.id)
  normalizeCardSort(card.groupId)
}

function applyCardTypeDefaults(type) {
  editingCard.value.type = type
  if (type === 'system') {
    Object.assign(editingCard.value, { title: '系统状态', icon: 'mdi-server-outline', remark: 'CPU、内存、存储与网络' })
  } else if (type === 'docker') {
    Object.assign(editingCard.value, { title: 'Docker 容器', icon: 'mdi-docker', remark: '容器运行状态' })
    loadDockerContainers()
  } else if (type === 'service') {
    applyServiceDefaults(editingCard.value.service.serviceType || 'generic')
  } else {
    Object.assign(editingCard.value, { title: '自定义链接', icon: 'mdi-web', remark: '打开常用网站或服务' })
  }
}

function applyServiceDefaults(type) {
  editingCard.value.service.serviceType = type
  const [title, icon, remark] = serviceDefaults[type] || serviceDefaults.generic
  Object.assign(editingCard.value, { title, icon, remark })
}

async function autoFillCustomCard() {
  const url = editingCard.value.custom.internalUrl?.trim() || editingCard.value.custom.externalUrl?.trim()
  if (!url) { error.value = '请先填写内网或公网 URL'; return }
  loadingMetadata.value = true
  error.value = ''
  try {
    const metadata = await api('/api/admin/link-metadata', {
      method: 'POST',
      body: JSON.stringify({ url })
    })
    if (metadata.title) editingCard.value.title = metadata.title
    if (metadata.description) editingCard.value.remark = metadata.description
    if (metadata.iconUrl) editingCard.value.iconUrl = metadata.iconUrl
    message.value = '已从链接补全标题、简介和图标'
  } catch (e) { error.value = e.message }
  finally { loadingMetadata.value = false }
}

async function loadDockerContainers() {
  try { dockerContainers.value = await api('/api/admin/docker/containers') }
  catch { dockerContainers.value = [] }
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

async function uploadIcon(value) {
  const file = Array.isArray(value) ? value[0] : value
  if (!file) return
  uploadingIcon.value = true
  error.value = ''
  const body = new FormData()
  body.append('file', file)
  try {
    const result = await api('/api/admin/assets/icon', { method: 'POST', body })
    editingCard.value.iconUrl = result.url
  } catch (e) { error.value = e.message }
  finally { uploadingIcon.value = false }
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
    anchor.download = 'open-panel-config.json'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (e) { error.value = e.message }
}

async function importConfig(event) {
  const file = event.target.files?.[0]
  if (!file) return
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

async function installUpdate() {
  updateLoading.value = true
  try {
    const result = await api('/api/admin/update/install', { method: 'POST' })
    message.value = result.restarting ? '更新已下载，服务即将重启' : '更新任务已提交'
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
        <v-tab value="security" prepend-icon="mdi-shield-lock-outline">安全</v-tab>
        <v-tab value="data" prepend-icon="mdi-database-outline">数据</v-tab>
        <v-tab value="about" prepend-icon="mdi-information-outline">关于</v-tab>
      </v-tabs>

      <v-alert v-if="message" type="success" variant="tonal" closable @click:close="message = ''">{{ message }}</v-alert>
      <v-alert v-if="error" type="error" variant="tonal" closable @click:close="error = ''">{{ error }}</v-alert>

      <v-window v-model="tab">
        <v-window-item value="appearance">
          <section class="settings-section">
            <div class="section-heading"><div><h2>站点与页面</h2><p>名称、主题与首页呈现方式</p></div></div>
            <div class="form-grid">
              <v-text-field v-model="config.site.title" label="网站标题" />
              <v-text-field v-model="config.site.icon" label="网站图标 URL" />
              <v-select v-model="config.site.theme" label="主题" :items="[{title:'跟随系统',value:'system'},{title:'浅色',value:'light'},{title:'深色',value:'dark'}]" />
              <v-select v-model="config.page.mode" label="页面模式" :items="[{title:'大封面模式',value:'cover'},{title:'列表模式',value:'list'}]" />
              <v-switch v-model="config.site.cornerControlsHoverOnly" label="右上角按钮仅悬停时显示" color="primary" hide-details />
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
                  backgroundImage: `linear-gradient(rgba(8, 12, 11, ${config.site.backgroundOverlay}), rgba(8, 12, 11, ${config.site.backgroundOverlay})), url(${appUrl(config.page.cover.wallpapers[0] || config.site.background)})`
                }"
              />
              <v-file-input
                label="上传背景图片"
                accept="image/png,image/jpeg,image/gif,image/webp"
                prepend-icon="mdi-image-plus-outline"
                :loading="uploadingBackground"
                hint="PNG、JPEG、GIF 或 WebP，最大 20 MB"
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
            <div class="section-heading"><div><h2>分组与卡片</h2><p>在每个分组内编辑卡片，并调整首页显示顺序</p></div><v-btn prepend-icon="mdi-plus" variant="outlined" @click="addGroup">添加分组</v-btn></div>
            <div class="group-editor-list">
              <div v-for="(group, groupIndex) in sortedGroups" :key="group.id" class="group-editor">
                <div class="group-editor-head">
                  <div class="group-fields">
                    <v-text-field v-model="group.title" label="分组名称" hide-details />
                    <v-text-field v-model="group.icon" label="MDI 图标" hide-details />
                    <v-select v-model="group.displayMode" label="卡片显示" :items="[{title:'标题、图标、备注',value:'detail'},{title:'只显示图标',value:'icon'}]" hide-details />
                  </div>
                  <div class="group-actions">
                    <v-switch v-model="group.enabled" label="显示" color="primary" hide-details />
                    <v-btn icon="mdi-arrow-up" variant="text" :disabled="groupIndex === 0" aria-label="分组上移" title="分组上移" @click="moveGroup(group, -1)" />
                    <v-btn icon="mdi-arrow-down" variant="text" :disabled="groupIndex === sortedGroups.length - 1" aria-label="分组下移" title="分组下移" @click="moveGroup(group, 1)" />
                    <v-btn icon="mdi-delete-outline" variant="text" color="error" aria-label="删除分组" title="删除分组" @click="removeGroup(group)" />
                  </div>
                </div>
                <div class="group-card-toolbar">
                  <strong>卡片</strong>
                  <v-btn prepend-icon="mdi-plus" variant="text" size="small" @click="newCard(group.id)">添加卡片</v-btn>
                </div>
                <div class="group-card-list">
                  <div v-for="(card, cardIndex) in cardsForGroup(group.id)" :key="card.id" class="item-row group-card-row">
                    <span class="item-icon">
                      <img v-if="card.iconUrl" :src="appUrl(card.iconUrl)" alt="" />
                      <v-icon v-else :icon="card.icon || 'mdi-web'" />
                    </span>
                    <div><strong>{{ card.title }}</strong><small>{{ cardTypeName(card.type) }} · {{ card.remark || '无备注' }}</small></div>
                    <v-spacer />
                    <v-switch v-model="card.enabled" color="primary" hide-details aria-label="显示卡片" />
                    <v-btn icon="mdi-arrow-up" variant="text" :disabled="cardIndex === 0" aria-label="卡片上移" title="卡片上移" @click="moveCard(card, -1)" />
                    <v-btn icon="mdi-arrow-down" variant="text" :disabled="cardIndex === cardsForGroup(group.id).length - 1" aria-label="卡片下移" title="卡片下移" @click="moveCard(card, 1)" />
                    <v-btn icon="mdi-pencil-outline" variant="text" aria-label="编辑卡片" title="编辑卡片" @click="editCard(card)" />
                    <v-btn icon="mdi-delete-outline" variant="text" color="error" aria-label="删除卡片" title="删除卡片" @click="removeCard(card)" />
                  </div>
                  <div v-if="!cardsForGroup(group.id).length" class="group-empty">暂无卡片</div>
                </div>
              </div>
            </div>
          </section>

          <section class="settings-section">
            <div class="section-heading"><div><h2>搜索引擎</h2><p>URL 模板中使用 {query} 作为关键词占位符</p></div><v-btn prepend-icon="mdi-plus" variant="outlined" @click="addEngine">添加引擎</v-btn></div>
            <div class="editable-list">
              <div v-for="engine in config.searchEngines" :key="engine.id" class="editable-row engine-row">
                <v-text-field v-model="engine.name" label="名称" hide-details />
                <v-text-field v-model="engine.icon" label="MDI 图标" hide-details />
                <v-text-field v-model="engine.urlTemplate" label="搜索 URL 模板" hide-details />
                <v-switch v-model="engine.enabled" label="启用" color="primary" hide-details />
                <v-btn icon="mdi-delete-outline" variant="text" color="error" aria-label="删除引擎" @click="removeEngine(engine)" />
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
            <div class="section-heading"><div><h2>管理员密码</h2><p>修改后所有现有登录令牌立即失效</p></div></div>
            <form class="password-form" @submit.prevent="changePassword">
              <v-text-field v-model="passwordForm.currentPassword" label="当前密码" type="password" autocomplete="current-password" />
              <v-text-field v-model="passwordForm.newPassword" label="新密码" type="password" autocomplete="new-password" />
              <v-text-field v-model="passwordForm.confirmPassword" label="确认新密码" type="password" autocomplete="new-password" />
              <v-btn type="submit" variant="outlined" prepend-icon="mdi-lock-reset" :loading="changingPassword">修改密码</v-btn>
            </form>
          </section>
        </v-window-item>

        <v-window-item value="data">
          <section class="settings-section">
            <div class="section-heading"><div><h2>配置备份</h2><p>导出文件包含完整配置和凭据，请妥善保管</p></div></div>
            <div class="action-row">
              <v-btn prepend-icon="mdi-download-outline" variant="outlined" @click="exportConfig">导出配置</v-btn>
              <v-btn prepend-icon="mdi-upload-outline" variant="outlined" @click="importInput.click()">导入配置</v-btn>
              <input ref="importInput" type="file" accept="application/json,.json" hidden @change="importConfig" />
            </div>
          </section>
        </v-window-item>

        <v-window-item value="about">
          <section class="settings-section">
            <div class="section-heading"><div><h2>项目更新</h2><p>从 GitHub Releases 检查稳定版本</p></div></div>
            <v-text-field
              v-model="config.update.githubToken"
              label="GitHub Token"
              type="password"
              autocomplete="off"
              prepend-inner-icon="mdi-github"
              hint="可选，仅用于 GitHub API 更新检查，避免匿名请求受 IP 频率限制"
              persistent-hint
              class="update-token"
            />
            <div class="update-row">
              <div><strong>Open Panel <span v-if="updateInfo?.currentVersion" class="version-badge">v{{ updateInfo.currentVersion }}</span></strong><small v-if="updateInfo?.latestVersion">当前 {{ updateInfo.currentVersion }} · 最新 {{ updateInfo.latestVersion }}</small><small v-else>尚未检查更新</small></div>
              <v-spacer />
              <v-btn variant="outlined" prepend-icon="mdi-refresh" :loading="updateLoading" @click="checkUpdate">检查更新</v-btn>
              <v-btn v-if="updateInfo?.available && !updateInfo.container" color="secondary" prepend-icon="mdi-download" :loading="updateLoading" @click="installUpdate">安装更新</v-btn>
              <v-btn v-if="updateInfo?.available && updateInfo.container" :href="updateInfo.releaseUrl" target="_blank" color="secondary" prepend-icon="mdi-docker">查看发行版</v-btn>
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

    <v-dialog v-model="cardDialog" max-width="720" scrollable>
      <v-card v-if="editingCard">
        <v-card-title>编辑卡片</v-card-title>
        <v-card-text class="dialog-form">
          <div class="form-grid">
            <v-text-field v-model="editingCard.title" label="标题" />
            <v-select v-model="editingCard.groupId" label="所属分组" :items="sortedGroups" item-title="title" item-value="id" />
            <v-select v-model="editingCard.type" label="卡片类型" :items="cardTypes" @update:model-value="applyCardTypeDefaults" />
            <v-text-field v-model="editingCard.icon" label="MDI 图标" />
            <v-text-field v-model="editingCard.iconUrl" label="自定义图标 URL" />
            <v-select v-model="editingCard.openTarget" label="打开方式" :items="[{title:'新窗口',value:'new'},{title:'当前窗口',value:'self'}]" />
          </div>
          <div class="icon-upload-row">
            <span v-if="editingCard.iconUrl" class="icon-preview"><img :src="appUrl(editingCard.iconUrl)" alt="当前卡片图标" /></span>
            <v-file-input
              label="上传卡片图标"
              accept="image/png,image/jpeg,image/gif,image/webp"
              prepend-icon="mdi-image-plus-outline"
              :loading="uploadingIcon"
              hint="PNG、JPEG、GIF 或 WebP，最大 2 MB"
              persistent-hint
              @update:model-value="uploadIcon"
            />
          </div>
          <v-text-field v-model="editingCard.remark" label="备注" />

          <template v-if="editingCard.type === 'custom'">
            <div class="custom-link-fields">
              <v-text-field v-model="editingCard.custom.internalUrl" label="内网 URL" />
              <v-text-field v-model="editingCard.custom.externalUrl" label="公网 URL" />
              <v-btn prepend-icon="mdi-auto-fix" variant="outlined" :loading="loadingMetadata" @click="autoFillCustomCard">从链接自动补全</v-btn>
            </div>
          </template>
          <template v-else-if="editingCard.type === 'system'">
            <v-select v-model="editingCard.system.metric" label="系统信息" :items="[{title:'综合信息',value:'overview'},{title:'CPU',value:'cpu'},{title:'内存',value:'memory'},{title:'存储',value:'storage'},{title:'网络',value:'network'}]" />
          </template>
          <template v-else-if="editingCard.type === 'service'">
            <v-select v-model="editingCard.service.serviceType" label="服务类型" :items="serviceTypes" @update:model-value="applyServiceDefaults" />
            <p class="field-hint">{{ currentServiceAuthHint }}</p>
            <div class="form-grid">
              <v-text-field v-model="editingCard.service.internalUrl" label="内网 URL" />
              <v-text-field v-model="editingCard.service.externalUrl" label="公网 URL" />
            </div>
            <v-text-field v-if="editingCard.service.serviceType === 'generic'" v-model="editingCard.service.statusUrl" label="状态检测 URL" />
            <v-text-field v-if="editingCard.service.serviceType !== 'generic'" v-model="editingCard.service.token" label="API Key" type="password" autocomplete="new-password" />
          </template>
          <template v-else-if="editingCard.type === 'docker'">
            <v-combobox
              v-model="editingCard.docker.containerId"
              label="容器 ID 或名称"
              :items="dockerContainers"
              item-title="label"
              item-value="id"
              :return-object="false"
              hint="可从当前 Docker 容器中选择，也可手动填写"
              persistent-hint
            />
            <div class="form-grid">
              <v-text-field v-model="editingCard.docker.internalUrl" label="内网 URL" />
              <v-text-field v-model="editingCard.docker.externalUrl" label="公网 URL" />
            </div>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="cardDialog = false">取消</v-btn>
          <v-btn color="secondary" @click="commitCard">确定</v-btn>
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
.settings-section { padding-block: 28px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.section-heading h2 { margin: 0; font-size: 1.08rem; letter-spacing: 0; }
.section-heading p { margin: 5px 0 0; color: rgba(var(--v-theme-on-surface),.56); font-size: .84rem; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 4px 16px; }
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
.group-card-toolbar { display: flex; align-items: center; justify-content: space-between; min-height: 52px; padding-left: 4px; }
.group-card-toolbar strong { font-size: .83rem; letter-spacing: 0; }
.group-card-list { display: grid; gap: 8px; }
.group-card-row { padding-block: 9px; }
.group-empty { min-height: 58px; display: grid; place-items: center; border: 1px dashed rgba(var(--v-theme-on-surface),.14); border-radius: 8px; color: rgba(var(--v-theme-on-surface),.5); font-size: .82rem; }
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
.icon-upload-row { display: flex; align-items: flex-start; gap: 14px; }
.icon-upload-row > :last-child { flex: 1; }
.icon-preview { display: grid; width: 56px; height: 56px; flex: 0 0 56px; place-items: center; overflow: hidden; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; }
.field-hint { margin: -8px 0 12px; color: rgba(var(--v-theme-on-surface), .62); font-size: .78rem; line-height: 1.5; }
.icon-preview img { width: 42px; height: 42px; object-fit: contain; }
.custom-link-fields { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)) auto; align-items: start; gap: 16px; }
@media (max-width: 820px) {
  .form-grid { grid-template-columns: 1fr; }
  .switch-grid { grid-template-columns: repeat(2, 1fr); }
  .editable-row { align-items: stretch; flex-wrap: wrap; }
  .editable-row > :first-child, .editable-row > :nth-child(2), .editable-row > :nth-child(3) { flex: 1 1 220px; }
  .password-form { grid-template-columns: 1fr 1fr; }
  .scan-controls { grid-template-columns: 1fr; }
  .background-upload { grid-template-columns: 1fr; }
  .custom-link-fields { grid-template-columns: 1fr; }
  .group-editor-head { grid-template-columns: 1fr; }
  .group-fields { grid-template-columns: 1fr; }
  .group-actions { justify-content: flex-end; }
}
@media (max-width: 540px) {
  .switch-grid { grid-template-columns: 1fr; }
  .section-heading { align-items: flex-start; flex-direction: column; }
  .header-inner > div { display: none; }
  .item-row { flex-wrap: wrap; }
  .password-form { grid-template-columns: 1fr; }
  .scan-add-row { grid-template-columns: 1fr; }
  .scan-result { align-items: flex-start; flex-wrap: wrap; }
}
</style>
