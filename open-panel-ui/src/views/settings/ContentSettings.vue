<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { api } from '../../lib/api'
import { appUrl } from '../../lib/paths'
import MdiIconPicker from '../../components/MdiIconPicker.vue'

const props = defineProps({
  config: { type: Object, required: true }
})
const emit = defineEmits(['message', 'error'])

const currentPageHost = globalThis.location?.hostname?.replace(/^\[|\]$/g, '') || '127.0.0.1'
const scanForm = ref({ target: currentPageHost, range: [80, 65535] })
const scanJob = ref(null)
const scanGroupId = ref('')
const selectedServices = ref([])
const scanResultsDialog = ref(false)
const groupDialog = ref(false)
const groupDraft = ref(null)
let scanTimer

const sortedGroups = computed(() => [...(props.config.groups || [])]
  .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0)))
const scanRunning = computed(() => ['queued', 'running', 'cancelling'].includes(scanJob.value?.status))
const scanProgress = computed(() => scanJob.value?.total ? Math.round(scanJob.value.scanned / scanJob.value.total * 100) : 0)
const scannedServices = computed(() => scanJob.value?.results || [])
const selectedScannedServices = computed(() => {
  const selected = new Set(selectedServices.value)
  return scannedServices.value.filter(service => selected.has(service.id))
})
const scanAllSelected = computed({
  get: () => Boolean(scannedServices.value.length)
    && selectedScannedServices.value.length === scannedServices.value.length,
  set: selected => {
    selectedServices.value = selected ? scannedServices.value.map(service => service.id) : []
  }
})
const scanSelectionIndeterminate = computed(() => selectedScannedServices.value.length > 0 && !scanAllSelected.value)

onBeforeUnmount(() => clearTimeout(scanTimer))

// 生成新分组和搜索引擎使用的 UUID，兼容不支持 randomUUID 的浏览器。
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

// 返回指定分组内按显示顺序排列的卡片。
function cardsForGroup(groupId) {
  return (props.config.cards || [])
    .filter(card => card.groupId === groupId)
    .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
}

// 按当前界面顺序重写分组排序值。
function normalizeGroupSort() {
  sortedGroups.value.forEach((group, index) => { group.sort = index })
}

// 将分组移动一位并立即更新排序草稿。
function moveGroup(group, direction) {
  const ordered = sortedGroups.value
  const index = ordered.findIndex(item => item.id === group.id)
  const target = index + direction
  if (index < 0 || target < 0 || target >= ordered.length) return
  ;[ordered[index], ordered[target]] = [ordered[target], ordered[index]]
  ordered.forEach((item, sort) => { item.sort = sort })
}

// 创建新分组草稿并打开编辑弹窗。
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

// 校验并写入新分组草稿。
function commitGroup() {
  const title = groupDraft.value?.title?.trim()
  if (!title) return
  groupDraft.value.title = title
  groupDraft.value.icon = groupDraft.value.icon?.trim() || 'mdi-folder-outline'
  props.config.groups.push(groupDraft.value)
  groupDialog.value = false
  groupDraft.value = null
  emit('message', `已添加分组“${title}”，保存后生效`)
}

// 删除分组以及所有关联卡片和封面引用。
function removeGroup(group) {
  props.config.groups = props.config.groups.filter(item => item.id !== group.id)
  props.config.cards = props.config.cards.filter(card => card.groupId !== group.id)
  props.config.page.cover.groupIds = props.config.page.cover.groupIds.filter(id => id !== group.id)
  normalizeGroupSort()
}

// 提交端口扫描任务并开始轮询进度。
async function startScan() {
  selectedServices.value = []
  scanGroupId.value = ''
  scanResultsDialog.value = false
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
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 轮询扫描任务，结束后自动展示已发现的服务。
async function pollScan() {
  clearTimeout(scanTimer)
  if (!scanJob.value?.id) return
  try {
    const wasRunning = scanRunning.value
    scanJob.value = await api(`/api/admin/scan/${scanJob.value.id}`)
    if (scanRunning.value) scanTimer = setTimeout(pollScan, 700)
    else if (scanJob.value.status === 'failed') emit('error', scanJob.value.error || '扫描失败')
    if (wasRunning && !scanRunning.value && scannedServices.value.length) scanResultsDialog.value = true
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 请求后端停止当前扫描任务。
async function cancelScan() {
  if (!scanJob.value?.id) return
  try {
    await api(`/api/admin/scan/${scanJob.value.id}`, { method: 'DELETE' })
    pollScan()
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 将选中的扫描结果转换为普通软件服务卡片。
function addScannedServices() {
  if (!scanGroupId.value) {
    emit('error', '请先选择目标分组')
    return
  }
  if (!selectedScannedServices.value.length) return
  const addedCount = selectedScannedServices.value.length
  for (const service of selectedScannedServices.value) {
    props.config.cards.push({
      id: uuid(), groupId: scanGroupId.value, type: 'service', title: service.title,
      remark: service.description, icon: 'mdi-application-outline', iconUrl: service.iconUrl,
      enabled: true, sort: cardsForGroup(scanGroupId.value).length, openTarget: 'new', custom: null, system: null, docker: null,
      service: { serviceType: 'generic', internalUrl: service.url, externalUrl: service.url, statusUrl: service.url, token: '' }
    })
  }
  emit('message', `已添加 ${addedCount} 个 Web 服务卡片，保存后生效`)
  scanResultsDialog.value = false
  selectedServices.value = []
  scanGroupId.value = ''
}

// 添加带有默认配置的新搜索引擎。
function addEngine() {
  props.config.searchEngines.push({
    id: uuid(),
    name: '新搜索引擎',
    icon: 'mdi-magnify',
    urlTemplate: 'https://example.com/search?q={query}',
    enabled: true,
    sort: props.config.searchEngines.length
  })
}

// 从配置草稿中删除指定搜索引擎。
function removeEngine(engine) {
  props.config.searchEngines = props.config.searchEngines.filter(item => item.id !== engine.id)
}
</script>

<template>
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
      <div class="scan-progress-head">
        <div><strong>{{ scanJob.status === 'completed' ? '扫描完成' : scanJob.status === 'cancelled' ? '扫描已停止' : '正在扫描' }}</strong><span>{{ scanJob.scanned }} / {{ scanJob.total }} 端口 · 已发现 {{ scanJob.results.length }} 个服务</span></div>
        <v-btn v-if="scanJob.results.length" variant="outlined" prepend-icon="mdi-format-list-checks" size="small" @click="scanResultsDialog = true">查看结果（{{ scanJob.results.length }}）</v-btn>
      </div>
      <v-progress-linear :model-value="scanProgress" color="secondary" height="7" rounded />
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

  <v-dialog v-model="groupDialog" max-width="480">
    <v-card v-if="groupDraft">
      <v-card-title>添加分组</v-card-title>
      <v-card-text class="dialog-form">
        <v-text-field v-model="groupDraft.title" label="分组名称" autofocus @keydown.enter="commitGroup" />
        <MdiIconPicker v-model="groupDraft.icon" />
        <v-select v-model="groupDraft.displayMode" label="卡片显示方式" :items="[{title:'标题、图标、备注',value:'detail'},{title:'只显示图标',value:'icon'}]" />
        <v-switch v-model="groupDraft.enabled" label="在首页显示" color="primary" hide-details />
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="groupDialog = false">取消</v-btn>
        <v-btn color="secondary" :disabled="!groupDraft.title?.trim()" @click="commitGroup">添加</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="scanResultsDialog" max-width="760" scrollable>
    <v-card>
      <v-card-title class="scan-results-title">
        <span>发现的 Web 服务</span>
        <v-spacer />
        <v-btn icon="mdi-close" variant="text" aria-label="关闭" @click="scanResultsDialog = false" />
      </v-card-title>
      <v-card-text class="scan-results-dialog-content">
        <div class="scan-results-toolbar">
          <div class="scan-results-selection">
            <v-checkbox-btn v-model="scanAllSelected" label="全选" color="primary" :indeterminate="scanSelectionIndeterminate" />
            <strong>已选 {{ selectedScannedServices.length }} / {{ scannedServices.length }}</strong>
          </div>
          <v-select
            v-model="scanGroupId"
            class="scan-results-group"
            label="目标分组"
            placeholder="请选择要添加到的分组"
            :items="sortedGroups"
            item-title="title"
            item-value="id"
            no-data-text="暂无可选分组"
            clearable
            hide-details
            persistent-placeholder
          />
        </div>
        <div class="scan-results">
          <label v-for="service in scannedServices" :key="service.id" class="scan-result">
            <v-checkbox-btn v-model="selectedServices" :value="service.id" color="primary" :aria-label="`选择 ${service.title}`" />
            <span class="scan-icon">
              <img v-if="service.iconUrl" :src="appUrl(service.iconUrl)" alt="" @error="service.iconUrl = ''" />
              <v-icon v-else icon="mdi-web" />
            </span>
            <span class="scan-copy"><strong>{{ service.title }}</strong><small>{{ service.url }}</small><small>{{ service.description }}</small></span>
            <v-chip size="small" variant="tonal">{{ service.protocol.toUpperCase() }} {{ service.statusCode }}</v-chip>
          </label>
        </div>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="scanResultsDialog = false">取消</v-btn>
        <v-btn color="secondary" prepend-icon="mdi-plus" :disabled="scanRunning || !scanGroupId || !selectedScannedServices.length" @click="addScannedServices">确认添加（{{ selectedScannedServices.length }}）</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.settings-section { padding-block: 28px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.section-heading h2 { margin: 0; font-size: 1.08rem; letter-spacing: 0; }
.section-heading p { margin: 5px 0 0; color: rgba(var(--v-theme-on-surface),.56); font-size: .84rem; }
.editable-list { display: grid; gap: 10px; }
.editable-row { display: flex; align-items: center; gap: 10px; min-width: 0; padding: 12px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgb(var(--v-theme-surface)); }
.editable-row > :first-child { flex: 0 1 190px; }
.editable-row > :nth-child(2) { flex: 0 1 190px; }
.editable-row > :nth-child(3) { flex: 1 1 280px; }
.group-editor-list { display: grid; }
.group-editor { padding-block: 20px; border-top: 1px solid rgba(var(--v-theme-on-surface),.1); }
.group-editor:last-child { border-bottom: 1px solid rgba(var(--v-theme-on-surface),.1); }
.group-editor-head { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: center; gap: 14px; }
.group-fields { display: grid; grid-template-columns: minmax(150px,.8fr) minmax(150px,.8fr) minmax(210px,1fr); gap: 12px; }
.group-actions { display: flex; align-items: center; gap: 2px; }
.dialog-form { display: grid; gap: 4px; padding-top: 20px !important; }
.scan-controls { display: grid; grid-template-columns: minmax(190px, .7fr) minmax(360px, 1.5fr) auto; align-items: start; gap: 16px; }
.range-control { padding-top: 8px; }
.range-inputs { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: -6px; }
.scan-progress { display: grid; gap: 11px; margin-top: 18px; }
.scan-progress-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: .82rem; }
.scan-progress-head > div { display: grid; gap: 3px; }
.scan-progress span { color: rgba(var(--v-theme-on-surface),.58); }
.scan-results { display: grid; gap: 8px; margin-top: 18px; }
.scan-result { display: flex; align-items: center; min-width: 0; gap: 10px; padding: 11px 12px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgb(var(--v-theme-surface)); cursor: pointer; }
.scan-result > .v-selection-control { flex: 0 0 auto; }
.scan-icon { position: relative; display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; overflow: hidden; border-radius: 7px; background: rgb(var(--v-theme-secondary)); color: #1a211d; }
.scan-icon img { position: absolute; z-index: 1; width: 28px; height: 28px; object-fit: contain; }
.scan-copy { display: grid; min-width: 0; flex: 1; }
.scan-copy strong, .scan-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scan-copy small { color: rgba(var(--v-theme-on-surface),.56); font-size: .75rem; }
.scan-results-title { display: flex; min-height: 58px; align-items: center; padding-right: 8px; }
.scan-results-dialog-content { display: grid; min-height: 180px; gap: 14px; }
.scan-results-toolbar { display: grid; grid-template-columns: minmax(0,1fr) minmax(240px,300px); align-items: center; gap: 16px; }
.scan-results-selection { display: flex; min-width: 0; align-items: center; gap: 12px; }
.scan-results-selection > .v-selection-control { flex: 0 0 auto; }
.scan-results-selection strong { font-size: .86rem; white-space: nowrap; }
.scan-results-group { min-width: 0; }
.scan-results-dialog-content .scan-results { max-height: min(56svh, 560px); overflow-y: auto; margin-top: 0; padding-right: 2px; }
@media (max-width: 820px) {
  .editable-row { align-items: stretch; flex-wrap: wrap; }
  .editable-row > :first-child, .editable-row > :nth-child(2), .editable-row > :nth-child(3) { flex: 1 1 220px; }
  .scan-controls { grid-template-columns: 1fr; }
  .group-editor-head { grid-template-columns: 1fr; }
  .group-fields { grid-template-columns: 1fr; }
  .group-actions { justify-content: flex-end; }
}
@media (max-width: 540px) {
  .section-heading { align-items: flex-start; flex-direction: column; }
  .scan-progress-head { align-items: flex-start; flex-direction: column; }
  .scan-results-toolbar { grid-template-columns: 1fr; }
  .scan-result { align-items: flex-start; flex-wrap: wrap; }
}
</style>
